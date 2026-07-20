from django.urls import path

from .views import CreateRoomView, JoinRoomView, MatchHistoryView


urlpatterns = [
    path("rooms/", CreateRoomView.as_view(), name="create-room"),
    path("rooms/join/", JoinRoomView.as_view(), name="join-room"),
    path("matches/", MatchHistoryView.as_view(), name="match-history"),
]
