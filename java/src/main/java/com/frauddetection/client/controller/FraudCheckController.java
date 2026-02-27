package com.frauddetection.client.controller;

import com.frauddetection.client.dto.BatchResponse;
import com.frauddetection.client.dto.FraudPredictionResponse;
import com.frauddetection.client.dto.TransactionRequest;
import com.frauddetection.client.exception.CircuitOpenException;
import com.frauddetection.client.exception.DuplicateTransactionException;
import com.frauddetection.client.exception.FraudApiUnavailableException;
import com.frauddetection.client.exception.InvalidTransactionException;
import com.frauddetection.client.model.TransactionRecord;
import com.frauddetection.client.repository.TransactionRecordRepository;
import com.frauddetection.client.service.FraudDetectionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST controller exposing fraud-check endpoints on port 8080.
 *
 * <p>This is the Java Spring Boot equivalent of the Python FastAPI routers.
 * It delegates all business logic to {@link FraudDetectionService}.
 *
 * <h3>Endpoints</h3>
 * <pre>
 *   POST /fraud-check                            — single transaction
 *   POST /fraud-check/batch                      — batch of transactions
 *   GET  /fraud-check/history                    — all local records
 *   GET  /fraud-check/history/{transactionId}    — one local record
 *   GET  /fraud-check/circuit-status             — circuit breaker diagnostic
 * </pre>
 */
@RestController
@RequestMapping("/fraud-check")
public class FraudCheckController {

    private final FraudDetectionService service;
    private final TransactionRecordRepository repository;

    public FraudCheckController(FraudDetectionService service,
                                 TransactionRecordRepository repository) {
        this.service = service;
        this.repository = repository;
    }

    // ------------------------------------------------------------------ //
    // POST /fraud-check                                                    //
    // ------------------------------------------------------------------ //

    /**
     * Check a single transaction for fraud.
     *
     * <p>Returns 200 on success, 400 on validation failure, 409 on duplicate,
     * 503 if the circuit is open or the API is unreachable.
     */
    @PostMapping
    public ResponseEntity<?> checkSingle(@Valid @RequestBody TransactionRequest request) {
        try {
            FraudPredictionResponse result = service.checkTransaction(request);
            return ResponseEntity.ok(result);
        } catch (InvalidTransactionException ex) {
            return ResponseEntity.badRequest().body(Map.of("detail", ex.getMessage()));
        } catch (DuplicateTransactionException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("detail", ex.getMessage()));
        } catch (CircuitOpenException | FraudApiUnavailableException ex) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("detail", ex.getMessage()));
        }
    }

    // ------------------------------------------------------------------ //
    // POST /fraud-check/batch                                              //
    // ------------------------------------------------------------------ //

    /**
     * Check a batch of up to 100 transactions.
     */
    @PostMapping("/batch")
    public ResponseEntity<?> checkBatch(@Valid @RequestBody List<@Valid TransactionRequest> requests) {
        try {
            BatchResponse result = service.checkBatch(requests);
            return ResponseEntity.ok(result);
        } catch (InvalidTransactionException ex) {
            return ResponseEntity.badRequest().body(Map.of("detail", ex.getMessage()));
        } catch (DuplicateTransactionException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("detail", ex.getMessage()));
        } catch (CircuitOpenException | FraudApiUnavailableException ex) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("detail", ex.getMessage()));
        }
    }

    // ------------------------------------------------------------------ //
    // GET /fraud-check/history                                             //
    // ------------------------------------------------------------------ //

    /**
     * Return all locally stored fraud check results (from H2).
     */
    @GetMapping("/history")
    public List<TransactionRecord> history() {
        return repository.findAll();
    }

    // ------------------------------------------------------------------ //
    // GET /fraud-check/history/{transactionId}                            //
    // ------------------------------------------------------------------ //

    /**
     * Return one locally stored record by transaction ID.
     */
    @GetMapping("/history/{transactionId}")
    public ResponseEntity<?> historyById(@PathVariable String transactionId) {
        return repository.findByTransactionId(transactionId)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // ------------------------------------------------------------------ //
    // GET /fraud-check/circuit-status                                      //
    // ------------------------------------------------------------------ //

    /**
     * Returns the current circuit breaker state and failure count.
     * Useful for monitoring and debugging.
     */
    @GetMapping("/circuit-status")
    public Map<String, Object> circuitStatus() {
        return Map.of(
                "state", service.getCircuitState().name(),
                "failure_count", service.getFailureCount()
        );
    }
}
