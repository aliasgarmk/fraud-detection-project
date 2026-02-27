# Fraud Detection System

An end-to-end ML fraud-detection pipeline built across four phases:
a scikit-learn model, a FastAPI inference service, a Spring Boot Java client,
and full Docker orchestration with a real-time Streamlit dashboard.

---

## Architecture

```
┌────────────────────────────────────────────────────────────────────────┐
│                          docker-compose network                         │
│                                                                        │
│  ┌─────────────┐                                                       │
│  │  PostgreSQL │  fraud_db (API)  +  fraud_client_db (Java client)    │
│  │  :5432      │                                                       │
│  └──────┬──────┘                                                       │
│         │                                                              │
│  ┌──────▼──────┐   HTTP/REST   ┌───────────────┐                      │
│  │  fraud-api  │◄──────────────│ fraud-client  │                      │
│  │  :8000      │   (predict)   │  :8080        │                      │
│  │  FastAPI    │               │  Spring Boot  │                      │
│  └──────┬──────┘               └───────────────┘                      │
│         │                                                              │
│  ┌──────▼──────┐                                                       │
│  │  dashboard  │  monitoring + Check Transaction UI                   │
│  │  :8501      │  reads fraud_db · calls POST /predict                 │
│  │  Streamlit  │                                                       │
│  └─────────────┘                                                       │
└────────────────────────────────────────────────────────────────────────┘
```

---

## Quick Start (Docker)

**Prerequisites:** Docker Desktop >= 24, Docker Compose v2

```bash
# 1. Clone the repository
git clone <repo-url>
cd fraud-detection-project

# 2. Make sure the trained model exists (skip if python/models/ is already committed)
venv\Scripts\python.exe python/ml_training/generate_data.py
venv\Scripts\python.exe python/ml_training/train_model.py

# 3. Start all services
docker-compose up --build

# 4. Open in browser:
#   API docs          -> http://localhost:8000/docs
#   Java UI           -> http://localhost:8080/actuator/health
#   Dashboard         -> http://localhost:8501          (monitoring)
#   Check Transaction -> http://localhost:8501 (sidebar: Check Transaction)
```

Stop everything:
```bash
docker-compose down          # keep data
docker-compose down -v       # also remove postgres volume
```

---

## Project Phases

| Phase | Description | Stack |
|---|---|---|
| 1 | ML pipeline — data generation, training, evaluation | Python, scikit-learn, pandas |
| 2 | REST inference API | FastAPI, PostgreSQL, SQLAlchemy |
| 3 | Java service client | Spring Boot 3, Spring Retry, H2 / PostgreSQL |
| 4 | Docker orchestration + monitoring dashboard + interactive Check Transaction UI | Docker Compose, Streamlit, Plotly |

---

## API Reference (Phase 2 — `fraud-api`)

| Method | Path | Description |
|---|---|---|
| `POST` | `/predict` | Score a single transaction |
| `POST` | `/batch` | Score up to 100 transactions |
| `GET` | `/health` | Liveness + model status |
| `GET` | `/metrics` | Aggregate stats (total, fraud count, avg score) |

### Example — single prediction

```bash
curl -X POST http://localhost:8000/predict \
  -H "Content-Type: application/json" \
  -d '{
    "transaction_id": "TXN-001",
    "amount": 1500.00,
    "merchant_category": "online",
    "location": "CA",
    "timestamp": "2024-06-01T14:30:00Z",
    "user_id": "user_42"
  }'
```

Response:
```json
{
  "transaction_id": "TXN-001",
  "fraud_score": 0.83,
  "is_fraudulent": true,
  "confidence": "high",
  "risk_factors": ["high_amount", "high_velocity"],
  "processing_time_ms": 12.4
}
```

---

## Java Client API Reference (Phase 3 — `fraud-client`)

| Method | Path | Description |
|---|---|---|
| `POST` | `/fraud-check` | Forward a transaction to the Python API and persist the result |
| `POST` | `/fraud-check/batch` | Forward a batch |
| `GET` | `/fraud-check/history` | All checked transactions (from local DB) |
| `GET` | `/fraud-check/history/{transactionId}` | Single record by ID |
| `GET` | `/fraud-check/circuit-status` | Current circuit-breaker state |
| `GET` | `/actuator/health` | Spring Boot health |

The Java client implements:
- **Exponential-backoff retry** (3 attempts, 500 ms -> 1 s -> 2 s) via `@Retryable`
- **Three-state circuit breaker** (CLOSED -> OPEN after 5 failures -> HALF_OPEN after 30 s cooldown)

---

## Environment Variables

### Python API (`.env` or docker-compose environment)

| Variable | Default | Description |
|---|---|---|
| `DATABASE_URL` | — | PostgreSQL connection string |
| `MODEL_PATH` | `models/fraud_model.pkl` | Path to trained model |
| `FRAUD_THRESHOLD` | `0.5` | Score above which a tx is flagged |
| `APP_HOST` | `0.0.0.0` | Uvicorn bind host |
| `APP_PORT` | `8000` | Uvicorn bind port |
| `LOG_LEVEL` | `info` | Uvicorn log level |

### Java Client (docker-compose environment)

| Variable | Default | Description |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `default` (H2) | Set to `docker` to use PostgreSQL |
| `FRAUD_API_BASE_URL` | `http://localhost:8000` | Python API URL |
| `SPRING_DATASOURCE_URL` | H2 in-memory | JDBC URL (overridden in docker profile) |
| `SPRING_DATASOURCE_USERNAME` | `sa` | DB username |
| `SPRING_DATASOURCE_PASSWORD` | _(empty)_ | DB password |

---

## Local Development (without Docker)

### Prerequisites

- Python 3.11+, Java 21, Maven 3.9+
- PostgreSQL 14+ running locally

### Python API

```bash
# Create and activate venv
python -m venv venv
venv\Scripts\activate        # Windows
# source venv/bin/activate   # Linux/macOS

pip install -r python/requirements.txt

# Configure environment
cp .env.example .env         # edit DATABASE_URL, etc.

# Generate data and train model (first time only)
venv\Scripts\python.exe python/ml_training/generate_data.py
venv\Scripts\python.exe python/ml_training/train_model.py

# Start the API (run from python/ so 'api' is a top-level package)
cd python && uvicorn api.main:app --reload
# -> http://localhost:8000/docs
```

### Java Client

```bash
cd java
mvn spring-boot:run
# -> http://localhost:8080
```

### Dashboard

```bash
cd python/dashboard
pip install -r requirements.txt
streamlit run app.py
# -> http://localhost:8501  (monitoring page)
# -> http://localhost:8501  sidebar: Check Transaction (interactive fraud-check form)
```

The **Check Transaction** page (`pages/2_Check_Transaction.py`) requires the `fraud-api`
to be running (`http://localhost:8000` by default, or set `FRAUD_API_URL`).

---

## Running Tests

### Java (Phase 3)

```bash
cd java
mvn test
# Expected: Tests run: 20, Failures: 0, Errors: 0, Skipped: 0
```

---

## Model Details

- **Algorithm:** Random Forest (100 estimators)
- **Features:** `amount`, `merchant_category`, `location`, `hour_of_day`, `user_id`,
  `tx_velocity`, `amount_percentile`, `hour_sin`, `hour_cos`
- **Target accuracy:** > 95% on held-out test set
- **Serialisation:** `joblib` -> `python/models/fraud_model.pkl`

---

## Repository Structure

```
fraud-detection-project/
├── python/                     # All Python source code
│   ├── api/                    # Phase 2 - FastAPI service
│   │   ├── Dockerfile
│   │   ├── main.py
│   │   ├── config.py
│   │   ├── schemas.py
│   │   ├── database.py
│   │   ├── db_models.py
│   │   ├── model_service.py
│   │   └── routers/
│   │       ├── predict.py
│   │       ├── batch.py
│   │       ├── health.py
│   │       └── metrics.py
│   ├── dashboard/              # Phase 4 - Streamlit UI
│   │   ├── app.py              # monitoring dashboard (main page)
│   │   ├── pages/
│   │   │   └── 2_Check_Transaction.py  # interactive fraud-check form
│   │   ├── requirements.txt
│   │   └── Dockerfile
│   ├── ml_training/            # Phase 1 - ML pipeline scripts
│   │   ├── generate_data.py
│   │   ├── train_model.py
│   │   └── evaluate_model.py
│   ├── models/                 # Trained model artefact
│   │   └── fraud_model.pkl
│   ├── data/                   # Generated CSV (git-ignored)
│   │   └── transactions.csv
│   └── requirements.txt        # Python dependencies
├── java/                       # Phase 3 - Spring Boot client
│   ├── Dockerfile
│   ├── pom.xml
│   └── src/
│       ├── main/java/com/frauddetection/client/
│       │   ├── client/         FraudDetectionApiClient.java
│       │   ├── config/         AppConfig.java, FraudApiProperties.java
│       │   ├── controller/     FraudCheckController.java
│       │   ├── dto/            TransactionRequest.java, FraudPredictionResponse.java, ...
│       │   ├── exception/      CircuitOpenException.java, ...
│       │   ├── model/          TransactionRecord.java
│       │   ├── repository/     TransactionRecordRepository.java
│       │   └── service/        FraudDetectionService.java
│       └── test/               20 JUnit 5 tests
├── docker/
│   └── postgres/
│       └── init.sql            # creates fraud_client_db on first boot
├── docker-compose.yml
├── .dockerignore
├── .env.example
├── README.md
└── docs/                       # All project documentation
    ├── Fraud_Detection_HLD.docx
    ├── Phase1_Implementation_Guide.docx
    ├── Phase1_LLD.md           # ML pipeline design
    ├── Phase2_LLD.md           # FastAPI service design
    ├── Phase3_LLD.md           # Java Spring Boot client design
    └── Phase4_LLD.md           # Docker orchestration + dashboard design
```
