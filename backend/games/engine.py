from dataclasses import dataclass
from enum import StrEnum


class GameError(ValueError):
    pass


class Phase(StrEnum):
    PLACEMENT = "placement"
    MOVEMENT = "movement"


class Status(StrEnum):
    ACTIVE = "active"
    X_WON = "x_won"
    O_WON = "o_won"


WINNING_LINES = (
    (0, 1, 2), (3, 4, 5), (6, 7, 8),
    (0, 3, 6), (1, 4, 7), (2, 5, 8),
    (0, 4, 8), (2, 4, 6),
)


@dataclass(frozen=True)
class GameState:
    board: str = "........."
    current_player: str = "X"
    phase: Phase = Phase.PLACEMENT
    status: Status = Status.ACTIVE

    def apply(self, player: str, action: dict) -> "GameState":
        if self.status != Status.ACTIVE:
            raise GameError("game_finished")
        if player != self.current_player:
            raise GameError("not_your_turn")
        if player not in ("X", "O"):
            raise GameError("invalid_player")

        kind = action.get("kind")
        if kind == "place":
            return self._place(player, action.get("destination"))
        if kind == "move":
            return self._move(player, action.get("source"), action.get("destination"))
        raise GameError("invalid_action")

    def _place(self, player: str, destination) -> "GameState":
        if self.phase != Phase.PLACEMENT:
            raise GameError("wrong_phase")
        destination = self._index(destination)
        if self.board[destination] != ".":
            raise GameError("cell_occupied")
        if self.board.count(player) >= 3:
            raise GameError("all_pieces_placed")

        board = self._replace(destination, player)
        status = self._status(board)
        phase = Phase.MOVEMENT if board.count(".") == 3 and status == Status.ACTIVE else self.phase
        return GameState(board, self._opponent(player), phase, status)

    def _move(self, player: str, source, destination) -> "GameState":
        if self.phase != Phase.MOVEMENT:
            raise GameError("wrong_phase")
        source = self._index(source)
        destination = self._index(destination)
        if self.board[source] != player:
            raise GameError("source_not_owned")
        if self.board[destination] != ".":
            raise GameError("cell_occupied")

        board = list(self.board)
        board[source] = "."
        board[destination] = player
        board = "".join(board)
        return GameState(board, self._opponent(player), self.phase, self._status(board))

    def as_dict(self) -> dict:
        return {
            "board": self.board,
            "current_player": self.current_player,
            "phase": self.phase.value,
            "status": self.status.value,
        }

    def _replace(self, index: int, value: str) -> str:
        board = list(self.board)
        board[index] = value
        return "".join(board)

    @staticmethod
    def _index(value) -> int:
        if isinstance(value, bool) or not isinstance(value, int) or not 0 <= value < 9:
            raise GameError("invalid_cell")
        return value

    @staticmethod
    def _opponent(player: str) -> str:
        return "O" if player == "X" else "X"

    @staticmethod
    def _status(board: str) -> Status:
        for first, second, third in WINNING_LINES:
            if board[first] != "." and board[first] == board[second] == board[third]:
                return Status.X_WON if board[first] == "X" else Status.O_WON
        return Status.ACTIVE
