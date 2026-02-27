# Phase 3 Low-Level Design: Java Spring Boot Client

**Version:** 1.1
**Phase:** 3 of 4
**Author:** Aliasgar Kantawala
**Status:** Complete

---

## 1. Overview

Phase 3 builds a Java 21 / Spring Boot 3.x client application that consumes the Phase 2 Fraud Detection API. It demonstrates Java–Python integration, production-grade HTTP client patterns (retry with exponential back-off, circuit breaker), and local transaction history persistence via Spring Data JPA + H2 (embedded, for portability without requiring a second PostgreSQL instance). In the Docker environment (Phase 4) the H2 datasource is swapped for PostgreSQL via a Spring profile.

---

## 2. Folder Structure

```
java/
├── pom.xml                                          # Maven build descriptor
└── src/
    ├── main/
    │   ├── java/com/frauddetection/client/
    │   │   ├── FraudDetectionClientApplication.java # Spring Boot entry point
    │   │   ├── config/
    │   │   │   ├── AppConfig.java                   # RestTemplate + retry beans
    │   │   │   └── FraudApiProperties.java          # @ConfigurationProperties
    │   │   ├── dto/
    │   │   │   ├── TransactionRequest.java          # Outbound request to Python API
    │   │   │   ├── FraudPredictionResponse.java     # Inbound response from Python API
    │   │   │   ├── BatchRequest.java                # Batch payload wrapper
    │   │   │   └── BatchResponse.java               # Batch result wrapper
    │   │   ├── client/
    │   │   │   └── FraudDetectionApiClient.java     # HTTP client (RestTemplate + retry)
    │   │   ├── service/
    │   │   │   └── FraudDetectionService.java       # Business logic, circuit breaker
    │   │   ├── model/
    │   │   │   └── TransactionRecord.java           # JPA entity — local history
    │   │   ├── repository/
    │   │   │   └── TransactionRecordRepository.java # Spring Data JPA repository
    │   │   └── controller/
    │   │       └── FraudCheckController.java        # REST endpoints exposed by this app
    │   └── resources/
    │       ├── application.yml                      # App configuration (H2 / local)
    │       └── application-docker.yml               # Docker profile (PostgreSQL)
    └── test/
        └── java/com/frauddetection/client/
            ├── client/
            │   └── FraudDetectionApiClientTest.java
            ├── service/
            │   └── FraudDetectionServiceTest.java
            └── controller/
                └── FraudCheckControllerTest.java
```

---

## 3. Technology Stack

| Dependency | Version | Purpose |
|---|---|---|
| Java | 21 | Language (LTS, available locally) |
| Spring Boot | 3.2.x | Framework, auto-configuration |
| `spring-boot-starter-web` | 3.2.x | RestTemplate, embedded Tomcat |
| `spring-boot-starter-data-jpa` | 3.2.x | JPA / Hibernate ORM |
| `spring-boot-starter-actuator` | 3.2.x | `/actuator/health` endpoint |
| `spring-retry` | 2.0.x | `@Retryable` / exponential back-off |
| `spring-aspects` | 6.x | AOP required by spring-retry |
| H2 Database | 2.x | Embedded DB for local transaction history |
| PostgreSQL JDBC | (via BOM) | Used when `docker` profile is active |
| `jackson-databind` | (via Boot) | JSON serialisation |
| JUnit 5 | (via Boot) | Unit testing |
| Mockito | (via Boot) | Mocking in tests |

---

## 4. Configuration (`application.yml`)

```yaml
fraud-api:
  base-url: http://localhost:8000
  connect-timeout-ms: 3000
  read-timeout-ms: 5000
  retry:
    max-attempts: 3
    initial-interval-ms: 500
    multiplier: 2.0
    max-interval-ms: 5000

spring:
  datasource:
    url: jdbc:h2:mem:fraudclientdb;DB_CLOSE_DELAY=-1
    driver-class-name: org.h2.Driver
  jpa:
    hibernate:
      ddl-auto: create-drop
    show-sql: false
  h2:
    console:
      enabled: true            # Browse at http://localhost:8080/h2-console

server:
  port: 8080
```

All `fraud-api.*` values are bound via `@ConfigurationProperties(prefix = "fraud-api")`.

### Docker Profile (`application-docker.yml`)

Activated by `SPRING_PROFILES_ACTIVE=docker` (set in docker-compose.yml):

```yaml
fraud-api:
  base-url: ${FRAUD_API_BASE_URL:http://fraud-api:8000}
spring:
  datasource:
    url: ${SPRING_DATASOURCE_URL:jdbc:postgresql://postgres:5432/fraud_client_db}
    driver-class-name: org.postgresql.Driver
    username: ${SPRING_DATASOURCE_USERNAME:fraud_user}
    password: ${SPRING_DATASOURCE_PASSWORD:changeme}
  jpa:
    hibernate:
      ddl-auto: update
    properties:
      hibernate:
        dialect: org.hibernate.dialect.PostgreSQLDialect
  h2:
    console:
      enabled: false
```

---

## 5. DTOs

### 5.1 `TransactionRequest`
Mirrors the Python API's `POST /predict` body:

| Field | Java Type | Constraints |
|---|---|---|
| `transactionId` | `String` | `@NotBlank` |
| `amount` | `double` | `@Positive` |
| `merchantCategory` | `String` | `@NotBlank` |
| `location` | `String` | `@NotBlank` |
| `timestamp` | `OffsetDateTime` | serialised as ISO-8601 |
| `userId` | `String` | `@NotBlank` |

JSON uses `snake_case` via `@JsonProperty` on each field (global `SNAKE_CASE` strategy set in `application.yml`).

### 5.2 `FraudPredictionResponse`

| Field | Java Type | Notes |
|---|---|---|
| `transactionId` | `String` | `@JsonProperty("transaction_id")` |
| `fraudScore` | `double` | `@JsonProperty("fraud_score")` |
| `isFraudulent` | `boolean` | `@JsonProperty("is_fraudulent")` — explicit annotation required; Jackson would otherwise strip the `is` prefix |
| `confidence` | `String` | |
| `riskFactors` | `List<String>` | `@JsonProperty("risk_factors")` |
| `processingTimeMs` | `double` | `@JsonProperty("processing_time_ms")` |

### 5.3 `BatchRequest` / `BatchResponse`
`BatchRequest` wraps `List<TransactionRequest>`.
`BatchResponse` wraps `List<FraudPredictionResponse>` plus `totalProcessed` and `fraudDetected`.

---

## 6. HTTP Client (`FraudDetectionApiClient`)

Thin wrapper around `RestTemplate`. All network calls go through this class.

### 6.1 Methods

```java
FraudPredictionResponse predict(TransactionRequest request);
BatchResponse           batchPredict(BatchRequest request);
Map<String,Object>      health();
Map<String,Object>      metrics();
```

### 6.2 Retry Behaviour

`@Retryable` from `spring-retry` is applied to `predict()` and `batchPredict()`:

| Parameter | Value |
|---|---|
| Retried exceptions | `ResourceAccessException`, `HttpServerErrorException` |
| Max attempts | 3 (configurable) |
| Back-off | Exponential — 500 ms → 1000 ms → 2000 ms |

A final `@Recover` method logs the failure and throws a `FraudApiUnavailableException`.

### 6.3 Error Handling

| HTTP Status | Action |
|---|---|
| 422 | Throw `InvalidTransactionException` (bad request — do not retry) |
| 409 | Throw `DuplicateTransactionException` |
| 503 | Retry, then throw `FraudApiUnavailableException` |
| 5xx | Retry |
| Network timeout | Retry |

---

## 7. Service (`FraudDetectionService`)

Orchestrates the API call, persists the result, and implements a simple in-process **circuit breaker** (open / half-open / closed).

### 7.1 Circuit Breaker State Machine

```
CLOSED ──(failure threshold reached)──► OPEN
  ▲                                        │
  │                                        │ (cooldown elapsed)
  └──(probe succeeds)── HALF_OPEN ◄────────┘
```

| State | Behaviour |
|---|---|
| `CLOSED` | Normal — calls pass through |
| `OPEN` | Fail fast — throw `CircuitOpenException` without calling API |
| `HALF_OPEN` | Allow one probe call; success → CLOSED, failure → OPEN |

Configuration constants (in `application.yml` under `fraud-api.circuit-breaker`):

| Key | Default | Meaning |
|---|---|---|
| `failure-threshold` | 5 | Failures in window before opening |
| `cooldown-seconds` | 30 | Seconds to wait before half-open |

### 7.2 `checkTransaction(TransactionRequest)` Flow

```
1. Check circuit state — if OPEN and cooldown not elapsed → throw CircuitOpenException
2. Call FraudDetectionApiClient.predict(request)
3. On success:
     a. Reset failure counter (circuit closes if half-open)
     b. Persist TransactionRecord to local DB
     c. Return FraudPredictionResponse
4. On exception:
     a. Increment failure counter
     b. Open circuit if threshold exceeded
     c. Re-throw
```

### 7.3 `checkBatch(List<TransactionRequest>)` Flow

Wraps list into `BatchRequest`, calls `batchPredict()`, persists each result, returns `BatchResponse`.

---

## 8. Local Persistence (`TransactionRecord`)

JPA entity stored in H2 (local) or PostgreSQL (docker profile) to keep an audit log of every fraud check performed by this client.

| Column | Java Type | Notes |
|---|---|---|
| `id` | `Long` | `@GeneratedValue(IDENTITY)` |
| `transactionId` | `String` | Unique, indexed |
| `amount` | `double` | |
| `merchantCategory` | `String` | |
| `location` | `String` | |
| `userId` | `String` | |
| `fraudScore` | `double` | From API response |
| `isFraudulent` | `boolean` | |
| `confidence` | `String` | |
| `riskFactors` | `String` | JSON array serialised as string |
| `checkedAt` | `Instant` | `@CreationTimestamp` |

---

## 9. REST Controller (`FraudCheckController`)

This application itself exposes a small REST API (port 8080) so it can be tested with curl / Postman.

| Method | Path | Description |
|---|---|---|
| `POST` | `/fraud-check` | Check a single transaction |
| `POST` | `/fraud-check/batch` | Check a batch of transactions |
| `GET` | `/fraud-check/history` | List all locally stored checks |
| `GET` | `/fraud-check/history/{transactionId}` | Fetch one record by ID |

All responses use standard HTTP status codes:
- 200 — success
- 400 — validation failure
- 409 — duplicate transaction
- 503 — fraud API unavailable (circuit open or all retries exhausted)

---

## 10. Unit Tests (20 passing)

### 10.1 `FraudDetectionApiClientTest`
- Mocks `RestTemplate` with `MockRestServiceServer`
- Verifies correct URL construction, request headers, JSON serialisation
- Tests retry: first two calls return 503, third succeeds — verifies 3 HTTP calls made
- Tests `@Recover`: three 503s — verifies `FraudApiUnavailableException` thrown

### 10.2 `FraudDetectionServiceTest`
- Mocks `FraudDetectionApiClient` and `TransactionRecordRepository`
- Tests happy path: response persisted, counter reset
- Tests circuit opening: 5 consecutive failures → `OPEN` state
- Tests cooldown + half-open probe

### 10.3 `FraudCheckControllerTest`
- Uses `@WebMvcTest` with mocked `FraudDetectionService`
- Tests 200 response for valid input; JSON path assertions use `snake_case` keys (e.g. `$.transaction_id`, `$.is_fraudulent`)
- Tests 400 for missing required fields
- Tests 503 when service throws `CircuitOpenException`

---

## 11. Running the Client

```bash
# From java/ directory (requires Java 21 and Maven):
./mvnw spring-boot:run

# Or build jar:
./mvnw clean package
java -jar target/fraud-detection-client-1.0.0.jar

# Run all tests:
mvn test
# Expected: Tests run: 20, Failures: 0, Errors: 0, Skipped: 0

# Test a transaction:
curl -X POST http://localhost:8080/fraud-check \
  -H "Content-Type: application/json" \
  -d '{
    "transaction_id": "TXN-001",
    "amount": 850.00,
    "merchant_category": "online",
    "location": "NY",
    "timestamp": "2024-03-01T03:00:00Z",
    "user_id": "1500"
  }'
```

---

## 12. Phase 3 Success Criteria

| Criterion | Target |
|---|---|
| `POST /fraud-check` calls the Python API and returns the result | Pass |
| Failed calls are retried up to 3 times with exponential back-off | Pass |
| Circuit breaker opens after 5 consecutive failures | Pass |
| Every check is persisted to the local DB | Pass |
| Unit tests cover client, service, and controller layers | Pass |
| `./mvnw clean test` passes with no failures (20/20) | Pass |
| Docker profile switches datasource to PostgreSQL | Pass |
