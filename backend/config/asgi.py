import os

os.environ.setdefault("DJANGO_SETTINGS_MODULE", "config.settings")

from channels.routing import ProtocolTypeRouter, URLRouter  # noqa: E402
from django.core.asgi import get_asgi_application  # noqa: E402

django_asgi_application = get_asgi_application()

from games.routing import websocket_urlpatterns  # noqa: E402

application = ProtocolTypeRouter(
    {
        "http": django_asgi_application,
        # Native Android clients do not normally send a browser Origin header.
        # RoomConsumer authenticates every connection with its reconnect token.
        "websocket": URLRouter(websocket_urlpatterns),
    }
)
