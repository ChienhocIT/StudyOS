from decimal import Decimal

from pydantic import Field, SecretStr, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")
    environment: str = "development"
    internal_jwt_secret: SecretStr = SecretStr("")
    internal_jwt_issuer: str = "studyos-core"
    internal_jwt_audience: str = "studyos-ai"
    database_read_url: SecretStr = SecretStr(
        "postgresql://studyos:studyos@localhost:5432/studyos"
    )
    database_derived_data_url: SecretStr = SecretStr(
        "postgresql://studyos:studyos@localhost:5432/studyos"
    )
    model_provider: str = "local-extractive"
    model_provider_api_key: SecretStr = SecretStr("")
    model_provider_base_url: str = "https://api.openai.com/v1"
    model_name: str = "gpt-4.1-mini"
    model_input_usd_per_million: Decimal | None = Field(default=None, ge=0, allow_inf_nan=False)
    model_output_usd_per_million: Decimal | None = Field(default=None, ge=0, allow_inf_nan=False)
    embedding_model: str = "text-embedding-3-small"
    embedding_dimensions: int = 1536
    context_token_budget: int = Field(default=5000, ge=128, le=24000)
    retrieval_limit: int = Field(default=12, ge=1, le=30)
    provider_timeout_seconds: float = Field(default=60, ge=1, le=180)
    debug_retrieval_enabled: bool = False
    rabbitmq_url: SecretStr = SecretStr("amqp://studyos:studyos@localhost:5672/")
    worker_queues: str = "q.source.parse.v1,q.source.index.v1,q.source.enrich.v1,q.source.transcript.v1,q.source.delete.v1,q.artifact.quiz.v1,q.artifact.flashcards.v1,q.artifact.study-guide.v1"
    worker_concurrency: int = Field(default=2, ge=1, le=16)
    worker_metrics_port: int = 8001
    s3_endpoint: str | None = None
    s3_bucket: str = "studyos-sources"
    s3_region: str = "us-east-1"
    s3_access_key: SecretStr = SecretStr("")
    s3_secret_key: SecretStr = SecretStr("")
    max_source_bytes: int = Field(default=104857600, ge=1, le=104857600)
    max_web_bytes: int = Field(default=5242880, ge=1, le=10485760)
    max_pdf_pages: int = Field(default=500, ge=1, le=500)
    transcript_provider_url: str | None = None
    transcript_provider_key: SecretStr = SecretStr("")

    @model_validator(mode="after")
    def validate_settings(self):
        if len(self.internal_jwt_secret.get_secret_value()) < 32:
            raise ValueError("INTERNAL_JWT_SECRET must contain at least 32 characters")
        if self.embedding_dimensions != 1536:
            raise ValueError("Schema requires exactly 1536 embedding dimensions")
        if self.model_provider not in {"local-extractive", "openai-compatible"}:
            raise ValueError("Unsupported MODEL_PROVIDER")
        if (
            self.environment == "production"
            and self.model_provider == "local-extractive"
        ):
            raise ValueError(
                "Development extractive provider is not permitted in production"
            )
        if (
            self.model_provider == "openai-compatible"
            and not self.model_provider_api_key.get_secret_value()
        ):
            raise ValueError("MODEL_PROVIDER_API_KEY is required")
        return self
