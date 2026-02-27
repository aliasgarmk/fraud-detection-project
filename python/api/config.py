from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    MODEL_PATH: str = "models/fraud_model.pkl"
    DATABASE_URL: str = "postgresql://user:password@localhost:5432/fraud_db"
    FRAUD_THRESHOLD: float = 0.5
    APP_HOST: str = "0.0.0.0"
    APP_PORT: int = 8000
    LOG_LEVEL: str = "info"


settings = Settings()
