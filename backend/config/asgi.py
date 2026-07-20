import os

os.environ.setdefault("DJANGO_SETTINGS_MODULE", "config.settings")

from channels.routing import ProtocolTypeRouter, URLRouter
from django.core.asgi import get_asgi_application

django_asgi_application = get_asgi_application()

from games.routing import websocket_urlpatterns

application = ProtocolTypeRouter(
    {
        "http": django_asgi_application,
        # Native Android clients do not normally send a browser Origin header.
        # RoomConsumer authenticates every connection with its reconnect token.
        "websocket": URLRouter(websocket_urlpatterns),
    }
)
