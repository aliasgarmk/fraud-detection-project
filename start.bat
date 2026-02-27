@echo off
setlocal enabledelayedexpansion

echo ============================================
echo   Fraud Detection System - Startup Script
echo ============================================
echo.

:: ── Check Docker is running ───────────────────────────────────────────────────
docker info >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Docker is not running. Please start Docker Desktop and try again.
    exit /b 1
)
echo [OK] Docker is running.

:: ── Check for trained model ───────────────────────────────────────────────────
if exist "python\models\fraud_model.pkl" (
    echo [OK] Trained model found - skipping data generation and training.
) else (
    echo [!] No trained model found. Running ML pipeline...
    echo.

    :: Locate Python in venv
    if exist "venv\Scripts\python.exe" (
        set PYTHON=venv\Scripts\python.exe
    ) else (
        echo [ERROR] venv not found. Run: python -m venv venv ^& venv\Scripts\pip install -r python\requirements.txt
        exit /b 1
    )

    :: Generate data if CSV is also missing
    if not exist "python\data\transactions.csv" (
        echo [1/2] Generating synthetic transaction data...
        !PYTHON! python\ml_training\generate_data.py
        if errorlevel 1 ( echo [ERROR] Data generation failed. & exit /b 1 )
        echo [OK] Data generated.
    ) else (
        echo [OK] Training data already exists - skipping generation.
    )

    :: Train model
    echo [2/2] Training the fraud detection model...
    !PYTHON! python\ml_training\train_model.py
    if errorlevel 1 ( echo [ERROR] Model training failed. & exit /b 1 )
    echo [OK] Model trained and saved.
)

:: ── Start Docker services ─────────────────────────────────────────────────────
echo.
echo Starting all services via Docker Compose...
echo.
docker-compose up --build %*

endlocal
