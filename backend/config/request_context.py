import contextvars


request_id_context = contextvars.ContextVar("request_id", default="-")
