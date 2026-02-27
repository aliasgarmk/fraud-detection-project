package com.frauddetection.client.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.frauddetection.client.client.FraudDetectionApiClient;
import com.frauddetection.client.config.FraudApiProperties;
import com.frauddetection.client.dto.BatchRequest;
import com.frauddetection.client.dto.BatchResponse;
import com.frauddetection.client.dto.FraudPredictionResponse;
import com.frauddetection.client.dto.TransactionRequest;
import com.frauddetection.client.exception.CircuitOpenException;
import com.frauddetection.client.model.TransactionRecord;
import com.frauddetection.client.repository.TransactionRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Business logic layer that orchestrates fraud checks.
 *
 * <h3>Circuit Breaker</h3>
 * <p>Implements a three-state circuit breaker (CLOSED → OPEN → HALF_OPEN → CLOSED)
 * in plain Java using atomic variables — no external library needed.
 *
 * <pre>
 *  CLOSED ──(failures >= threshold)──► OPEN
 *    ▲                                   │
 *    │                                   │ (cooldown elapsed)
 *    └──(probe succeeds)── HALF_OPEN ◄───┘
 * </pre>
 *
 * <ul>
 *   <li><b>CLOSED</b> — normal operation, calls go through.</li>
 *   <li><b>OPEN</b>   — fail-fast; throws {@link CircuitOpenException} immediately.</li>
 *   <li><b>HALF_OPEN</b> — one probe call allowed; success closes, failure re-opens.</li>
 * </ul>
 *
 * <h3>Persistence</h3>
 * <p>Every successful prediction is saved to the local H2 database so the client
 * maintains an independent audit trail.
 */
@Service
public class FraudDetectionService {

    private static final Logger log = LoggerFactory.getLogger(FraudDetectionService.class);

    // ------------------------------------------------------------------ //
    // Circuit breaker state                                                //
    // ------------------------------------------------------------------ //

    public enum CircuitState { CLOSED, OPEN, HALF_OPEN }

    private final AtomicReference<CircuitState> circuitState =
            new AtomicReference<>(CircuitState.CLOSED);
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private volatile Instant openedAt = null;

    // ------------------------------------------------------------------ //
    // Dependencies                                                         //
    // ------------------------------------------------------------------ //

    private final FraudDetectionApiClient apiClient;
    private final TransactionRecordRepository repository;
    private final FraudApiProperties props;
    private final ObjectMapper objectMapper;

    public FraudDetectionService(FraudDetectionApiClient apiClient,
                                  TransactionRecordRepository repository,
                                  FraudApiProperties props,
                                  ObjectMapper objectMapper) {
        this.apiClient = apiClient;
        this.repository = repository;
        this.props = props;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------------ //
    // Public API                                                           //
    // ------------------------------------------------------------------ //

    /**
     * Check a single transaction for fraud.
     *
     * @throws CircuitOpenException if the circuit is open and cooldown has not elapsed.
     */
    public FraudPredictionResponse checkTransaction(TransactionRequest request) {
        guardCircuit();
        try {
            FraudPredictionResponse response = apiClient.predict(request);
            onSuccess();
            persist(request, response);
            return response;
        } catch (Exception ex) {
            onFailure(ex);
            throw ex;
        }
    }

    /**
     * Check a batch of transactions for fraud.
     *
     * @throws CircuitOpenException if the circuit is open and cooldown has not elapsed.
     */
    public BatchResponse checkBatch(List<TransactionRequest> requests) {
        guardCircuit();
        try {
            BatchRequest batchRequest = new BatchRequest(requests);
            BatchResponse response = apiClient.batchPredict(batchRequest);
            onSuccess();
            if (response.getResults() != null) {
                for (int i = 0; i < response.getResults().size(); i++) {
                    persist(requests.get(i), response.getResults().get(i));
                }
            }
            return response;
        } catch (Exception ex) {
            onFailure(ex);
            throw ex;
        }
    }

    /**
     * Returns the current circuit breaker state (for health/diagnostic endpoints).
     */
    public CircuitState getCircuitState() {
        return circuitState.get();
    }

    /**
     * Returns the current failure count (for monitoring).
     */
    public int getFailureCount() {
        return failureCount.get();
    }

    // ------------------------------------------------------------------ //
    // Circuit breaker logic                                                //
    // ------------------------------------------------------------------ //

    /**
     * Throws {@link CircuitOpenException} if the circuit is OPEN and the cooldown
     * period has not yet elapsed. Transitions to HALF_OPEN when cooldown expires.
     */
    private void guardCircuit() {
        CircuitState state = circuitState.get();
        if (state == CircuitState.OPEN) {
            long cooldownMs = props.getCircuitBreaker().getCooldownSeconds() * 1000L;
            if (openedAt != null && Instant.now().isAfter(openedAt.plusMillis(cooldownMs))) {
                log.info("Circuit cooldown elapsed — transitioning to HALF_OPEN");
                circuitState.compareAndSet(CircuitState.OPEN, CircuitState.HALF_OPEN);
            } else {
                throw new CircuitOpenException(
                        "Circuit breaker is OPEN — fraud API calls suspended until cooldown expires");
            }
        }
    }

    private void onSuccess() {
        CircuitState prev = circuitState.getAndSet(CircuitState.CLOSED);
        if (prev != CircuitState.CLOSED) {
            log.info("Circuit breaker CLOSED after successful probe");
        }
        failureCount.set(0);
    }

    private void onFailure(Exception ex) {
        int failures = failureCount.incrementAndGet();
        log.warn("Fraud API call failed (failures={}): {}", failures, ex.getMessage());

        int threshold = props.getCircuitBreaker().getFailureThreshold();
        if (failures >= threshold && circuitState.get() == CircuitState.CLOSED) {
            circuitState.set(CircuitState.OPEN);
            openedAt = Instant.now();
            log.error("Circuit breaker OPENED after {} consecutive failures", failures);
        }
    }

    // ------------------------------------------------------------------ //
    // Persistence helper                                                   //
    // ------------------------------------------------------------------ //

    private void persist(TransactionRequest request, FraudPredictionResponse response) {
        String riskFactorsJson;
        try {
            riskFactorsJson = objectMapper.writeValueAsString(
                    response.getRiskFactors() != null ? response.getRiskFactors() : Collections.emptyList());
        } catch (JsonProcessingException e) {
            riskFactorsJson = "[]";
        }

        TransactionRecord record = new TransactionRecord();
        record.setTransactionId(request.getTransactionId());
        record.setAmount(request.getAmount());
        record.setMerchantCategory(request.getMerchantCategory());
        record.setLocation(request.getLocation());
        record.setUserId(request.getUserId());
        record.setFraudScore(response.getFraudScore());
        record.setFraudulent(response.isFraudulent());
        record.setConfidence(response.getConfidence());
        record.setRiskFactors(riskFactorsJson);

        try {
            repository.save(record);
            log.debug("Persisted transaction record: {}", request.getTransactionId());
        } catch (Exception ex) {
            // Persistence failure must not affect the API response
            log.error("Failed to persist transaction record {}: {}", request.getTransactionId(), ex.getMessage());
        }
    }
}
