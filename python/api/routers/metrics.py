from fastapi import APIRouter, Depends
from sqlalchemy import cast, func, Integer
from sqlalchemy.orm import Session

from api.database import get_db
from api.db_models import Transaction
from api.schemas import MetricsResponse

router = APIRouter()


@router.get("/metrics", response_model=MetricsResponse)
def metrics(db: Session = Depends(get_db)):
    """Aggregate statistics derived from all stored predictions."""
    row = db.query(
        func.count(Transaction.id).label('total'),
        func.avg(Transaction.fraud_score).label('avg_fraud_score'),
        func.avg(cast(Transaction.is_fraudulent, Integer)).label('fraud_rate'),
        func.avg(Transaction.processing_time_ms).label('avg_processing_time_ms'),
    ).first()

    return MetricsResponse(
        total_predictions=row.total or 0,
        avg_fraud_score=round(float(row.avg_fraud_score or 0.0), 4),
        fraud_rate=round(float(row.fraud_rate or 0.0), 4),
        avg_processing_time_ms=round(float(row.avg_processing_time_ms or 0.0), 2),
    )
