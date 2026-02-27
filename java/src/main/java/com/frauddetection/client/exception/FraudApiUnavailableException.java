package com.frauddetection.client.exception;

/**
 * Thrown when the Fraud Detection API is unreachable after all retry attempts
 * are exhausted, or when the circuit breaker is in the OPEN state.
 */
public class FraudApiUnavailableException extends RuntimeException {

    public FraudApiUnavailableException(String message) {
        super(message);
    }

    public FraudApiUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
