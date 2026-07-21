from django.urls import path

from .consumers import RoomConsumer,LudoRoomConsumer


websocket_urlpatterns = [
    path("ws/v1/rooms/<str:code>/", RoomConsumer.as_asgi()),
    path("ws/v1/ludo/<str:code>/",LudoRoomConsumer.as_asgi()),
]
