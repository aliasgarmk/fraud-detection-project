package com.frauddetection.client.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.frauddetection.client.dto.FraudPredictionResponse;
import com.frauddetection.client.dto.TransactionRequest;
import com.frauddetection.client.exception.CircuitOpenException;
import com.frauddetection.client.exception.FraudApiUnavailableException;
import com.frauddetection.client.model.TransactionRecord;
import com.frauddetection.client.repository.TransactionRecordRepository;
import com.frauddetection.client.service.FraudDetectionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Controller-layer unit tests for {@link FraudCheckController}.
 *
 * <p>Uses {@code @WebMvcTest} — only the web layer is loaded, all services and
 * repositories are mocked. This is fast and focused, like {@code @SpringBootTest}
 * but for a single controller slice.
 *
 * <p>Jackson snake_case naming is configured via application.yml
 * (spring.jackson.property-naming-strategy=SNAKE_CASE), so no extra ObjectMapper
 * setup is needed in the test.
 */
@WebMvcTest(FraudCheckController.class)
class FraudCheckControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private FraudDetectionService service;

    @MockBean
    private TransactionRecordRepository repository;

    private static final String VALID_REQUEST = """
            {
              "transaction_id": "TXN-001",
              "amount": 250.00,
              "merchant_category": "online",
              "location": "CA",
              "timestamp": "2024-03-01T03:00:00Z",
              "user_id": "1500"
            }
            """;

    // ------------------------------------------------------------------ //
    // POST /fraud-check                                                    //
    // ------------------------------------------------------------------ //

    @Test
    void checkSingle_validRequest_returns200() throws Exception {
        FraudPredictionResponse response = buildResponse("TXN-001", 0.87, true, "high");
        when(service.checkTransaction(any(TransactionRequest.class))).thenReturn(response);

        mockMvc.perform(post("/fraud-check")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_REQUEST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transaction_id").value("TXN-001"))
                .andExpect(jsonPath("$.is_fraudulent").value(true))
                .andExpect(jsonPath("$.fraud_score").value(0.87))
                .andExpect(jsonPath("$.confidence").value("high"));
    }

    @Test
    void checkSingle_missingAmount_returns400() throws Exception {
        String badRequest = """
                {
                  "transaction_id": "TXN-002",
                  "merchant_category": "online",
                  "location": "CA",
                  "timestamp": "2024-03-01T03:00:00Z",
                  "user_id": "1500"
                }
                """;

        mockMvc.perform(post("/fraud-check")
                .contentType(MediaType.APPLICATION_JSON)
                .content(badRequest))
                .andExpect(status().isBadRequest());
    }

    @Test
    void checkSingle_negativeAmount_returns400() throws Exception {
        String badRequest = """
                {
                  "transaction_id": "TXN-003",
                  "amount": -50.0,
                  "merchant_category": "online",
                  "location": "CA",
                  "timestamp": "2024-03-01T03:00:00Z",
                  "user_id": "1500"
                }
                """;

        mockMvc.perform(post("/fraud-check")
                .contentType(MediaType.APPLICATION_JSON)
                .content(badRequest))
                .andExpect(status().isBadRequest());
    }

    @Test
    void checkSingle_circuitOpen_returns503() throws Exception {
        when(service.checkTransaction(any(TransactionRequest.class)))
                .thenThrow(new CircuitOpenException("Circuit is OPEN"));

        mockMvc.perform(post("/fraud-check")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_REQUEST))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value("Circuit is OPEN"));
    }

    @Test
    void checkSingle_apiUnavailable_returns503() throws Exception {
        when(service.checkTransaction(any(TransactionRequest.class)))
                .thenThrow(new FraudApiUnavailableException("All retries exhausted"));

        mockMvc.perform(post("/fraud-check")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_REQUEST))
                .andExpect(status().isServiceUnavailable());
    }

    // ------------------------------------------------------------------ //
    // GET /fraud-check/history                                             //
    // ------------------------------------------------------------------ //

    @Test
    void history_returnsListFromRepository() throws Exception {
        TransactionRecord record = buildRecord("TXN-001");
        when(repository.findAll()).thenReturn(List.of(record));

        mockMvc.perform(get("/fraud-check/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].transaction_id").value("TXN-001"));
    }

    // ------------------------------------------------------------------ //
    // GET /fraud-check/history/{transactionId}                            //
    // ------------------------------------------------------------------ //

    @Test
    void historyById_existingId_returns200() throws Exception {
        TransactionRecord record = buildRecord("TXN-001");
        when(repository.findByTransactionId("TXN-001")).thenReturn(Optional.of(record));

        mockMvc.perform(get("/fraud-check/history/TXN-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transaction_id").value("TXN-001"));
    }

    @Test
    void historyById_unknownId_returns404() throws Exception {
        when(repository.findByTransactionId("UNKNOWN")).thenReturn(Optional.empty());

        mockMvc.perform(get("/fraud-check/history/UNKNOWN"))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ //
    // GET /fraud-check/circuit-status                                     //
    // ------------------------------------------------------------------ //

    @Test
    void circuitStatus_returnsStateAndFailureCount() throws Exception {
        when(service.getCircuitState()).thenReturn(FraudDetectionService.CircuitState.CLOSED);
        when(service.getFailureCount()).thenReturn(0);

        mockMvc.perform(get("/fraud-check/circuit-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("CLOSED"))
                .andExpect(jsonPath("$.failure_count").value(0));
    }

    // ------------------------------------------------------------------ //
    // Helpers                                                              //
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

    private TransactionRecord buildRecord(String txId) {
        TransactionRecord record = new TransactionRecord();
        record.setTransactionId(txId);
        record.setFraudScore(0.87);
        record.setFraudulent(true);
        record.setConfidence("high");
        record.setMerchantCategory("online");
        record.setLocation("CA");
        record.setAmount(250.0);
        record.setUserId("1500");
        record.setRiskFactors("[]");
        return record;
    }
}
