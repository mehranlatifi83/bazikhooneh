package ir.codelighthouse.bazikhooneh.game.tictactoe;

public enum MoveResult {
    ACCEPTED,
    CELL_OCCUPIED,
    GAME_FINISHED,
    OUT_OF_BOUNDS,
    WRONG_PHASE,
    SOURCE_NOT_OWNED,
    NOT_ADJACENT
}
