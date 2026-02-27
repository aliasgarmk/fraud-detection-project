package com.frauddetection.client.exception;

/**
 * Thrown when the Fraud Detection API returns HTTP 409 — the transaction ID
 * has already been processed. Do not retry.
 */
public class DuplicateTransactionException extends RuntimeException {

    public DuplicateTransactionException(String message) {
        super(message);
    }
}
