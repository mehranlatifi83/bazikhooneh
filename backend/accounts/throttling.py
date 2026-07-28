import logging

from django.conf import settings
from rest_framework.throttling import SimpleRateThrottle


logger = logging.getLogger("bazikhooneh.security")


def request_ip(request):
    remote = request.META.get("REMOTE_ADDR", "")
    forwarded = request.META.get("HTTP_X_FORWARDED_FOR", "").split(",")[0].strip()
    if remote in set(settings.TRUSTED_PROXY_IPS) and forwarded:
        return forwarded
    return remote or "unknown"


class IPRateThrottle(SimpleRateThrottle):
    scope = "ip"

    def get_cache_key(self, request, view):
        return self.cache_format % {
            "scope": self.scope,
            "ident": request_ip(request),
        }

    def throttle_failure(self):
        logger.warning("ip_rate_limit_exceeded")
        return super().throttle_failure()
