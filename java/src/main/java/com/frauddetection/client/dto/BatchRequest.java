package com.frauddetection.client.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Outbound batch request DTO — mirrors the Python API's BatchRequest schema.
 * Wraps up to 100 TransactionRequests in a single call.
 */
public class BatchRequest {

    @NotEmpty(message = "transactions list must not be empty")
    @Size(max = 100, message = "Maximum 100 transactions per batch")
    @Valid
    private List<TransactionRequest> transactions;

    public BatchRequest() {}

    public BatchRequest(List<TransactionRequest> transactions) {
        this.transactions = transactions;
    }

    public List<TransactionRequest> getTransactions() { return transactions; }
    public void setTransactions(List<TransactionRequest> transactions) { this.transactions = transactions; }
}
