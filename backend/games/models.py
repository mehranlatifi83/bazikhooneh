import hashlib
import secrets
import string
import uuid

from django.db import models
from django.utils import timezone

from .engine import GameState, Phase, Status


ROOM_CODE_ALPHABET = string.ascii_uppercase + string.digits


def token_hash(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def generate_room_code() -> str:
    return "".join(secrets.choice(ROOM_CODE_ALPHABET) for _ in range(6))


class Room(models.Model):
    class State(models.TextChoices):
        WAITING = "waiting", "Waiting"
        ACTIVE = "active", "Active"
        FINISHED = "finished", "Finished"
        CLOSED = "closed", "Closed"

    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    game_key = models.CharField(max_length=40, default="three_piece_tic_tac_toe", db_index=True)
    code = models.CharField(max_length=6, unique=True, db_index=True)
    state = models.CharField(max_length=16, choices=State.choices, default=State.WAITING)
    board = models.CharField(max_length=9, default=".........")
    current_player = models.CharField(max_length=1, default="X")
    phase = models.CharField(max_length=16, default="placement")
    game_status = models.CharField(max_length=16, default="active")
    version = models.PositiveIntegerField(default=0)
    rematch_x = models.BooleanField(default=False)
    rematch_o = models.BooleanField(default=False)
    next_starter = models.CharField(max_length=1, default="O")
    outcome_reason = models.CharField(max_length=24, blank=True, default="")
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    @classmethod
    def create_unique(cls):
        for _ in range(20):
            code = generate_room_code()
            if not cls.objects.filter(code=code).exists():
                return cls.objects.create(code=code)
        raise RuntimeError("Could not generate a unique room code")

    def game(self) -> GameState:
        return GameState(
            board=self.board,
            current_player=self.current_player,
            phase=Phase(self.phase),
            status=Status(self.game_status),
        )

    def apply_game(self, game: GameState):
        self.board = game.board
        self.current_player = game.current_player
        self.phase = game.phase.value
        self.game_status = game.status.value
        self.version += 1
        if game.status.value != "active":
            self.state = self.State.FINISHED

    def request_rematch(self, symbol: str):
        if symbol == "X":
            self.rematch_x = True
        else:
            self.rematch_o = True
        if self.rematch_x and self.rematch_o:
            self.board = "........."
            self.current_player = self.next_starter
            self.next_starter = "O" if self.next_starter == "X" else "X"
            self.phase = Phase.PLACEMENT.value
            self.game_status = Status.ACTIVE.value
            self.state = self.State.ACTIVE
            self.rematch_x = False
            self.rematch_o = False
            self.outcome_reason = ""
            self.version += 1

    def close_for_player(self, symbol: str):
        self.state = self.State.CLOSED
        self.outcome_reason = f"{symbol.lower()}_left"
        self.rematch_x = False
        self.rematch_o = False
        self.version += 1

    def public_state(self) -> dict:
        return {
            "room_code": self.code,
            "game_key": self.game_key,
            "room_state": self.state,
            "version": self.version,
            "rematch_x": self.rematch_x,
            "rematch_o": self.rematch_o,
            "outcome_reason": self.outcome_reason,
            **self.game().as_dict(),
            "players": [
                {
                    "symbol": player.symbol,
                    "username": player.account.username if player.account else "",
                    "display_name": player.account.display_name if player.account else "",
                }
                for player in self.players.select_related("account").filter(is_active=True).order_by("symbol")
            ],
        }


class Player(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    room = models.ForeignKey(Room, related_name="players", on_delete=models.CASCADE)
    account = models.ForeignKey(
        "accounts.Account", related_name="room_players", on_delete=models.PROTECT, null=True
    )
    symbol = models.CharField(max_length=1)
    reconnect_token_hash = models.CharField(max_length=64, db_index=True)
    joined_at = models.DateTimeField(auto_now_add=True)
    last_seen_at = models.DateTimeField(auto_now=True)
    is_active = models.BooleanField(default=True)

    class Meta:
        constraints = [
            models.UniqueConstraint(fields=("room", "symbol"), name="unique_room_symbol"),
            models.UniqueConstraint(fields=("room", "account"), name="unique_room_account"),
        ]

    @classmethod
    def create_with_token(cls, room: Room, symbol: str, account=None):
        token = secrets.token_urlsafe(32)
        player = cls.objects.create(
            room=room,
            account=account,
            symbol=symbol,
            reconnect_token_hash=token_hash(token),
        )
        return player, token


class Match(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    room = models.ForeignKey(Room, related_name="matches", on_delete=models.CASCADE)
    game_key = models.CharField(max_length=40, default="three_piece_tic_tac_toe", db_index=True)
    round_number = models.PositiveIntegerField(default=1)
    x_account = models.ForeignKey(
        "accounts.Account", related_name="matches_as_x", on_delete=models.PROTECT
    )
    o_account = models.ForeignKey(
        "accounts.Account", related_name="matches_as_o", on_delete=models.PROTECT
    )
    winner = models.ForeignKey(
        "accounts.Account", related_name="matches_won", on_delete=models.PROTECT, null=True, blank=True
    )
    outcome = models.CharField(max_length=24, blank=True, default="")
    started_at = models.DateTimeField(auto_now_add=True)
    finished_at = models.DateTimeField(null=True, blank=True)

    class Meta:
        constraints = [
            models.UniqueConstraint(fields=("room", "round_number"), name="unique_room_round")
        ]
        ordering = ("-started_at",)

    @classmethod
    def start_for_room(cls, room):
        players = {player.symbol: player for player in room.players.select_related("account")}
        if (not players.get("X") or not players.get("O")
                or players["X"].account is None or players["O"].account is None):
            return None
        round_number = room.matches.count() + 1
        return cls.objects.create(
            room=room, game_key=room.game_key, round_number=round_number,
            x_account=players["X"].account, o_account=players["O"].account,
        )

    def finish(self, outcome, winner=None):
        if self.finished_at is not None:
            return
        self.outcome = outcome
        self.winner = winner
        self.finished_at = timezone.now()
        self.save(update_fields=("outcome", "winner", "finished_at"))
