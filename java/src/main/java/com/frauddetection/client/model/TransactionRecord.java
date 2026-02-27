package com.frauddetection.client.model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * JPA entity — local audit log of every fraud check performed by this client.
 * Stored in the embedded H2 database (no PostgreSQL required for Phase 3).
 *
 * <p>This is the Java equivalent of Python's SQLAlchemy Transaction ORM model,
 * but scoped to the client's local history rather than the central fraud DB.
 */
@Entity
@Table(name = "transaction_records",
       indexes = @Index(name = "idx_transaction_id", columnList = "transaction_id"))
public class TransactionRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transaction_id", nullable = false, unique = true, length = 100)
    private String transactionId;

    @Column(nullable = false)
    private double amount;

    @Column(name = "merchant_category", nullable = false, length = 50)
    private String merchantCategory;

    @Column(nullable = false, length = 10)
    private String location;

    @Column(name = "user_id", nullable = false, length = 100)
    private String userId;

    @Column(name = "fraud_score", nullable = false)
    private double fraudScore;

    @Column(name = "is_fraudulent", nullable = false)
    private boolean isFraudulent;

    @Column(nullable = false, length = 10)
    private String confidence;

    @Column(name = "risk_factors", columnDefinition = "TEXT")
    private String riskFactors;   // JSON array serialised as string

    @CreationTimestamp
    @Column(name = "checked_at", nullable = false, updatable = false)
    private Instant checkedAt;

    // ------------------------------------------------------------------ //
    // Accessors                                                            //
    // ------------------------------------------------------------------ //

    public Long getId() { return id; }
    public String getTransactionId() { return transactionId; }
    public void setTransactionId(String transactionId) { this.transactionId = transactionId; }
    public double getAmount() { return amount; }
    public void setAmount(double amount) { this.amount = amount; }
    public String getMerchantCategory() { return merchantCategory; }
    public void setMerchantCategory(String merchantCategory) { this.merchantCategory = merchantCategory; }
    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public double getFraudScore() { return fraudScore; }
    public void setFraudScore(double fraudScore) { this.fraudScore = fraudScore; }
    public boolean isFraudulent() { return isFraudulent; }
    public void setFraudulent(boolean fraudulent) { isFraudulent = fraudulent; }
    public String getConfidence() { return confidence; }
    public void setConfidence(String confidence) { this.confidence = confidence; }
    public String getRiskFactors() { return riskFactors; }
    public void setRiskFactors(String riskFactors) { this.riskFactors = riskFactors; }
    public Instant getCheckedAt() { return checkedAt; }
}
