"""
Fraud Detection API — Phase 2
FastAPI service that loads the trained Random Forest model and exposes it
for real-time fraud scoring via REST endpoints.
"""

from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

from api.config import settings
from api.database import Base, engine
from api.model_service import ModelService
from api.routers import batch, health, metrics, predict


@asynccontextmanager
async def lifespan(app: FastAPI):
    # ---- Startup ----
    # Create DB tables if they don't exist yet (no Alembic needed for Phase 2)
    Base.metadata.create_all(bind=engine)

    # Load ML model into memory (done once; held for the lifetime of the process)
    ModelService.load(settings.MODEL_PATH)

    yield

    # ---- Shutdown ---- (nothing needed for Phase 2)


app = FastAPI(
    title="Fraud Detection API",
    description="Real-time ML-powered fraud scoring for financial transactions.",
    version="1.0.0",
    lifespan=lifespan,
)


# ------------------------------------------------------------------ #
# Global exception handler                                            #
# ------------------------------------------------------------------ #

@app.exception_handler(Exception)
async def generic_exception_handler(request: Request, exc: Exception):
    return JSONResponse(
        status_code=500,
        content={"detail": "Internal server error"},
    )


# ------------------------------------------------------------------ #
# Routers                                                             #
# ------------------------------------------------------------------ #

app.include_router(predict.router, tags=["Prediction"])
app.include_router(batch.router, tags=["Prediction"])
app.include_router(health.router, tags=["Operations"])
app.include_router(metrics.router, tags=["Operations"])


# ------------------------------------------------------------------ #
# Entry-point (run directly with: python -m api.main)                 #
# ------------------------------------------------------------------ #

if __name__ == "__main__":
    import uvicorn

    uvicorn.run(
        "api.main:app",
        host=settings.APP_HOST,
        port=settings.APP_PORT,
        log_level=settings.LOG_LEVEL,
        reload=True,
    )
