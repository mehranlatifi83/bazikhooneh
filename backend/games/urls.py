from django.urls import path

from .views import (CreateRoomView,JoinRoomView,MatchHistoryView,CreateLudoRoomView,
                    JoinLudoRoomView,StartLudoRoomView)
from .community import (CommunityCallModerationView, CommunityCallView, CommunityGameView, CommunityGameJoinView, CommunityInviteView,
    CommunityJoinRequestView, CommunityJoinView, CommunityLeaveView, CommunityMessageView,
    CommunityEventsView, CommunityMessagesView, CommunityModerationView,
    CommunityRoomsView, CommunityRoomView, IceServersView, LeaderboardView,
    MatchmakingView, PlayerStatsView)


urlpatterns = [
    path("rooms/", CreateRoomView.as_view(), name="create-room"),
    path("rooms/join/", JoinRoomView.as_view(), name="join-room"),
    path("matches/", MatchHistoryView.as_view(), name="match-history"),
    path("ludo/rooms/",CreateLudoRoomView.as_view(),name="ludo-create"),
    path("ludo/rooms/join/",JoinLudoRoomView.as_view(),name="ludo-join"),
    path("ludo/rooms/<str:code>/start/",StartLudoRoomView.as_view(),name="ludo-start"),
    path("community/rooms/", CommunityRoomsView.as_view(), name="community-rooms"),
    path("community/rooms/join/", CommunityJoinView.as_view(), name="community-join"),
    path("community/rooms/<str:code>/", CommunityRoomView.as_view(), name="community-room"),
    path("community/rooms/<str:code>/leave/", CommunityLeaveView.as_view(), name="community-leave"),
    path("community/rooms/<str:code>/messages/", CommunityMessagesView.as_view(), name="community-messages"),
    path("community/rooms/<str:code>/invite/", CommunityInviteView.as_view(), name="community-invite"),
    path("community/rooms/<str:code>/events/", CommunityEventsView.as_view(), name="community-events"),
    path("community/rooms/<str:code>/messages/<int:message_id>/", CommunityMessageView.as_view(), name="community-message"),
    path("community/rooms/<str:code>/join-requests/<int:request_id>/", CommunityJoinRequestView.as_view(), name="community-join-request"),
    path("community/rooms/<str:code>/moderate/", CommunityModerationView.as_view(), name="community-moderate"),
    path("community/rooms/<str:code>/call/", CommunityCallView.as_view(), name="community-call"),
    path("community/rooms/<str:code>/call/moderate/", CommunityCallModerationView.as_view(), name="community-call-moderate"),
    path("community/rooms/<str:code>/game/", CommunityGameView.as_view(), name="community-game"),
    path("community/rooms/<str:code>/game/join/", CommunityGameJoinView.as_view(), name="community-game-join"),
    path("webrtc/ice-servers/", IceServersView.as_view(), name="webrtc-ice-servers"),
    path("matchmaking/", MatchmakingView.as_view(), name="matchmaking"),
    path("stats/me/", PlayerStatsView.as_view(), name="player-stats"),
    path("leaderboard/", LeaderboardView.as_view(), name="leaderboard"),
]
