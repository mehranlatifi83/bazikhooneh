import json
import logging
from datetime import datetime, timezone

from .request_context import request_id_context


class JsonFormatter(logging.Formatter):
    """Emit one JSON object per line for journald and log collectors."""

    standard_attributes = set(logging.LogRecord("", 0, "", 0, "", (), None).__dict__)

    def format(self, record):
        payload = {
            "timestamp": datetime.now(timezone.utc).isoformat(),
            "level": record.levelname,
            "logger": record.name,
            "message": record.getMessage(),
            "request_id": request_id_context.get(),
        }
        for key, value in record.__dict__.items():
            if key not in self.standard_attributes and key not in {
                "message", "asctime", "request_id", "request", "request_body"
            }:
                try:
                    json.dumps(value)
                    payload[key] = value
                except (TypeError, ValueError):
                    payload[key] = str(value)
        if record.exc_info:
            payload["exception"] = self.formatException(record.exc_info)
        return json.dumps(payload, ensure_ascii=False, separators=(",", ":"))
