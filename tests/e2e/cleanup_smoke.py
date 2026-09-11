"""Verify real Core -> RabbitMQ -> worker -> object-store cleanup for owned test data."""
import os
import time
from uuid import uuid4

import boto3
import httpx
import psycopg
from botocore.exceptions import ClientError
from local_smoke import CONTENT, request, wait_resource


def main():
    storage = boto3.client("s3", endpoint_url=os.environ["S3_ENDPOINT"],
                           aws_access_key_id=os.environ["S3_ACCESS_KEY"],
                           aws_secret_access_key=os.environ["S3_SECRET_KEY"], region_name="us-east-1")
    with httpx.Client(timeout=20, trust_env=False) as client, psycopg.connect(
            os.environ["DATABASE_DERIVED_DATA_URL"], autocommit=True) as database:
        account = request(client, "POST", "/auth/register", {
            "email": f"cleanup-{uuid4().hex}@example.com", "password": "StudyOSTest123!", "displayName": "Cleanup test"})
        token = account["tokens"]["accessToken"]
        for kind in ("notebooks", "workspaces"):
            workspace = request(client, "POST", "/workspaces", {"name": f"Cleanup {kind}"}, token)["id"]
            notebook = request(client, "POST", f"/workspaces/{workspace}/notebooks", {"title": "Cleanup evidence"}, token)["id"]
            source = request(client, "POST", f"/notebooks/{notebook}/sources/raw", {"title": "Evidence", "content": CONTENT}, token, str(uuid4()))["id"]
            wait_resource(client, f"/sources/{source}", token, "READY")
            versions = database.execute("SELECT object_key,normalized_object_key FROM source_versions WHERE source_id=%s", (source,)).fetchall()
            keys = {key for row in versions for key in row if key}
            assert keys, "Test source has no stored objects"
            for key in keys:
                storage.head_object(Bucket=os.environ["S3_BUCKET"], Key=key)
            if kind == "workspaces":
                request(client, "PATCH", f"/notebooks/{notebook}", {"status": "ARCHIVED"}, token)
            request(client, "DELETE", f"/{kind}/{notebook if kind == 'notebooks' else workspace}", token=token)
            deadline = time.monotonic() + 45
            while time.monotonic() < deadline:
                state = database.execute("SELECT status FROM sources WHERE id=%s", (source,)).fetchone()[0]
                if state == "DELETED":
                    break
                time.sleep(0.5)
            assert state == "DELETED", f"Source cleanup stuck at {state}"
            assert database.execute("SELECT count(*) FROM document_chunks WHERE notebook_id=%s", (notebook,)).fetchone()[0] == 0
            for key in keys:
                try:
                    storage.head_object(Bucket=os.environ["S3_BUCKET"], Key=key)
                except ClientError as error:
                    assert error.response["ResponseMetadata"]["HTTPStatusCode"] == 404
                else:
                    raise AssertionError("Source object survived container deletion")
            print(f"PASS {kind} deletion removes source objects and derived chunks", flush=True)


if __name__ == "__main__":
    main()
