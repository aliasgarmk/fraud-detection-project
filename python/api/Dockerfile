FROM python:3.11-slim

# Install curl for docker-compose healthcheck
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Install Python dependencies first (layer-cached unless requirements.txt changes)
COPY python/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Copy application source and the trained model
COPY python/api/ api/
COPY python/models/ models/

EXPOSE 8000

CMD ["uvicorn", "api.main:app", "--host", "0.0.0.0", "--port", "8000"]
