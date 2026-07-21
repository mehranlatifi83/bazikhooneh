from django.urls import path

from .views import (CreateRoomView,JoinRoomView,MatchHistoryView,CreateLudoRoomView,
                    JoinLudoRoomView,StartLudoRoomView)


urlpatterns = [
    path("rooms/", CreateRoomView.as_view(), name="create-room"),
    path("rooms/join/", JoinRoomView.as_view(), name="join-room"),
    path("matches/", MatchHistoryView.as_view(), name="match-history"),
    path("ludo/rooms/",CreateLudoRoomView.as_view(),name="ludo-create"),
    path("ludo/rooms/join/",JoinLudoRoomView.as_view(),name="ludo-join"),
    path("ludo/rooms/<str:code>/start/",StartLudoRoomView.as_view(),name="ludo-start"),
]
