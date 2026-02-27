import uuid
from datetime import datetime, timezone

from sqlalchemy import Boolean, Column, Float, Integer, String, Text, TIMESTAMP
from sqlalchemy.dialects.postgresql import UUID
from sqlalchemy.types import DECIMAL

from api.database import Base


class Transaction(Base):
    """Persisted record of every fraud prediction made by the API."""

    __tablename__ = "transactions"

    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    transaction_id = Column(String(100), nullable=False, unique=True, index=True)
    amount = Column(DECIMAL(10, 2), nullable=False)
    merchant_category = Column(String(50), nullable=False)
    location = Column(String(10), nullable=False)
    hour_of_day = Column(Integer, nullable=False)
    user_id = Column(String(100), nullable=False, index=True)
    fraud_score = Column(Float, nullable=False)
    is_fraudulent = Column(Boolean, nullable=False)
    confidence = Column(String(10), nullable=False)
    risk_factors = Column(Text, nullable=True)   # JSON-serialized list
    processing_time_ms = Column(Float, nullable=False)
    created_at = Column(
        TIMESTAMP(timezone=True),
        nullable=False,
        default=lambda: datetime.now(timezone.utc),
    )
