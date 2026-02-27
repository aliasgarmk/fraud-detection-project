package com.frauddetection.client.dto;

import java.util.List;

/**
 * Inbound batch response DTO — mirrors the Python API's BatchResponse schema.
 */
public class BatchResponse {

    private List<FraudPredictionResponse> results;
    private int totalProcessed;
    private int fraudDetected;

    public List<FraudPredictionResponse> getResults() { return results; }
    public void setResults(List<FraudPredictionResponse> results) { this.results = results; }
    public int getTotalProcessed() { return totalProcessed; }
    public void setTotalProcessed(int totalProcessed) { this.totalProcessed = totalProcessed; }
    public int getFraudDetected() { return fraudDetected; }
    public void setFraudDetected(int fraudDetected) { this.fraudDetected = fraudDetected; }
}
