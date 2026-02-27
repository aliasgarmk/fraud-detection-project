import json

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from api.database import get_db
from api.db_models import Transaction
from api.model_service import ModelService
from api.schemas import FraudPredictionResponse, TransactionRequest

router = APIRouter()


@router.post("/predict", response_model=FraudPredictionResponse)
def predict(request: TransactionRequest, db: Session = Depends(get_db)):
    """Score a single transaction for fraud."""
    result = ModelService.predict(request, db)

    try:
        db.add(Transaction(
            transaction_id=result.transaction_id,
            amount=request.amount,
            merchant_category=request.merchant_category,
            location=request.location,
            hour_of_day=request.timestamp.hour,
            user_id=request.user_id,
            fraud_score=result.fraud_score,
            is_fraudulent=result.is_fraudulent,
            confidence=result.confidence,
            risk_factors=json.dumps(result.risk_factors),
            processing_time_ms=result.processing_time_ms,
        ))
        db.commit()
    except IntegrityError:
        db.rollback()
        raise HTTPException(
            status_code=409,
            detail=f"Transaction ID '{request.transaction_id}' has already been processed",
        )

    return result
