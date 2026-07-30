import logging
import re
import time
import uuid

from .request_context import request_id_context


logger = logging.getLogger("bazikhooneh.request")
VALID_REQUEST_ID = re.compile(r"^[A-Za-z0-9._-]{1,64}$")


class RequestContextMiddleware:
    def __init__(self, get_response):
        self.get_response = get_response

    def __call__(self, request):
        supplied = request.headers.get("X-Request-ID", "")
        request_id = (
            supplied if VALID_REQUEST_ID.fullmatch(supplied) else uuid.uuid4().hex
        )
        request.request_id = request_id
        context_token = request_id_context.set(request_id)
        started = time.monotonic()
        try:
            response = self.get_response(request)
        except Exception:
            logger.exception(
                "unhandled_request_exception",
                extra={"method": request.method, "path": request.path},
            )
            raise
        finally:
            duration_ms = round((time.monotonic() - started) * 1000, 2)
            # The response may not exist when an exception is re-raised.
            if "response" not in locals():
                request_id_context.reset(context_token)

        response["X-Request-ID"] = request_id
        logger.info(
            "request_completed",
            extra={
                "method": request.method,
                "path": request.path,
                "status": response.status_code,
                "duration_ms": duration_ms,
                "account_id": str(request.user.id)
                if getattr(request, "user", None) and request.user.is_authenticated
                else None,
            },
        )
        request_id_context.reset(context_token)
        return response
