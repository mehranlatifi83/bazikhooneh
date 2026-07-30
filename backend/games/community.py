# Compatibility exports for community HTTP and WebSocket entry points.

from .community_consumers import CommunityConsumer as CommunityConsumer
from .community_views import (
    CommunityCallModerationView as CommunityCallModerationView,
    CommunityCallView as CommunityCallView,
    CommunityEventsView as CommunityEventsView,
    CommunityGameJoinView as CommunityGameJoinView,
    CommunityGameView as CommunityGameView,
    CommunityInviteView as CommunityInviteView,
    CommunityJoinRequestView as CommunityJoinRequestView,
    CommunityJoinView as CommunityJoinView,
    CommunityLeaveView as CommunityLeaveView,
    CommunityMessageView as CommunityMessageView,
    CommunityMessagesView as CommunityMessagesView,
    CommunityModerationView as CommunityModerationView,
    CommunityRoomsView as CommunityRoomsView,
    CommunityRoomView as CommunityRoomView,
    IceServersView as IceServersView,
    LeaderboardView as LeaderboardView,
    MatchmakingView as MatchmakingView,
    PlayerStatsView as PlayerStatsView,
)
