from django.urls import path

from .consumers import RoomConsumer


websocket_urlpatterns = [
    path("ws/v1/rooms/<str:code>/", RoomConsumer.as_asgi()),
]
