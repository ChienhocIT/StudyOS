import json
import logging
import time
from contextlib import contextmanager

from opentelemetry import trace
from prometheus_client import Counter, Histogram

LOGGER = logging.getLogger("studyos.ai")
RUNS = Counter(
    "studyos_ai_runs_total",
    "AI computation outcomes",
    ["feature", "status", "provider"],
)
DURATION = Histogram(
    "studyos_ai_duration_seconds", "AI computation duration", ["feature"]
)
WORKER_EVENTS = Counter(
    "studyos_worker_events_total", "Worker delivery outcomes", ["queue", "outcome"]
)


@contextmanager
def operation(feature: str, provider: str, trace_id: str):
    start, status = time.monotonic(), "success"
    with trace.get_tracer("studyos.ai").start_as_current_span(feature) as span:
        span.set_attribute("studyos.trace_id", trace_id)
        span.set_attribute("studyos.provider", provider)
        try:
            yield
        except BaseException:
            status = "failed"
            raise
        finally:
            elapsed = time.monotonic() - start
            RUNS.labels(feature, status, provider).inc()
            DURATION.labels(feature).observe(elapsed)
            # No user text, URLs, authorization tokens, exception repr, or provider bodies.
            LOGGER.info(
                json.dumps(
                    {
                        "service": "studyos-ai",
                        "feature": feature,
                        "status": status,
                        "provider": provider,
                        "traceId": trace_id,
                        "latencyMs": round(elapsed * 1000),
                    }
                )
            )
