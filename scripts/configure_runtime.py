"""Internal helper for local commands; never prints credentials."""
from pathlib import Path
import os
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]


def runtime_env():
    env = os.environ.copy()
    env.pop("DEBUG", None)
    file = ROOT / ".env"
    if not file.exists():
        raise SystemExit("Run scripts/setup.ps1 first to create .env")
    for line in file.read_text(encoding="utf-8-sig").splitlines():
        if line and not line.startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            env[key] = value
    user, password, database = env["POSTGRES_USER"], env["POSTGRES_PASSWORD"], env["POSTGRES_DB"]
    env.update({
        "DATABASE_URL": f"jdbc:postgresql://127.0.0.1:5432/{database}",
        "DATABASE_USERNAME": user, "DATABASE_PASSWORD": password,
        "DATABASE_READ_URL": f"postgresql://{user}:{password}@127.0.0.1:5432/{database}",
        "DATABASE_DERIVED_DATA_URL": f"postgresql://{user}:{password}@127.0.0.1:5432/{database}",
        "RABBITMQ_URL": f"amqp://{env['RABBITMQ_USER']}:{env['RABBITMQ_PASSWORD']}@localhost:5672/",
        "RABBITMQ_USERNAME": env["RABBITMQ_USER"],
        "S3_ACCESS_KEY": env["MINIO_ROOT_USER"], "S3_SECRET_KEY": env["MINIO_ROOT_PASSWORD"],
        "S3_ENDPOINT": "http://localhost:9000", "S3_PUBLIC_ENDPOINT": "http://localhost:9000",
    })
    local_jdk = Path.home() / ".jdks/ms-21.0.10"
    if local_jdk.exists():
        env["JAVA_HOME"] = str(local_jdk)
        env["PATH"] = str(local_jdk / "bin") + os.pathsep + env["PATH"]
    return env


if __name__ == "__main__":
    import shutil
    command = sys.argv[1:]
    if not command:
        raise SystemExit("Usage: python scripts/configure_runtime.py <command> [arguments]")
    command[0] = shutil.which(command[0]) or command[0]
    raise SystemExit(subprocess.call(command, cwd=ROOT, env=runtime_env()))
