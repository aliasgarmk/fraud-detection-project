#!/usr/bin/env bash
set -e

echo "============================================"
echo "  Fraud Detection System - Startup Script"
echo "============================================"
echo

# ── Check Docker is running ───────────────────────────────────────────────────
if ! docker info > /dev/null 2>&1; then
    echo "[ERROR] Docker is not running. Please start Docker Desktop and try again."
    exit 1
fi
echo "[OK] Docker is running."

# ── Locate Python in venv ─────────────────────────────────────────────────────
if [ -f "venv/Scripts/python.exe" ]; then
    PYTHON="venv/Scripts/python.exe"       # Windows (Git Bash)
elif [ -f "venv/bin/python" ]; then
    PYTHON="venv/bin/python"               # Linux / macOS
else
    echo "[ERROR] venv not found. Run: python -m venv venv && pip install -r python/requirements.txt"
    exit 1
fi

# ── Check for trained model ───────────────────────────────────────────────────
if [ -f "python/models/fraud_model.pkl" ]; then
    echo "[OK] Trained model found - skipping data generation and training."
else
    echo "[!] No trained model found. Running ML pipeline..."
    echo

    # Generate data if CSV is also missing
    if [ ! -f "python/data/transactions.csv" ]; then
        echo "[1/2] Generating synthetic transaction data..."
        "$PYTHON" python/ml_training/generate_data.py
        echo "[OK] Data generated."
    else
        echo "[OK] Training data already exists - skipping generation."
    fi

    # Train model
    echo "[2/2] Training the fraud detection model..."
    "$PYTHON" python/ml_training/train_model.py
    echo "[OK] Model trained and saved."
fi

# ── Start Docker services ─────────────────────────────────────────────────────
echo
echo "Starting all services via Docker Compose..."
echo
docker-compose up --build "$@"
