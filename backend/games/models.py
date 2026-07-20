import hashlib
import secrets
import string
import uuid

from django.db import models

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
            "room_state": self.state,
            "version": self.version,
            "rematch_x": self.rematch_x,
            "rematch_o": self.rematch_o,
            "outcome_reason": self.outcome_reason,
            **self.game().as_dict(),
        }


class Player(models.Model):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    room = models.ForeignKey(Room, related_name="players", on_delete=models.CASCADE)
    symbol = models.CharField(max_length=1)
    reconnect_token_hash = models.CharField(max_length=64, db_index=True)
    joined_at = models.DateTimeField(auto_now_add=True)
    last_seen_at = models.DateTimeField(auto_now=True)
    is_active = models.BooleanField(default=True)

    class Meta:
        constraints = [
            models.UniqueConstraint(fields=("room", "symbol"), name="unique_room_symbol")
        ]

    @classmethod
    def create_with_token(cls, room: Room, symbol: str):
        token = secrets.token_urlsafe(32)
        player = cls.objects.create(
            room=room,
            symbol=symbol,
            reconnect_token_hash=token_hash(token),
        )
        return player, token
