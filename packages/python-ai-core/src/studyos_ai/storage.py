import boto3
from botocore.config import Config
from botocore.exceptions import BotoCoreError, ClientError

from .config import Settings
from .errors import PipelineError


class ObjectStore:
    def __init__(self, settings: Settings):
        self.settings = settings
        self.client = boto3.client(
            "s3",
            endpoint_url=settings.s3_endpoint,
            region_name=settings.s3_region,
            aws_access_key_id=settings.s3_access_key.get_secret_value() or None,
            aws_secret_access_key=settings.s3_secret_key.get_secret_value() or None,
            config=Config(
                connect_timeout=10,
                read_timeout=30,
                retries={"max_attempts": 2},
                s3={"addressing_style": "path"},
            ),
        )

    def read(self, key: str) -> bytes:
        if not key or key.startswith("/") or ".." in key.split("/"):
            raise PipelineError(
                "SOURCE_OBJECT_INVALID", "Source object key is invalid."
            )
        try:
            response = self.client.get_object(Bucket=self.settings.s3_bucket, Key=key)
            body = response["Body"]
            try:
                if response.get("ContentLength", 0) > self.settings.max_source_bytes:
                    raise PipelineError(
                        "SOURCE_FILE_TOO_LARGE", "Source exceeds the file limit."
                    )
                data = body.read(self.settings.max_source_bytes + 1)
            finally:
                body.close()
            if len(data) > self.settings.max_source_bytes:
                raise PipelineError(
                    "SOURCE_FILE_TOO_LARGE", "Source exceeds the file limit."
                )
            return data
        except (BotoCoreError, ClientError) as exc:
            raise PipelineError(
                "OBJECT_STORAGE_UNAVAILABLE", "Source object could not be read.", True
            ) from exc

    def write(self, key: str, data: bytes):
        try:
            self.client.put_object(
                Bucket=self.settings.s3_bucket,
                Key=key,
                Body=data,
                ContentType="application/json",
            )
        except (BotoCoreError, ClientError) as exc:
            raise PipelineError(
                "OBJECT_STORAGE_UNAVAILABLE",
                "Normalized source could not be stored.",
                True,
            ) from exc

    def delete(self, key: str):
        try:
            self.client.delete_object(Bucket=self.settings.s3_bucket, Key=key)
        except (BotoCoreError, ClientError) as exc:
            raise PipelineError(
                "OBJECT_STORAGE_UNAVAILABLE",
                "Source object could not be deleted.",
                True,
            ) from exc
