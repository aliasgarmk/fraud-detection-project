import json

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from api.database import get_db
from api.db_models import Transaction
from api.model_service import ModelService
from api.schemas import BatchRequest, BatchResponse

router = APIRouter()


@router.post("/batch", response_model=BatchResponse)
def batch_predict(request: BatchRequest, db: Session = Depends(get_db)):
    """Score multiple transactions in a single request (max 100)."""
    results = []

    for tx in request.transactions:
        result = ModelService.predict(tx, db)
        results.append(result)

        db.add(Transaction(
            transaction_id=result.transaction_id,
            amount=tx.amount,
            merchant_category=tx.merchant_category,
            location=tx.location,
            hour_of_day=tx.timestamp.hour,
            user_id=tx.user_id,
            fraud_score=result.fraud_score,
            is_fraudulent=result.is_fraudulent,
            confidence=result.confidence,
            risk_factors=json.dumps(result.risk_factors),
            processing_time_ms=result.processing_time_ms,
        ))

    try:
        db.commit()
    except IntegrityError:
        db.rollback()
        raise HTTPException(
            status_code=409,
            detail="One or more transaction IDs in this batch have already been processed",
        )

    return BatchResponse(
        results=results,
        total_processed=len(results),
        fraud_detected=sum(1 for r in results if r.is_fraudulent),
    )
