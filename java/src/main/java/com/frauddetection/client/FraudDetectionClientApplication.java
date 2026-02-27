package com.frauddetection.client;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.retry.annotation.EnableRetry;

/**
 * Phase 3 — Java Spring Boot Client for the Fraud Detection API.
 *
 * <p>Demonstrates Java-Python integration: this app submits transactions
 * to the Python FastAPI service (Phase 2), applies retry with exponential
 * back-off, implements a circuit breaker, and persists every result locally.
 */
@SpringBootApplication
@EnableRetry
public class FraudDetectionClientApplication {

    public static void main(String[] args) {
        SpringApplication.run(FraudDetectionClientApplication.class, args);
    }
}
