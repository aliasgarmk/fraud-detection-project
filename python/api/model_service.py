"""
ModelService — singleton that loads the trained fraud detection model once at
startup and handles all feature engineering + prediction logic.
"""

import json
import time
from datetime import datetime, timedelta, timezone
from typing import List, Optional, Tuple

import joblib
import numpy as np
import pandas as pd
from fastapi import HTTPException
from sqlalchemy.orm import Session

from api.config import settings
from api.schemas import FraudPredictionResponse, TransactionRequest

# Column order must exactly match what the model was trained on (train_model.py)
FEATURE_COLUMNS = [
    'amount',
    'merchant_category',
    'location',
    'hour_of_day',
    'user_id',
    'tx_velocity',
    'amount_percentile',
    'hour_sin',
    'hour_cos',
]


class ModelService:
    _model = None
    _label_encoders: Optional[dict] = None
    _loaded_at: Optional[datetime] = None

    # ------------------------------------------------------------------ #
    # Lifecycle                                                            #
    # ------------------------------------------------------------------ #

    @classmethod
    def load(cls, path: str) -> None:
        package = joblib.load(path)
        cls._model = package['model']
        cls._label_encoders = package['label_encoders']
        cls._loaded_at = datetime.now(timezone.utc)
        print(f"[OK] Model loaded from {path}")

    @classmethod
    def is_loaded(cls) -> bool:
        return cls._model is not None

    # ------------------------------------------------------------------ #
    # Public prediction entry-point                                        #
    # ------------------------------------------------------------------ #

    @classmethod
    def predict(
        cls,
        request: TransactionRequest,
        db: Session,
    ) -> FraudPredictionResponse:
        if not cls.is_loaded():
            raise HTTPException(status_code=503, detail="Model not loaded")

        start = time.perf_counter()

        hour_of_day = request.timestamp.hour
        user_id_numeric = cls._encode_user_id(request.user_id)
        tx_velocity, amount_percentile = cls._get_user_stats(request, db)
        hour_sin = float(np.sin(2 * np.pi * hour_of_day / 24))
        hour_cos = float(np.cos(2 * np.pi * hour_of_day / 24))

        # Encode categorical features using the saved LabelEncoders
        try:
            merchant_encoded = int(
                cls._label_encoders['merchant_category'].transform(
                    [request.merchant_category]
                )[0]
            )
            location_encoded = int(
                cls._label_encoders['location'].transform([request.location])[0]
            )
        except ValueError as exc:
            raise HTTPException(
                status_code=422,
                detail=f"Unknown value for encoded field: {exc}",
            )

        feature_vector = pd.DataFrame(
            [[
                request.amount,
                merchant_encoded,
                location_encoded,
                hour_of_day,
                user_id_numeric,
                tx_velocity,
                amount_percentile,
                hour_sin,
                hour_cos,
            ]],
            columns=FEATURE_COLUMNS,
        )

        fraud_score = float(cls._model.predict_proba(feature_vector)[0, 1])
        is_fraudulent = fraud_score >= settings.FRAUD_THRESHOLD
        confidence = cls._get_confidence(fraud_score)
        risk_factors = cls._get_risk_factors(
            fraud_score=fraud_score,
            amount=request.amount,
            hour_of_day=hour_of_day,
            tx_velocity=tx_velocity,
            amount_percentile=amount_percentile,
            merchant_category=request.merchant_category,
        )

        processing_time_ms = round((time.perf_counter() - start) * 1000, 2)

        return FraudPredictionResponse(
            transaction_id=request.transaction_id,
            fraud_score=round(fraud_score, 4),
            is_fraudulent=is_fraudulent,
            confidence=confidence,
            risk_factors=risk_factors,
            processing_time_ms=processing_time_ms,
        )

    # ------------------------------------------------------------------ #
    # Private helpers                                                      #
    # ------------------------------------------------------------------ #

    @staticmethod
    def _encode_user_id(user_id: str) -> int:
        """
        The model was trained with integer user_ids in [1000, 1999].
        Try parsing the string directly; fall back to a stable hash-derived value.
        """
        try:
            return int(user_id)
        except (ValueError, TypeError):
            return abs(hash(user_id)) % 1000 + 1000

    @staticmethod
    def _get_user_stats(
        request: TransactionRequest,
        db: Session,
    ) -> Tuple[float, float]:
        """
        Compute tx_velocity and amount_percentile from the user's DB history.
        Returns (1.0, 0.5) as fallback for users with no history.
        """
        from sqlalchemy import func
        from api.db_models import Transaction

        ts = request.timestamp
        if ts.tzinfo is None:
            ts = ts.replace(tzinfo=timezone.utc)
        cutoff = ts - timedelta(hours=24)

        recent_count = (
            db.query(func.count(Transaction.id))
            .filter(
                Transaction.user_id == request.user_id,
                Transaction.created_at >= cutoff,
            )
            .scalar()
        ) or 0

        # +1 accounts for the current transaction being scored
        tx_velocity = (recent_count + 1) / 24.0

        historical_amounts = (
            db.query(Transaction.amount)
            .filter(Transaction.user_id == request.user_id)
            .all()
        )

        if historical_amounts:
            amounts = [float(row.amount) for row in historical_amounts]
            below = sum(1 for a in amounts if a <= request.amount)
            amount_percentile = below / len(amounts)
        else:
            amount_percentile = 0.5

        return tx_velocity, amount_percentile

    @staticmethod
    def _get_confidence(fraud_score: float) -> str:
        if fraud_score > 0.7:
            return 'high'
        elif fraud_score >= 0.3:
            return 'medium'
        return 'low'

    @staticmethod
    def _get_risk_factors(
        fraud_score: float,
        amount: float,
        hour_of_day: int,
        tx_velocity: float,
        amount_percentile: float,
        merchant_category: str,
    ) -> List[str]:
        factors = []
        if fraud_score >= 0.7 and amount > 300:
            factors.append('unusual_amount')
        if fraud_score >= 0.7 and hour_of_day in range(0, 6):
            factors.append('unusual_hour')
        if tx_velocity > 5:
            factors.append('high_transaction_velocity')
        if amount_percentile > 0.9:
            factors.append('amount_above_user_average')
        if merchant_category == 'online' and fraud_score >= 0.6:
            factors.append('high_risk_merchant_category')
        return factors
