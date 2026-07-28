import socket
import os
import struct
import uuid

from asgiref.sync import async_to_sync
from channels.layers import get_channel_layer
from django.conf import settings
from django.core.cache import cache
from django.db import connection
from django.http import JsonResponse
from django.views.decorators.http import require_GET


@require_GET
def liveness(request):
    return JsonResponse({"status": "ok"})


def database_ready():
    with connection.cursor() as cursor:
        cursor.execute("SELECT 1")
        return cursor.fetchone()[0] == 1


def cache_ready():
    key = f"health:{uuid.uuid4().hex}"
    cache.set(key, "ok", timeout=5)
    value = cache.get(key)
    cache.delete(key)
    return value == "ok"


def channel_layer_ready():
    layer = get_channel_layer()
    if layer is None:
        return False
    channel = async_to_sync(layer.new_channel)("health.")
    async_to_sync(layer.send)(channel, {"type": "health.check", "value": "ok"})
    message = async_to_sync(layer.receive)(channel)
    return message.get("value") == "ok"


def turn_ready():
    host = settings.TURN_HOST
    if not host:
        return False
    # A STUN binding request verifies the UDP listener used by TURN without
    # requiring or exposing application credentials.
    transaction_id = os.urandom(12)
    request = struct.pack("!HHI12s", 0x0001, 0, 0x2112A442, transaction_id)
    addresses = socket.getaddrinfo(
        host, settings.TURN_PORT, type=socket.SOCK_DGRAM)
    family, socket_type, protocol, _, address = addresses[0]
    with socket.socket(family, socket_type, protocol) as connection:
        connection.settimeout(1)
        connection.sendto(request, address)
        response, _ = connection.recvfrom(512)
    if len(response) < 20:
        return False
    message_type, _, magic_cookie, response_transaction = struct.unpack(
        "!HHI12s", response[:20])
    return (
        message_type == 0x0101
        and magic_cookie == 0x2112A442
        and response_transaction == transaction_id
    )


@require_GET
def readiness(request):
    checks = {}
    for name, check in (
        ("database", database_ready),
        ("cache", cache_ready),
        ("channel_layer", channel_layer_ready),
        ("turn", turn_ready),
    ):
        try:
            checks[name] = bool(check())
        except Exception:
            checks[name] = False
    core_ready = all(
        checks[name] for name in ("database", "cache", "channel_layer")
    )
    fully_ready = core_ready and checks["turn"]
    return JsonResponse(
        {"status": "ok" if fully_ready else "degraded", "checks": checks},
        status=200 if core_ready else 503,
    )
