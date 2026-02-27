# Phase 1 Low-Level Design: ML Model Development

**Version:** 1.1
**Phase:** 1 of 4
**Status:** Complete

---

## 1. Overview

Phase 1 builds the core machine learning pipeline that underpins the entire system.
It produces a serialised Random Forest model (`fraud_model.pkl`) consumed by the
Phase 2 FastAPI service and (indirectly) by the Phase 4 Streamlit dashboard via the API.

---

## 2. Project Structure

```
python/
├── ml_training/
│   ├── generate_data.py    # Step 1 — synthetic dataset creation
│   ├── train_model.py      # Step 2 — model training and serialisation
│   └── evaluate_model.py   # Step 3 — detailed performance analysis
├── models/
│   └── fraud_model.pkl     # output artefact (joblib-serialised dict)
└── data/
    └── transactions.csv    # output artefact (10,000 rows)
```

All scripts use `__file__`-based path resolution so they can be run from any working directory:

```python
_PYTHON_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
_DATA_PATH  = os.path.join(_PYTHON_DIR, 'data', 'transactions.csv')
_MODEL_PATH = os.path.join(_PYTHON_DIR, 'models', 'fraud_model.pkl')
```

---

## 3. Data Generation (`generate_data.py`)

### 3.1 Dataset Specification

| Property | Value |
|---|---|
| Total rows | 10,000 |
| Fraud ratio | 10 % (1,000 fraudulent, 9,000 legitimate) |
| Output | `python/data/transactions.csv` |

### 3.2 Raw Features

| Column | Type | Legitimate distribution | Fraud distribution |
|---|---|---|---|
| `amount` | float | Normal(μ=50, σ=30), clipped [5, 500] | Normal(μ=200, σ=100), clipped [100, 1000] |
| `merchant_category` | str | grocery 30 %, restaurant 25 %, gas 20 %, retail 15 %, online 10 % | online 40 %, retail 30 %, gas 15 %, restaurant 10 %, grocery 5 % |
| `location` | str | CA 40 %, NY 25 %, TX 15 %, FL 12 %, IL 8 % | Uniform across CA/NY/TX/FL/IL/WA/AZ/NV |
| `hour_of_day` | int [0-23] | Business-hours peak (9 am–5 pm) | Night peak (midnight–3 am) |
| `user_id` | int | Range [1000, 2000) | Same range (stolen account) |
| `is_fraud` | int {0, 1} | 0 | 1 |

### 3.3 Engineered Features

Added in `add_engineered_features()` after raw generation:

| Column | Formula | Rationale |
|---|---|---|
| `tx_velocity` | `count(user_id) / 24` | High velocity → suspicion |
| `amount_percentile` | `rank(amount per user, pct=True)` | Unusually large vs user history |
| `hour_sin` | `sin(2π × hour / 24)` | Cyclical hour encoding |
| `hour_cos` | `cos(2π × hour / 24)` | Cyclical hour encoding |

### 3.4 Data Flow

```
generate_legitimate(9000)  ──┐
                              ├── pd.concat → shuffle → add_engineered_features → to_csv
generate_fraudulent(1000)  ──┘
```

---

## 4. Model Training (`train_model.py`)

### 4.1 Pre-processing

| Step | Detail |
|---|---|
| Target extraction | `y = df['is_fraud']`; `X = df.drop('is_fraud')` |
| Categorical encoding | `sklearn.LabelEncoder` on `merchant_category` and `location` |
| Train/test split | 80 / 20, `random_state=42`, stratified on `y` |

**Label encoder classes (order matters for Phase 2 inference):**
- `merchant_category`: `['gas_station', 'grocery', 'online', 'restaurant', 'retail']`
- `location`: `['AZ', 'CA', 'FL', 'IL', 'NV', 'NY', 'TX', 'WA']`

### 4.2 Model Architecture

```
RandomForestClassifier(
    n_estimators    = 100,   # ensemble size
    max_depth       = 10,    # prevents overfitting
    min_samples_split = 20,  # regularisation
    min_samples_leaf  = 10,  # regularisation
    random_state    = 42,
    n_jobs          = -1     # parallelise across all CPU cores
)
```

### 4.3 Training Pipeline

```
load_data()                  # read transactions.csv
  └── preprocess_data()      # encode categoricals, split X/y
        └── split_data()     # 80/20 stratified split
              └── train_model()   # RandomForest.fit(X_train, y_train)
                    └── evaluate_model()  # report on X_test
                          └── save_model()  # joblib.dump → models/fraud_model.pkl
```

### 4.4 Serialised Model Package

`fraud_model.pkl` is a `dict` serialised with `joblib.dump`:

```python
{
    'model':          RandomForestClassifier,  # fitted estimator
    'label_encoders': {
        'merchant_category': LabelEncoder,
        'location':          LabelEncoder,
    }
}
```

The Phase 2 `ModelService` loads this dict and uses both objects at inference time.
The Phase 4 dashboard calls the Phase 2 API which in turn uses this model.

### 4.5 Save Guard

The model is only saved if accuracy on the test set exceeds 90 %:
```python
if accuracy > 0.90:
    save_model(model, label_encoders)
```

---

## 5. Model Evaluation (`evaluate_model.py`)

### 5.1 Metrics Computed

| Metric | Meaning |
|---|---|
| Accuracy | (TP + TN) / total |
| Precision | TP / (TP + FP) — few false alarms |
| Recall | TP / (TP + FN) — catch most fraud |
| F1-Score | Harmonic mean of precision and recall |
| ROC-AUC | Overall discrimination ability |

### 5.2 Sample Transaction Tests

The script includes two hand-crafted transactions to smoke-test the model:

| # | Description | Expected |
|---|---|---|
| 1 | $45 grocery, CA, 2 pm | Legitimate |
| 2 | $850 online, NY, 3 am | Fraudulent |

These same inputs can be submitted via the Phase 4 **Check Transaction** dashboard page to verify the full end-to-end pipeline.

---

## 6. Feature Column Order

The model expects features in this exact order (enforced by `X = df.drop('is_fraud', axis=1)`):

```
amount, merchant_category, location, hour_of_day, user_id,
tx_velocity, amount_percentile, hour_sin, hour_cos
```

Phase 2's `ModelService.predict()` must assemble the feature vector in this order.

---

## 7. Configuration

All randomness is seeded at module level for reproducibility:
```python
np.random.seed(42)
random.seed(42)
```

The train/test split uses `random_state=42` and `stratify=y` to guarantee the same
fraud ratio in both partitions.

---

## 8. Running the Pipeline

```bash
# From the project root, using the venv
venv\Scripts\python.exe python/ml_training/generate_data.py
venv\Scripts\python.exe python/ml_training/train_model.py
venv\Scripts\python.exe python/ml_training/evaluate_model.py
```

---

## 9. Success Criteria

| Criterion | Target |
|---|---|
| Dataset rows | 10,000 (9,000 legit + 1,000 fraud) |
| Model accuracy | > 95 % on held-out test set |
| Precision | > 90 % |
| Recall | > 85 % |
| Serialised artefact | `python/models/fraud_model.pkl` exists and is loadable |
