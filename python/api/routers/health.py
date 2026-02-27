import time

from fastapi import APIRouter
from fastapi.responses import JSONResponse
from sqlalchemy import text

from api.database import engine
from api.model_service import ModelService
from api.schemas import HealthResponse

router = APIRouter()

_start_time = time.time()


@router.get("/health", response_model=HealthResponse)
def health():
    """
    Liveness + readiness check.
    Returns HTTP 200 when healthy, HTTP 503 when degraded or unhealthy.
    """
    model_loaded = ModelService.is_loaded()

    db_connected = False
    try:
        with engine.connect() as conn:
            conn.execute(text("SELECT 1"))
        db_connected = True
    except Exception:
        pass

    if model_loaded and db_connected:
        status = 'healthy'
    elif model_loaded or db_connected:
        status = 'degraded'
    else:
        status = 'unhealthy'

    response = HealthResponse(
        status=status,
        model_loaded=model_loaded,
        db_connected=db_connected,
        uptime_seconds=round(time.time() - _start_time, 2),
    )

    http_status = 200 if status == 'healthy' else 503
    return JSONResponse(content=response.model_dump(), status_code=http_status)
