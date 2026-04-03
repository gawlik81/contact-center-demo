from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    redis_url: str = "redis://localhost:6379"
    rabbitmq_url: str = "amqp://guest:guest@localhost:5672/"
    whisper_model: str = "small"
    confidence_threshold: float = 0.40
    session_ttl_seconds: int = 900  # 15 min
    voicebot_service_name: str = "voicebot"
    max_turns: int = 3  # maksymalna liczba tur multi-turn dialogu

    class Config:
        env_file = ".env"
        env_file_encoding = "utf-8"


settings = Settings()
