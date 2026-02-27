package com.frauddetection.client.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

import java.time.OffsetDateTime;

/**
 * Outbound request DTO — mirrors the Python API's TransactionRequest schema.
 *
 * <p>JSON field names use snake_case (handled by the global ObjectMapper in AppConfig).
 * The Python API expects: transaction_id, amount, merchant_category, location,
 * timestamp, user_id.
 */
public class TransactionRequest {

    @NotBlank(message = "transaction_id must not be blank")
    private String transactionId;

    @Positive(message = "amount must be greater than 0")
    private double amount;

    @NotBlank(message = "merchant_category must not be blank")
    private String merchantCategory;

    @NotBlank(message = "location must not be blank")
    private String location;

    private OffsetDateTime timestamp;

    @NotBlank(message = "user_id must not be blank")
    private String userId;

    // ------------------------------------------------------------------ //
    // Constructors                                                         //
    // ------------------------------------------------------------------ //

    public TransactionRequest() {}

    public TransactionRequest(String transactionId, double amount, String merchantCategory,
                               String location, OffsetDateTime timestamp, String userId) {
        this.transactionId = transactionId;
        this.amount = amount;
        this.merchantCategory = merchantCategory;
        this.location = location;
        this.timestamp = timestamp;
        this.userId = userId;
    }

    // ------------------------------------------------------------------ //
    // Accessors                                                            //
    // ------------------------------------------------------------------ //

    public String getTransactionId() { return transactionId; }
    public void setTransactionId(String transactionId) { this.transactionId = transactionId; }
    public double getAmount() { return amount; }
    public void setAmount(double amount) { this.amount = amount; }
    public String getMerchantCategory() { return merchantCategory; }
    public void setMerchantCategory(String merchantCategory) { this.merchantCategory = merchantCategory; }
    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }
    public OffsetDateTime getTimestamp() { return timestamp; }
    public void setTimestamp(OffsetDateTime timestamp) { this.timestamp = timestamp; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
}
