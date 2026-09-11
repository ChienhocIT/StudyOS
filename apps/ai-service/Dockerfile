FROM python:3.12-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
WORKDIR /opt/studyos
COPY packages/python-ai-core packages/python-ai-core
COPY apps/ai-service apps/ai-service
RUN pip install --no-cache-dir ./packages/python-ai-core ./apps/ai-service && useradd --create-home --uid 10001 studyos
USER studyos
EXPOSE 8000
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000", "--no-access-log"]
