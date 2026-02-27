package com.frauddetection.client.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Typed configuration bound from application.yml under the {@code fraud-api} prefix.
 *
 * <p>Java equivalent of Python's pydantic-settings: all values can be overridden
 * by environment variables (e.g. FRAUD_API_BASE_URL) or a .env file.
 */
@Component
@ConfigurationProperties(prefix = "fraud-api")
public class FraudApiProperties {

    private String baseUrl = "http://localhost:8000";
    private int connectTimeoutMs = 3000;
    private int readTimeoutMs = 5000;
    private Retry retry = new Retry();
    private CircuitBreaker circuitBreaker = new CircuitBreaker();

    // ------------------------------------------------------------------ //
    // Nested: Retry                                                        //
    // ------------------------------------------------------------------ //

    public static class Retry {
        private int maxAttempts = 3;
        private long initialIntervalMs = 500;
        private double multiplier = 2.0;
        private long maxIntervalMs = 5000;

        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
        public long getInitialIntervalMs() { return initialIntervalMs; }
        public void setInitialIntervalMs(long initialIntervalMs) { this.initialIntervalMs = initialIntervalMs; }
        public double getMultiplier() { return multiplier; }
        public void setMultiplier(double multiplier) { this.multiplier = multiplier; }
        public long getMaxIntervalMs() { return maxIntervalMs; }
        public void setMaxIntervalMs(long maxIntervalMs) { this.maxIntervalMs = maxIntervalMs; }
    }

    // ------------------------------------------------------------------ //
    // Nested: CircuitBreaker                                               //
    // ------------------------------------------------------------------ //

    public static class CircuitBreaker {
        private int failureThreshold = 5;
        private int cooldownSeconds = 30;

        public int getFailureThreshold() { return failureThreshold; }
        public void setFailureThreshold(int failureThreshold) { this.failureThreshold = failureThreshold; }
        public int getCooldownSeconds() { return cooldownSeconds; }
        public void setCooldownSeconds(int cooldownSeconds) { this.cooldownSeconds = cooldownSeconds; }
    }

    // ------------------------------------------------------------------ //
    // Accessors                                                            //
    // ------------------------------------------------------------------ //

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public int getConnectTimeoutMs() { return connectTimeoutMs; }
    public void setConnectTimeoutMs(int connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }
    public int getReadTimeoutMs() { return readTimeoutMs; }
    public void setReadTimeoutMs(int readTimeoutMs) { this.readTimeoutMs = readTimeoutMs; }
    public Retry getRetry() { return retry; }
    public void setRetry(Retry retry) { this.retry = retry; }
    public CircuitBreaker getCircuitBreaker() { return circuitBreaker; }
    public void setCircuitBreaker(CircuitBreaker circuitBreaker) { this.circuitBreaker = circuitBreaker; }
}
