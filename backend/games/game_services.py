import secrets

from .models import (
    CommunityEvent,
    CommunityGameSession,
    LudoRoom,
    LudoSeat,
    Match,
    Player,
    Room,
    token_hash,
)
from .views import ludo_payload, player_payload


TIC_TAC_TOE = "three_piece_tic_tac_toe"
LUDO = "ludo"
SUPPORTED_GAMES = (TIC_TAC_TOE, LUDO)
GAME_CAPACITIES = {TIC_TAC_TOE: 2, LUDO: 4}


class GameServiceError(Exception):
    def __init__(self, code, status=400):
        super().__init__(code)
        self.code = code
        self.status = status


def is_supported(game_key):
    return game_key in SUPPORTED_GAMES


def game_capacity(game_key):
    return GAME_CAPACITIES[game_key]


def create_game_session(room, account, game_key, options):
    if game_key == TIC_TAC_TOE:
        legacy = Room.create_unique()
        player, token = Player.create_with_token(legacy, "X", account)
        session = CommunityGameSession.objects.create(
            room=room,
            game_key=game_key,
            created_by=account,
            tic_tac_toe_room=legacy,
        )
        return session, player_payload(legacy, player, token)
    if game_key == LUDO:
        legacy = LudoRoom.create_unique(account)
        legacy.game_state = {
            "third_six_penalty": bool(options.get("third_six_penalty", False))
        }
        legacy.save(update_fields=("game_state", "updated_at"))
        seat, token = LudoSeat.create_human(legacy, 0, account)
        session = CommunityGameSession.objects.create(
            room=room,
            game_key=game_key,
            created_by=account,
            ludo_room=legacy,
        )
        return session, ludo_payload(legacy, seat, token)
    raise GameServiceError("unsupported_game")


def join_game_session(session, account):
    if session.game_key == TIC_TAC_TOE:
        return _join_tic_tac_toe(session, account)
    if session.game_key == LUDO:
        return _join_ludo(session, account)
    raise GameServiceError("unsupported_game")


def create_quick_match(room, game_key, first_account, second_account):
    if game_key == TIC_TAC_TOE:
        legacy = Room.create_unique()
        first, first_token = Player.create_with_token(legacy, "X", first_account)
        second, second_token = Player.create_with_token(legacy, "O", second_account)
        legacy.state = Room.State.ACTIVE
        legacy.save(update_fields=("state", "updated_at"))
        Match.start_for_room(legacy)
        CommunityGameSession.objects.create(
            room=room,
            game_key=game_key,
            created_by=first_account,
            tic_tac_toe_room=legacy,
            state="active",
        )
        credentials = (
            player_payload(legacy, first, first_token),
            player_payload(legacy, second, second_token),
        )
    elif game_key == LUDO:
        legacy = LudoRoom.create_unique(first_account)
        first, first_token = LudoSeat.create_human(legacy, 0, first_account)
        second, second_token = LudoSeat.create_human(legacy, 1, second_account)
        CommunityGameSession.objects.create(
            room=room,
            game_key=game_key,
            created_by=first_account,
            ludo_room=legacy,
        )
        credentials = (
            ludo_payload(legacy, first, first_token),
            ludo_payload(legacy, second, second_token),
        )
    else:
        raise GameServiceError("unsupported_game")
    CommunityEvent.objects.create(
        room=room,
        actor=first_account,
        kind="game_selected",
        payload={"game_key": game_key},
    )
    return credentials


def _join_tic_tac_toe(session, account):
    legacy = session.tic_tac_toe_room
    player = legacy.players.filter(account=account).first()
    if player:
        token = secrets.token_urlsafe(32)
        player.reconnect_token_hash = token_hash(token)
        player.is_active = True
        player.save(update_fields=("reconnect_token_hash", "is_active", "last_seen_at"))
    else:
        if legacy.players.filter(is_active=True).count() >= 2:
            raise GameServiceError("game_full", 409)
        symbol = (
            "O" if legacy.players.filter(symbol="X", is_active=True).exists() else "X"
        )
        player, token = Player.create_with_token(legacy, symbol, account)
    if (
        legacy.players.filter(is_active=True).count() == 2
        and legacy.state == Room.State.WAITING
    ):
        legacy.state = Room.State.ACTIVE
        legacy.save(update_fields=("state", "updated_at"))
        Match.start_for_room(legacy)
        session.state = "active"
        session.save(update_fields=("state",))
    return player_payload(legacy, player, token)


def _join_ludo(session, account):
    legacy = session.ludo_room
    seat = legacy.seats.filter(account=account, is_bot=False).first()
    if seat:
        token = secrets.token_urlsafe(32)
        seat.reconnect_token_hash = token_hash(token)
        seat.active = True
        seat.save(update_fields=("reconnect_token_hash", "active", "last_seen_at"))
    else:
        used = set(legacy.seats.filter(active=True).values_list("color", flat=True))
        color = next((value for value in range(4) if value not in used), None)
        if color is None:
            raise GameServiceError("game_full", 409)
        seat, token = LudoSeat.create_human(legacy, color, account)
    return ludo_payload(legacy, seat, token)
