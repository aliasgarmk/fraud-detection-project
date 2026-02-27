package com.frauddetection.client.exception;

/**
 * Thrown when the Fraud Detection API rejects a request with HTTP 422
 * (Pydantic validation failure). This is a client error — do not retry.
 */
public class InvalidTransactionException extends RuntimeException {

    public InvalidTransactionException(String message) {
        super(message);
    }
}
