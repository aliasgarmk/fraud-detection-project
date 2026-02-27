package com.frauddetection.client.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.frauddetection.client.config.FraudApiProperties;
import com.frauddetection.client.dto.BatchRequest;
import com.frauddetection.client.dto.BatchResponse;
import com.frauddetection.client.dto.FraudPredictionResponse;
import com.frauddetection.client.dto.TransactionRequest;
import com.frauddetection.client.exception.DuplicateTransactionException;
import com.frauddetection.client.exception.InvalidTransactionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/**
 * Unit tests for {@link FraudDetectionApiClient}.
 *
 * <p>Uses {@link MockRestServiceServer} to intercept HTTP calls without
 * starting a real server — the Java equivalent of Python's unittest.mock.patch.
 *
 * <p>Note: {@code @Retryable} behavior requires a Spring AOP proxy and is therefore
 * tested end-to-end through {@link com.frauddetection.client.service.FraudDetectionServiceTest},
 * which drives the client through the Spring proxy context.
 */
class FraudDetectionApiClientTest {

    private RestTemplate restTemplate;
    private MockRestServiceServer mockServer;
    private FraudDetectionApiClient client;
    private ObjectMapper objectMapper;
    private TransactionRequest sampleRequest;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .registerModule(new JavaTimeModule());

        restTemplate = new RestTemplate();
        restTemplate.setMessageConverters(List.of(new MappingJackson2HttpMessageConverter(objectMapper)));

        mockServer = MockRestServiceServer.createServer(restTemplate);

        FraudApiProperties props = new FraudApiProperties();
        props.setBaseUrl("http://localhost:8000");

        client = new FraudDetectionApiClient(restTemplate, props);

        sampleRequest = new TransactionRequest(
                "TXN-001", 250.0, "online", "CA",
                OffsetDateTime.parse("2024-03-01T03:00:00Z"), "1500");
    }

    // ------------------------------------------------------------------ //
    // Happy path                                                           //
    // ------------------------------------------------------------------ //

    @Test
    void predict_successOnFirstAttempt_returnsResponse() throws Exception {
        FraudPredictionResponse expected = buildResponse("TXN-001", 0.87, true, "high");
        String responseJson = objectMapper.writeValueAsString(expected);

        mockServer.expect(ExpectedCount.once(),
                requestTo("http://localhost:8000/predict"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

        FraudPredictionResponse result = client.predict(sampleRequest);

        mockServer.verify();
        assertThat(result.getTransactionId()).isEqualTo("TXN-001");
        assertThat(result.isFraudulent()).isTrue();
        assertThat(result.getFraudScore()).isEqualTo(0.87);
        assertThat(result.getConfidence()).isEqualTo("high");
    }

    @Test
    void batchPredict_successOnFirstAttempt_returnsResponse() throws Exception {
        BatchResponse batchResp = new BatchResponse();
        batchResp.setResults(List.of(buildResponse("TXN-001", 0.87, true, "high")));
        batchResp.setTotalProcessed(1);
        batchResp.setFraudDetected(1);
        String responseJson = objectMapper.writeValueAsString(batchResp);

        mockServer.expect(ExpectedCount.once(),
                requestTo("http://localhost:8000/batch"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

        BatchRequest batchReq = new BatchRequest(List.of(sampleRequest));
        BatchResponse result = client.batchPredict(batchReq);

        mockServer.verify();
        assertThat(result.getTotalProcessed()).isEqualTo(1);
        assertThat(result.getFraudDetected()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ //
    // 4xx handling — domain exceptions (no retry for these)               //
    // ------------------------------------------------------------------ //

    @Test
    void predict_422UnprocessableEntity_throwsInvalidTransactionException() {
        mockServer.expect(ExpectedCount.once(),
                requestTo("http://localhost:8000/predict"))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY));

        assertThatThrownBy(() -> client.predict(sampleRequest))
                .isInstanceOf(InvalidTransactionException.class);

        mockServer.verify();
    }

    @Test
    void predict_409Conflict_throwsDuplicateTransactionException() {
        mockServer.expect(ExpectedCount.once(),
                requestTo("http://localhost:8000/predict"))
                .andRespond(withStatus(HttpStatus.CONFLICT));

        assertThatThrownBy(() -> client.predict(sampleRequest))
                .isInstanceOf(DuplicateTransactionException.class);

        mockServer.verify();
    }

    // ------------------------------------------------------------------ //
    // health pass-through                                                  //
    // ------------------------------------------------------------------ //

    @Test
    void health_callsCorrectEndpointAndReturns() {
        mockServer.expect(ExpectedCount.once(),
                requestTo("http://localhost:8000/health"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"status\":\"healthy\"}", MediaType.APPLICATION_JSON));

        client.health();
        mockServer.verify();
    }

    // ------------------------------------------------------------------ //
    // Helper                                                               //
    // ------------------------------------------------------------------ //

    private FraudPredictionResponse buildResponse(String txId, double score,
                                                   boolean fraudulent, String confidence) {
        FraudPredictionResponse r = new FraudPredictionResponse();
        r.setTransactionId(txId);
        r.setFraudScore(score);
        r.setFraudulent(fraudulent);
        r.setConfidence(confidence);
        r.setRiskFactors(List.of());
        r.setProcessingTimeMs(45.0);
        return r;
    }
}
