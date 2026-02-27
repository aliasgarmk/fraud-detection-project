# Phase 2 Low-Level Design: FastAPI Fraud Detection Service

**Version:** 1.1
**Phase:** 2 of 4
**Author:** Aliasgar Kantawala
**Status:** Complete

---

## 1. Overview

Phase 2 builds a REST API that loads the trained Random Forest model (`.pkl`) from Phase 1 and exposes it for real-time fraud scoring. The service is stateless at the prediction layer; every prediction is persisted to PostgreSQL for audit and metrics. In Phase 4 the service is containerised and queried by the Streamlit dashboard.

---

## 2. Folder Structure

```
python/
├── api/
│   ├── Dockerfile           # Phase 4 container image
│   ├── main.py              # FastAPI app, lifespan (startup/shutdown)
│   ├── config.py            # Settings loaded from environment variables
│   ├── schemas.py           # Pydantic request/response models
│   ├── model_service.py     # Model loading, feature engineering, prediction
│   ├── database.py          # SQLAlchemy engine and session factory
│   ├── db_models.py         # ORM table definitions (maps to PostgreSQL)
│   └── routers/
│       ├── __init__.py
│       ├── predict.py       # POST /predict
│       ├── batch.py         # POST /batch
│       ├── health.py        # GET /health
│       └── metrics.py       # GET /metrics
├── models/
│   └── fraud_model.pkl      # Trained model from Phase 1
├── data/
│   └── transactions.csv     # Training data (used only for reference)
├── ml_training/
│   ├── generate_data.py
│   ├── train_model.py
│   └── evaluate_model.py
└── requirements.txt
```

---

## 3. Configuration (`config.py`)

Uses `pydantic-settings` / `python-dotenv` to read from a `.env` file.

| Variable | Default | Description |
|---|---|---|
| `MODEL_PATH` | `models/fraud_model.pkl` | Path to serialized model |
| `DATABASE_URL` | `postgresql://user:pass@localhost:5432/fraud_db` | SQLAlchemy connection string |
| `FRAUD_THRESHOLD` | `0.5` | Minimum fraud score to set `is_fraudulent = True` |
| `APP_HOST` | `0.0.0.0` | Uvicorn bind host |
| `APP_PORT` | `8000` | Uvicorn port |
| `LOG_LEVEL` | `info` | Uvicorn / app log level |

---

## 4. Pydantic Schemas (`schemas.py`)

### 4.1 Request Models

```python
# POST /predict
class TransactionRequest(BaseModel):
    transaction_id: str
    amount: float          # Must be > 0
    merchant_category: Literal['grocery', 'restaurant', 'gas_station', 'retail', 'online']
    location: str          # 2-letter US state code, e.g. "CA"
    timestamp: datetime    # ISO 8601 — used to derive hour_of_day
    user_id: str

# POST /batch
class BatchRequest(BaseModel):
    transactions: List[TransactionRequest]  # Max 100 items
```

### 4.2 Response Models

```python
class FraudPredictionResponse(BaseModel):
    transaction_id: str
    fraud_score: float          # 0.0 – 1.0  (model probability)
    is_fraudulent: bool         # fraud_score >= FRAUD_THRESHOLD
    confidence: Literal['low', 'medium', 'high']
    risk_factors: List[str]     # Human-readable reasons (see section 6.3)
    processing_time_ms: float

class BatchResponse(BaseModel):
    results: List[FraudPredictionResponse]
    total_processed: int
    fraud_detected: int

class HealthResponse(BaseModel):
    status: Literal['healthy', 'degraded', 'unhealthy']
    model_loaded: bool
    db_connected: bool
    uptime_seconds: float

class MetricsResponse(BaseModel):
    total_predictions: int
    fraud_rate: float           # Fraction of transactions flagged as fraud
    avg_fraud_score: float
    avg_processing_time_ms: float
```

---

## 5. Database (`database.py` + `db_models.py`)

### 5.1 SQLAlchemy Setup (`database.py`)

```python
engine = create_engine(settings.DATABASE_URL)
SessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)

def get_db():
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()
```

### 5.2 ORM Model (`db_models.py`)

Maps to the `transactions` table defined in the HLD.

| Column | Type | Nullable | Notes |
|---|---|---|---|
| `id` | UUID | No | Primary key, auto-generated |
| `transaction_id` | VARCHAR(100) | No | Unique, from request |
| `amount` | DECIMAL(10,2) | No | From request |
| `merchant_category` | VARCHAR(50) | No | From request |
| `location` | VARCHAR(10) | No | From request |
| `hour_of_day` | INTEGER | No | Derived from timestamp |
| `user_id` | VARCHAR(100) | No | From request |
| `fraud_score` | FLOAT | No | Model output |
| `is_fraudulent` | BOOLEAN | No | Model output |
| `confidence` | VARCHAR(10) | No | low / medium / high |
| `risk_factors` | TEXT | Yes | JSON array serialized as string |
| `processing_time_ms` | FLOAT | No | Wall-clock time for prediction |
| `created_at` | TIMESTAMP | No | UTC, server default |

> **Note:** The column is `created_at` (not `checked_at`). The Streamlit dashboard and any direct DB queries must use `created_at`.

### 5.3 Table Initialization

`main.py` calls `Base.metadata.create_all(bind=engine)` on startup.

---

## 6. Model Service (`model_service.py`)

This is the core module. It is a singleton — the model is loaded once at application startup and held in memory.

### 6.1 Loading

```python
class ModelService:
    _model = None
    _label_encoders = None
    _loaded_at = None

    @classmethod
    def load(cls, path: str):
        package = joblib.load(path)
        cls._model = package['model']
        cls._label_encoders = package['label_encoders']
        cls._loaded_at = datetime.utcnow()

    @classmethod
    def is_loaded(cls) -> bool:
        return cls._model is not None
```

### 6.2 Feature Engineering at Inference

The model expects the same 9 features it was trained on. Given a `TransactionRequest`, the service constructs them as follows:

| Feature | Source | Derivation |
|---|---|---|
| `amount` | Request | Direct |
| `merchant_category` | Request | `LabelEncoder.transform()` using saved encoder |
| `location` | Request | `LabelEncoder.transform()` using saved encoder |
| `hour_of_day` | Request | `request.timestamp.hour` |
| `user_id` | Request | Direct (numeric) |
| `tx_velocity` | DB | Count of user's transactions in last 24h, divided by 24 |
| `amount_percentile` | DB | Percentile rank of this amount among user's historical amounts |
| `hour_sin` | Derived | `sin(2π × hour_of_day / 24)` |
| `hour_cos` | Derived | `cos(2π × hour_of_day / 24)` |

**Fallback for new users** (no history in DB): `tx_velocity = 1.0`, `amount_percentile = 0.5`.

### 6.3 Risk Factor Extraction

| Condition | Risk Factor Label |
|---|---|
| `fraud_score >= 0.7` and `amount > 300` | `"unusual_amount"` |
| `fraud_score >= 0.7` and `hour_of_day` in [0–5] | `"unusual_hour"` |
| `tx_velocity > 5` | `"high_transaction_velocity"` |
| `amount_percentile > 0.9` | `"amount_above_user_average"` |
| `merchant_category` in ['online'] and `fraud_score >= 0.6` | `"high_risk_merchant_category"` |

### 6.4 Confidence Mapping

| `fraud_score` range | `confidence` |
|---|---|
| < 0.3 | `"low"` |
| 0.3 – 0.7 | `"medium"` |
| > 0.7 | `"high"` |

### 6.5 Prediction Flow (single transaction)

```
TransactionRequest
    │
    ▼
extract hour_of_day from timestamp
    │
    ▼
query DB for user's tx_velocity + amount_percentile
    │
    ▼
label-encode merchant_category and location
    │
    ▼
compute hour_sin, hour_cos
    │
    ▼
model.predict_proba([feature_vector]) → fraud_score
    │
    ▼
apply FRAUD_THRESHOLD → is_fraudulent
    │
    ▼
compute confidence + risk_factors
    │
    ▼
persist to DB
    │
    ▼
return FraudPredictionResponse
```

This same endpoint is called by both the Java client (`FraudDetectionApiClient.predict()`) and the Streamlit **Check Transaction** page (`POST {FRAUD_API_URL}/predict`).

---

## 7. API Endpoints

### 7.1 `POST /predict`

**Purpose:** Score a single transaction.

**Request body:** `TransactionRequest`

**Response:** `FraudPredictionResponse` (HTTP 200)

**Error responses:**
| Code | Condition |
|---|---|
| 409 | Duplicate `transaction_id` (IntegrityError caught, session rolled back) |
| 422 | Pydantic validation failure (bad input) |
| 503 | Model not loaded |
| 500 | Unexpected error during inference |

---

### 7.2 `POST /batch`

**Purpose:** Score multiple transactions in a single call.

**Request body:** `BatchRequest` (max 100 transactions enforced via Pydantic `@field_validator`)

**Response:** `BatchResponse` (HTTP 200)

---

### 7.3 `GET /health`

**Purpose:** Liveness/readiness check (used by Docker healthcheck, load balancers).

**Response:** `HealthResponse` (HTTP 200 if healthy, HTTP 503 if unhealthy)

**Checks performed:**
1. `ModelService.is_loaded()`
2. DB connection: execute `SELECT 1`
3. `uptime_seconds`: current time minus app start time

---

### 7.4 `GET /metrics`

**Purpose:** Aggregate statistics from stored predictions.

**Response:** `MetricsResponse` (HTTP 200)

**DB queries:**
```sql
SELECT
    COUNT(*)                      AS total_predictions,
    AVG(fraud_score)              AS avg_fraud_score,
    AVG(CAST(is_fraudulent AS INT)) AS fraud_rate,
    AVG(processing_time_ms)       AS avg_processing_time_ms
FROM transactions;
```

---

## 8. Application Lifecycle (`main.py`)

Uses FastAPI's `lifespan` context manager:

```python
@asynccontextmanager
async def lifespan(app: FastAPI):
    # Startup
    ModelService.load(settings.MODEL_PATH)
    Base.metadata.create_all(bind=engine)
    yield
    # Shutdown — nothing needed for Phase 2

app = FastAPI(title="Fraud Detection API", version="1.0.0", lifespan=lifespan)
```

---

## 9. Error Handling

A global exception handler is registered in `main.py`:

```python
@app.exception_handler(Exception)
async def generic_exception_handler(request, exc):
    return JSONResponse(status_code=500, content={"detail": "Internal server error"})
```

A custom `ModelNotLoadedException` returns HTTP 503 from the predict and batch routers.
Duplicate `transaction_id` (PostgreSQL `IntegrityError`) returns HTTP 409 in both `/predict` and `/batch`.

---

## 10. Running the Service

```bash
# From python/ directory (so 'api' is a top-level package):
cd python
uvicorn api.main:app --host 0.0.0.0 --port 8000 --reload

# Interactive docs available at:
# http://localhost:8000/docs   (Swagger UI)
# http://localhost:8000/redoc  (ReDoc)
```

---

## 11. Phase 2 Success Criteria

| Criterion | Target |
|---|---|
| `POST /predict` response time | < 100ms |
| All 4 endpoints return correct responses | Pass |
| Every prediction logged to PostgreSQL | Pass |
| `GET /health` returns 200 when model + DB are up | Pass |
| `GET /metrics` returns aggregate stats | Pass |
| Input validation rejects bad requests with 422 | Pass |
| Duplicate `transaction_id` returns 409 (not 500) | Pass |
