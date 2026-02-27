from datetime import datetime
from typing import List, Literal

from pydantic import BaseModel, ConfigDict, field_validator


class TransactionRequest(BaseModel):
    transaction_id: str
    amount: float
    merchant_category: Literal['grocery', 'restaurant', 'gas_station', 'retail', 'online']
    location: str   # 2-letter US state code, e.g. "CA"
    timestamp: datetime
    user_id: str

    @field_validator('amount')
    @classmethod
    def amount_must_be_positive(cls, v: float) -> float:
        if v <= 0:
            raise ValueError('amount must be greater than 0')
        return v


class FraudPredictionResponse(BaseModel):
    transaction_id: str
    fraud_score: float                          # 0.0 – 1.0
    is_fraudulent: bool
    confidence: Literal['low', 'medium', 'high']
    risk_factors: List[str]
    processing_time_ms: float


class BatchRequest(BaseModel):
    transactions: List[TransactionRequest]

    @field_validator('transactions')
    @classmethod
    def max_batch_size(cls, v: list) -> list:
        if len(v) > 100:
            raise ValueError('Maximum 100 transactions per batch request')
        if len(v) == 0:
            raise ValueError('transactions list must not be empty')
        return v


class BatchResponse(BaseModel):
    results: List[FraudPredictionResponse]
    total_processed: int
    fraud_detected: int


class HealthResponse(BaseModel):
    model_config = ConfigDict(protected_namespaces=())

    status: Literal['healthy', 'degraded', 'unhealthy']
    model_loaded: bool
    db_connected: bool
    uptime_seconds: float


class MetricsResponse(BaseModel):
    total_predictions: int
    fraud_rate: float               # Fraction of transactions flagged as fraud
    avg_fraud_score: float
    avg_processing_time_ms: float
