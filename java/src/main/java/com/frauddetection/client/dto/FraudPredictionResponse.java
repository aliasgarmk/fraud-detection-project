package com.frauddetection.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Inbound response DTO — mirrors the Python API's FraudPredictionResponse schema.
 *
 * <p>JSON field names use snake_case (handled by the global ObjectMapper in AppConfig).
 */
public class FraudPredictionResponse {

    private String transactionId;
    private double fraudScore;
    @JsonProperty("is_fraudulent")
    private boolean isFraudulent;
    private String confidence;
    private List<String> riskFactors;
    private double processingTimeMs;

    // ------------------------------------------------------------------ //
    // Accessors                                                            //
    // ------------------------------------------------------------------ //

    public String getTransactionId() { return transactionId; }
    public void setTransactionId(String transactionId) { this.transactionId = transactionId; }
    public double getFraudScore() { return fraudScore; }
    public void setFraudScore(double fraudScore) { this.fraudScore = fraudScore; }
    public boolean isFraudulent() { return isFraudulent; }
    public void setFraudulent(boolean fraudulent) { isFraudulent = fraudulent; }
    public String getConfidence() { return confidence; }
    public void setConfidence(String confidence) { this.confidence = confidence; }
    public List<String> getRiskFactors() { return riskFactors; }
    public void setRiskFactors(List<String> riskFactors) { this.riskFactors = riskFactors; }
    public double getProcessingTimeMs() { return processingTimeMs; }
    public void setProcessingTimeMs(double processingTimeMs) { this.processingTimeMs = processingTimeMs; }

    @Override
    public String toString() {
        return "FraudPredictionResponse{" +
                "transactionId='" + transactionId + '\'' +
                ", fraudScore=" + fraudScore +
                ", isFraudulent=" + isFraudulent +
                ", confidence='" + confidence + '\'' +
                ", riskFactors=" + riskFactors +
                '}';
    }
}
