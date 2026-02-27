package com.frauddetection.client.exception;

/**
 * Thrown by {@link com.frauddetection.client.service.FraudDetectionService}
 * when the circuit breaker is in the OPEN state and the cooldown period has
 * not yet elapsed. Callers should surface this as HTTP 503.
 */
public class CircuitOpenException extends RuntimeException {

    public CircuitOpenException(String message) {
        super(message);
    }
}
