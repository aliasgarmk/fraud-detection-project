package com.frauddetection.client.client;

import com.frauddetection.client.config.FraudApiProperties;
import com.frauddetection.client.dto.BatchRequest;
import com.frauddetection.client.dto.BatchResponse;
import com.frauddetection.client.dto.FraudPredictionResponse;
import com.frauddetection.client.dto.TransactionRequest;
import com.frauddetection.client.exception.DuplicateTransactionException;
import com.frauddetection.client.exception.FraudApiUnavailableException;
import com.frauddetection.client.exception.InvalidTransactionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * Thin HTTP wrapper around the Python Fraud Detection API (Phase 2).
 *
 * <p>Retry policy (via spring-retry {@code @Retryable}):
 * <ul>
 *   <li>Retried on: network errors ({@link ResourceAccessException}) and
 *       5xx responses ({@link HttpServerErrorException}).</li>
 *   <li>NOT retried on 4xx — those are client errors (bad input, duplicates).</li>
 *   <li>Exponential back-off: 500 ms → 1000 ms → 2000 ms (configurable).</li>
 *   <li>After all attempts fail, {@link #recoverPredict} / {@link #recoverBatch}
 *       throw {@link FraudApiUnavailableException}.</li>
 * </ul>
 */
@Component
public class FraudDetectionApiClient {

    private static final Logger log = LoggerFactory.getLogger(FraudDetectionApiClient.class);

    private final RestTemplate restTemplate;
    private final FraudApiProperties props;

    public FraudDetectionApiClient(RestTemplate restTemplate, FraudApiProperties props) {
        this.restTemplate = restTemplate;
        this.props = props;
    }

    // ------------------------------------------------------------------ //
    // POST /predict                                                        //
    // ------------------------------------------------------------------ //

    @Retryable(
        retryFor = { ResourceAccessException.class, HttpServerErrorException.class },
        noRetryFor = { InvalidTransactionException.class, DuplicateTransactionException.class },
        maxAttemptsExpression = "#{@fraudApiProperties.retry.maxAttempts}",
        backoff = @Backoff(
            delayExpression      = "#{@fraudApiProperties.retry.initialIntervalMs}",
            multiplierExpression = "#{@fraudApiProperties.retry.multiplier}",
            maxDelayExpression   = "#{@fraudApiProperties.retry.maxIntervalMs}"
        )
    )
    public FraudPredictionResponse predict(TransactionRequest request) {
        String url = props.getBaseUrl() + "/predict";
        log.debug("POST {} — transaction_id={}", url, request.getTransactionId());
        try {
            return restTemplate.postForObject(url, request, FraudPredictionResponse.class);
        } catch (HttpClientErrorException ex) {
            handleClientError(ex);
            throw ex; // unreachable — handleClientError always throws
        }
    }

    @Recover
    public FraudPredictionResponse recoverPredict(Exception ex, TransactionRequest request) {
        log.error("predict() failed after all retries for transaction_id={}: {}",
                  request.getTransactionId(), ex.getMessage());
        throw new FraudApiUnavailableException(
                "Fraud API unavailable after retries for transaction " + request.getTransactionId(), ex);
    }

    // ------------------------------------------------------------------ //
    // POST /batch                                                          //
    // ------------------------------------------------------------------ //

    @Retryable(
        retryFor = { ResourceAccessException.class, HttpServerErrorException.class },
        noRetryFor = { InvalidTransactionException.class, DuplicateTransactionException.class },
        maxAttemptsExpression = "#{@fraudApiProperties.retry.maxAttempts}",
        backoff = @Backoff(
            delayExpression      = "#{@fraudApiProperties.retry.initialIntervalMs}",
            multiplierExpression = "#{@fraudApiProperties.retry.multiplier}",
            maxDelayExpression   = "#{@fraudApiProperties.retry.maxIntervalMs}"
        )
    )
    public BatchResponse batchPredict(BatchRequest request) {
        String url = props.getBaseUrl() + "/batch";
        log.debug("POST {} — {} transactions", url, request.getTransactions().size());
        try {
            return restTemplate.postForObject(url, request, BatchResponse.class);
        } catch (HttpClientErrorException ex) {
            handleClientError(ex);
            throw ex;
        }
    }

    @Recover
    public BatchResponse recoverBatch(Exception ex, BatchRequest request) {
        log.error("batchPredict() failed after all retries for {} transactions: {}",
                  request.getTransactions().size(), ex.getMessage());
        throw new FraudApiUnavailableException(
                "Fraud API unavailable after retries (batch of " + request.getTransactions().size() + ")", ex);
    }

    // ------------------------------------------------------------------ //
    // GET /health                                                          //
    // ------------------------------------------------------------------ //

    @SuppressWarnings("unchecked")
    public Map<String, Object> health() {
        String url = props.getBaseUrl() + "/health";
        return restTemplate.getForObject(url, Map.class);
    }

    // ------------------------------------------------------------------ //
    // GET /metrics                                                         //
    // ------------------------------------------------------------------ //

    @SuppressWarnings("unchecked")
    public Map<String, Object> metrics() {
        String url = props.getBaseUrl() + "/metrics";
        return restTemplate.getForObject(url, Map.class);
    }

    // ------------------------------------------------------------------ //
    // Private helpers                                                      //
    // ------------------------------------------------------------------ //

    /**
     * Translate HTTP 4xx errors from the Python API into domain exceptions.
     * These are not retried.
     */
    private void handleClientError(HttpClientErrorException ex) {
        if (ex.getStatusCode() == HttpStatus.UNPROCESSABLE_ENTITY) {  // 422
            throw new InvalidTransactionException(
                    "Transaction rejected by fraud API (validation error): " + ex.getResponseBodyAsString());
        }
        if (ex.getStatusCode() == HttpStatus.CONFLICT) {  // 409
            throw new DuplicateTransactionException(
                    "Transaction already processed: " + ex.getResponseBodyAsString());
        }
        // Other 4xx — rethrow as-is (not retried)
    }
}
