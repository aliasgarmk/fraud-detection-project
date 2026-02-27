# Phase 4 Low-Level Design: Docker Orchestration, Dashboard & Interactive UI

**Version:** 1.1
**Phase:** 4 of 4
**Status:** Complete

---

## 1. Overview

Phase 4 containerises all components and adds a real-time monitoring dashboard plus an
interactive transaction-checking UI so the entire system starts with a single
`docker-compose up --build` command.

---

## 2. Target Architecture

```
┌────────────────────────────────────────────────────────────────────────┐
│                          docker-compose network                         │
│                                                                        │
│  ┌─────────────┐   SQL   ┌──────────────────────────────────────────┐  │
│  │  PostgreSQL │◄────────│  fraud_db          fraud_client_db       │  │
│  │  :5432      │         │  (Python API)      (Java client)         │  │
│  └──────┬──────┘         └──────────────────────────────────────────┘  │
│         │                                                              │
│         │ SQL (fraud_db)          SQL (fraud_client_db)               │
│         ▼                         ▼                                   │
│  ┌─────────────┐   HTTP   ┌───────────────┐                          │
│  │  fraud-api  │◄─────────│ fraud-client  │                          │
│  │  :8000      │          │  :8080        │                          │
│  └──────┬──────┘          └───────────────┘                          │
│         │  HTTP (POST /predict)                                       │
│         │  SQL (fraud_db, read-only queries)                          │
│         ▼                                                              │
│  ┌─────────────┐                                                      │
│  │  dashboard  │  app.py (monitoring) + pages/2_Check_Transaction.py  │
│  │  :8501      │                                                      │
│  └─────────────┘                                                      │
└────────────────────────────────────────────────────────────────────────┘
```

### Component Responsibilities

| Service | Image | Port | DB / API |
|---|---|---|---|
| `postgres` | `postgres:16-alpine` | 5432 | hosts both databases |
| `fraud-api` | built from `python/api/Dockerfile` | 8000 | reads/writes `fraud_db` |
| `fraud-client` | built from `java/Dockerfile` | 8080 | reads/writes `fraud_client_db` |
| `dashboard` | built from `python/dashboard/Dockerfile` | 8501 | reads `fraud_db` + calls `fraud-api` |

---

## 3. PostgreSQL Initialisation

A single PostgreSQL 16 container hosts two databases:

- **`fraud_db`** — created automatically via `POSTGRES_DB`; owned by the Python API
- **`fraud_client_db`** — created by `docker/postgres/init.sql` at first startup

**Startup order enforced by healthchecks:**
```
postgres (healthy) → fraud-api (healthy) → fraud-client
                  → dashboard
```

---

## 4. Python API Dockerfile (`python/api/Dockerfile`)

- Base: `python:3.11-slim`
- Installs `curl` for the compose healthcheck
- Copies `python/requirements.txt`, then `python/api/` and `python/models/`
- Runs: `uvicorn api.main:app --host 0.0.0.0 --port 8000`
- Configuration injected via environment variables (same keys as `.env.example`)

---

## 5. Java Client — Docker Profile

The Java client uses **Spring profiles** to switch datasource:

| Profile | DB | DDL |
|---|---|---|
| `default` (local) | H2 in-memory | `create-drop` |
| `docker` | PostgreSQL `fraud_client_db` | `update` |

`application-docker.yml` overrides only the datasource and fraud-api URL; all other
settings (retry, circuit-breaker, Jackson, ports) are inherited from `application.yml`.

### Dockerfile (`java/Dockerfile`)

Multi-stage build:
1. **Stage 1** (`maven:3.9-eclipse-temurin-21`): downloads dependencies, runs
   `mvn package -DskipTests`, produces fat JAR
2. **Stage 2** (`eclipse-temurin:21-jre-alpine`): copies the JAR, runs it

This keeps the final image small (~200 MB) by excluding the Maven toolchain.

### pom.xml change

`org.postgresql:postgresql` added as a `runtime` dependency alongside H2. Spring Boot's
BOM manages the version; both drivers remain on the classpath and Spring selects the
correct one based on the active profile's `datasource.driver-class-name`.

---

## 6. Streamlit Dashboard (`python/dashboard/`)

A multi-page Streamlit application. The main page is a read-only monitoring panel;
a second page provides an interactive fraud-check form that calls the live API.

### 6.1 Monitoring Page (`app.py`)

Queries `fraud_db` directly via `psycopg2`. Data is cached for 30 s with
`@st.cache_data(ttl=30)` to avoid hammering the DB on every rerender.

| Panel | Source |
|---|---|
| KPI cards | total rows, fraud count, fraud rate %, avg fraud score |
| Fraud over time | `GROUP BY DATE(created_at)` line chart |
| Score distribution | histogram of `fraud_score` |
| Fraud by merchant | `GROUP BY merchant_category` bar chart |
| Fraud by location | `GROUP BY location` bar chart |
| Recent transactions | last 50 rows table |

### 6.2 Check Transaction Page (`pages/2_Check_Transaction.py`)

An interactive form that lets users submit a transaction to the live `fraud-api` and
immediately view the result — demonstrating end-to-end system integration from the UI
through the REST API to the ML model.

**Form fields:**

| Field | Widget | Notes |
|---|---|---|
| Transaction ID | `st.text_input` | Pre-filled with random UUID hex |
| Amount ($) | `st.number_input` | Range 0.01 – 100,000 |
| Merchant Category | `st.selectbox` | grocery / restaurant / gas_station / retail / online |
| Location (State) | `st.selectbox` | AZ / CA / FL / IL / NV / NY / TX / WA |
| User ID | `st.text_input` | Default: `user_1000` |
| Timestamp | `st.date_input` | Default: today (UTC) |

**Integration flow:**

```
User fills form → st.form_submit_button
    │
    ▼
POST {FRAUD_API_URL}/predict
    │ payload: {transaction_id, amount, merchant_category,
    │           location, timestamp (ISO-8601Z), user_id}
    ▼
fraud-api (Python FastAPI)
    │  runs RandomForest → persists to fraud_db
    ▼
FraudPredictionResponse {fraud_score, is_fraudulent, confidence, risk_factors, ...}
    │
    ▼
Dashboard renders result banner:
  🚨 FRAUD DETECTED   (st.error, red)     if is_fraudulent == true
  ✅ LEGITIMATE       (st.success, green)  if is_fraudulent == false
    │
    ▼
Metrics row: Fraud Score | Confidence | Processing Time ms
Progress bar: fraud_score (0.0 → 1.0)
Risk factor list (if any)
Collapsible: raw request payload + full API response
```

**Error handling:**

| Condition | UI response |
|---|---|
| `transaction_id` blank | `st.error` + `st.stop()` |
| API connection refused | `st.error` with API URL shown |
| API timeout (> 10 s) | `st.error` |
| HTTP 409 duplicate | `st.warning` — change the Transaction ID |
| HTTP 422 validation | `st.error` with detail message |
| Any other HTTP error | `st.error` with status code |

**Environment variable:**

| Variable | Docker default | Local default |
|---|---|---|
| `FRAUD_API_URL` | `http://fraud-api:8000` | `http://localhost:8000` |

### 6.3 Dockerfile (`python/dashboard/Dockerfile`)

```dockerfile
FROM python:3.11-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY . .                   # copies app.py AND pages/ directory
EXPOSE 8501
CMD ["streamlit", "run", "app.py",
     "--server.port=8501",
     "--server.address=0.0.0.0",
     "--server.headless=true"]
```

`COPY . .` is required (not `COPY app.py .`) so the `pages/` subdirectory is included
in the image, enabling Streamlit's multi-page sidebar navigation.

---

## 7. docker-compose.yml

```
version: '3.9'

services:
  postgres         → single DB server, volume for persistence
  fraud-api        → depends_on postgres healthy; env: DATABASE_URL, MODEL_PATH, FRAUD_THRESHOLD
  fraud-client     → depends_on fraud-api healthy + postgres healthy
  dashboard        → depends_on postgres healthy; env: DATABASE_URL, FRAUD_API_URL

volumes:
  postgres_data    → survives container restarts
```

All inter-service hostnames resolve via Docker's built-in DNS (service name = hostname).

---

## 8. File Structure Added / Modified in Phase 4

```
fraud-detection-project/
├── docker/
│   └── postgres/
│       └── init.sql                   # creates fraud_client_db on first boot
├── python/
│   ├── api/
│   │   └── Dockerfile                 # fraud-api image
│   └── dashboard/
│       ├── app.py                     # monitoring dashboard (main page)
│       ├── pages/
│       │   └── 2_Check_Transaction.py # interactive fraud-check UI (Phase 4 addition)
│       ├── requirements.txt           # streamlit, pandas, plotly, psycopg2, requests
│       └── Dockerfile                 # dashboard image (COPY . . to include pages/)
├── java/
│   ├── Dockerfile                     # multi-stage Maven → JRE image
│   └── src/main/resources/
│       └── application-docker.yml     # docker Spring profile (PostgreSQL datasource)
├── docker-compose.yml
├── .dockerignore
└── README.md
```

---

## 9. Success Criteria

| Criterion | Target |
|---|---|
| `docker-compose up --build` starts all four services | Pass |
| `GET http://localhost:8000/health` → `{"status": "healthy"}` | Pass |
| `GET http://localhost:8080/actuator/health` → `{"status": "UP"}` | Pass |
| `http://localhost:8501` shows monitoring dashboard | Pass |
| Sidebar shows **Check Transaction** page link | Pass |
| Submitting a form on Check Transaction calls `/predict` and shows fraud/legit banner | Pass |
| Duplicate transaction ID returns warning (not crash) | Pass |
| Java client history survives a container restart (PostgreSQL persistence) | Pass |
| `docker-compose down -v` cleanly removes all containers and volumes | Pass |
