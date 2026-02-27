package com.frauddetection.client.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.frauddetection.client.client.FraudDetectionApiClient;
import com.frauddetection.client.config.FraudApiProperties;
import com.frauddetection.client.dto.BatchResponse;
import com.frauddetection.client.dto.FraudPredictionResponse;
import com.frauddetection.client.dto.TransactionRequest;
import com.frauddetection.client.exception.CircuitOpenException;
import com.frauddetection.client.exception.FraudApiUnavailableException;
import com.frauddetection.client.model.TransactionRecord;
import com.frauddetection.client.repository.TransactionRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link FraudDetectionService}.
 *
 * <p>Verifies circuit breaker state transitions and persistence behaviour
 * without making real HTTP calls — all dependencies are mocked with Mockito.
 *
 * <p>Important: when overriding a stub from thenThrow to thenReturn, use
 * {@code doReturn().when()} instead of {@code when().thenReturn()} to avoid
 * the previous thenThrow firing during stub setup.
 */
@ExtendWith(MockitoExtension.class)
class FraudDetectionServiceTest {

    @Mock
    private FraudDetectionApiClient apiClient;

    @Mock
    private TransactionRecordRepository repository;

    private FraudDetectionService service;
    private TransactionRequest sampleRequest;

    @BeforeEach
    void setUp() {
        FraudApiProperties props = new FraudApiProperties();
        FraudApiProperties.CircuitBreaker cb = new FraudApiProperties.CircuitBreaker();
        cb.setFailureThreshold(5);
        cb.setCooldownSeconds(30);
        props.setCircuitBreaker(cb);

        ObjectMapper mapper = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

        service = new FraudDetectionService(apiClient, repository, props, mapper);

        sampleRequest = new TransactionRequest(
                "TXN-001", 250.0, "online", "CA",
                OffsetDateTime.parse("2024-03-01T03:00:00Z"), "1500");
    }

    // ------------------------------------------------------------------ //
    // Happy path                                                           //
    // ------------------------------------------------------------------ //

    @Test
    void checkTransaction_success_returnsPredictionAndPersists() {
        FraudPredictionResponse expected = buildResponse("TXN-001", 0.87, true, "high");
        when(apiClient.predict(any())).thenReturn(expected);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        FraudPredictionResponse result = service.checkTransaction(sampleRequest);

        assertThat(result.getTransactionId()).isEqualTo("TXN-001");
        assertThat(result.isFraudulent()).isTrue();
        assertThat(service.getCircuitState()).isEqualTo(FraudDetectionService.CircuitState.CLOSED);

        // Verify persistence
        ArgumentCaptor<TransactionRecord> captor = ArgumentCaptor.forClass(TransactionRecord.class);
        verify(repository).save(captor.capture());
        TransactionRecord saved = captor.getValue();
        assertThat(saved.getTransactionId()).isEqualTo("TXN-001");
        assertThat(saved.getFraudScore()).isEqualTo(0.87);
        assertThat(saved.isFraudulent()).isTrue();
    }

    @Test
    void checkTransaction_success_resetsFailureCounter() {
        // Cause 4 failures (below threshold of 5)
        when(apiClient.predict(any())).thenThrow(new FraudApiUnavailableException("down"));
        for (int i = 0; i < 4; i++) {
            TransactionRequest req = new TransactionRequest(
                    "TXN-F" + i, 100.0, "retail", "NY",
                    OffsetDateTime.now(), "1001");
            try { service.checkTransaction(req); } catch (Exception ignored) {}
        }
        assertThat(service.getFailureCount()).isEqualTo(4);

        // Re-stub using doReturn() — avoids triggering the previous thenThrow during setup
        doReturn(buildResponse("TXN-OK", 0.1, false, "low")).when(apiClient).predict(any());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.checkTransaction(sampleRequest);

        assertThat(service.getFailureCount()).isEqualTo(0);
        assertThat(service.getCircuitState()).isEqualTo(FraudDetectionService.CircuitState.CLOSED);
    }

    // ------------------------------------------------------------------ //
    // Circuit breaker: CLOSED → OPEN                                      //
    // ------------------------------------------------------------------ //

    @Test
    void checkTransaction_fiveConsecutiveFailures_opensCircuit() {
        when(apiClient.predict(any())).thenThrow(new FraudApiUnavailableException("API down"));

        for (int i = 0; i < 5; i++) {
            TransactionRequest req = new TransactionRequest(
                    "TXN-F" + i, 50.0, "grocery", "CA",
                    OffsetDateTime.now(), "1002");
            try { service.checkTransaction(req); } catch (Exception ignored) {}
        }

        assertThat(service.getCircuitState()).isEqualTo(FraudDetectionService.CircuitState.OPEN);
    }

    // ------------------------------------------------------------------ //
    // Circuit breaker: OPEN — fail fast                                   //
    // ------------------------------------------------------------------ //

    @Test
    void checkTransaction_circuitOpen_throwsCircuitOpenExceptionWithoutCallingApi() {
        // Force circuit open by causing 5 failures
        when(apiClient.predict(any())).thenThrow(new FraudApiUnavailableException("down"));
        for (int i = 0; i < 5; i++) {
            TransactionRequest req = new TransactionRequest(
                    "TXN-F" + i, 50.0, "grocery", "CA",
                    OffsetDateTime.now(), "1003");
            try { service.checkTransaction(req); } catch (Exception ignored) {}
        }

        // Reset mock — API must NOT be called while circuit is OPEN
        reset(apiClient);

        assertThatThrownBy(() -> service.checkTransaction(sampleRequest))
                .isInstanceOf(CircuitOpenException.class)
                .hasMessageContaining("OPEN");

        verifyNoInteractions(apiClient);
    }

    // ------------------------------------------------------------------ //
    // Persistence failure does not bubble up                               //
    // ------------------------------------------------------------------ //

    @Test
    void checkTransaction_persistenceFails_stillReturnsPrediction() {
        FraudPredictionResponse expected = buildResponse("TXN-001", 0.3, false, "medium");
        when(apiClient.predict(any())).thenReturn(expected);
        when(repository.save(any())).thenThrow(new RuntimeException("DB write failed"));

        // Persistence errors must not propagate to the caller
        FraudPredictionResponse result = service.checkTransaction(sampleRequest);
        assertThat(result).isNotNull();
        assertThat(result.getTransactionId()).isEqualTo("TXN-001");
    }

    // ------------------------------------------------------------------ //
    // Batch                                                                //
    // ------------------------------------------------------------------ //

    @Test
    void checkBatch_success_persistsAllResults() {
        List<TransactionRequest> requests = List.of(
                new TransactionRequest("TXN-B1", 100.0, "retail", "CA", OffsetDateTime.now(), "1001"),
                new TransactionRequest("TXN-B2", 500.0, "online", "NY", OffsetDateTime.now(), "1002")
        );

        BatchResponse batchResponse = new BatchResponse();
        batchResponse.setResults(List.of(
                buildResponse("TXN-B1", 0.1, false, "low"),
                buildResponse("TXN-B2", 0.9, true, "high")
        ));
        batchResponse.setTotalProcessed(2);
        batchResponse.setFraudDetected(1);

        when(apiClient.batchPredict(any())).thenReturn(batchResponse);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        BatchResponse result = service.checkBatch(requests);

        assertThat(result.getTotalProcessed()).isEqualTo(2);
        assertThat(result.getFraudDetected()).isEqualTo(1);
        verify(repository, times(2)).save(any(TransactionRecord.class));
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
