import logging
import time

from asgiref.sync import sync_to_async
from django.conf import settings
from django.core.cache import cache

from accounts.models import hash_token


logger = logging.getLogger("bazikhooneh.security")


def bearer_token(scope):
    """Read credentials only from headers so proxies never log tokens in URLs."""
    for key, value in scope.get("headers", []):
        if key.lower() != b"authorization":
            continue
        authorization = value.decode("utf-8", errors="ignore")
        if authorization.startswith("Bearer "):
            return authorization[7:].strip()
    return ""


def client_ip(scope):
    client = scope.get("client")
    remote = client[0] if client else ""
    trusted = set(settings.TRUSTED_PROXY_IPS)
    if remote in trusted:
        for key, value in scope.get("headers", []):
            if key.lower() == b"x-forwarded-for":
                forwarded = value.decode("ascii", errors="ignore").split(",")[0].strip()
                if forwarded:
                    return forwarded
    return remote or "unknown"


def _increment_window(key, limit, timeout):
    if cache.add(key, 1, timeout=timeout):
        return True
    try:
        return cache.incr(key) <= limit
    except ValueError:
        cache.set(key, 1, timeout=timeout)
        return True


@sync_to_async
def connection_allowed(scope, token):
    ip_allowed = _increment_window(
        f"ws:connect:ip:{client_ip(scope)}", limit=30, timeout=60)
    credential_allowed = _increment_window(
        f"ws:connect:credential:{hash_token(token)}", limit=20, timeout=60)
    if not ip_allowed or not credential_allowed:
        logger.warning("websocket_connection_rate_limited")
    return ip_allowed and credential_allowed


@sync_to_async
def message_allowed(token, limit):
    allowed = _increment_window(
        f"ws:message:{hash_token(token)}", limit=limit, timeout=10)
    if not allowed:
        logger.warning("websocket_message_rate_limited")
    return allowed


@sync_to_async
def claim_action(token, action_id):
    if not action_id:
        return True
    return cache.add(
        f"ws:action:{hash_token(token)}:{action_id}", "pending", timeout=300)


@sync_to_async
def release_action(token, action_id):
    if action_id:
        cache.delete(f"ws:action:{hash_token(token)}:{action_id}")


@sync_to_async
def update_presence(kind, identity, channel_name, connected=True):
    key = f"ws:presence:{kind}:{identity}"
    now = time.time()
    connections = cache.get(key, {})
    if not isinstance(connections, dict):
        connections = {}
    connections = {
        channel: seen for channel, seen in connections.items()
        if now - float(seen) < 45
    }
    if connected:
        connections[channel_name] = now
    else:
        connections.pop(channel_name, None)
    if connections:
        cache.set(key, connections, timeout=50)
    else:
        cache.delete(key)
    return len(connections)


@sync_to_async
def has_active_presence(kind, identity):
    key = f"ws:presence:{kind}:{identity}"
    now = time.time()
    connections = cache.get(key, {})
    if not isinstance(connections, dict):
        return False
    active = {
        channel: seen for channel, seen in connections.items()
        if now - float(seen) < 45
    }
    if active:
        cache.set(key, active, timeout=50)
        return True
    cache.delete(key)
    return False
