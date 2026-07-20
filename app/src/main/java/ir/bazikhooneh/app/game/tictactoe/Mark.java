package ir.bazikhooneh.app.game.tictactoe;

public enum Mark {
    EMPTY,
    X,
    O;

    public Mark opponent() {
        if (this == X) {
            return O;
        }
        if (this == O) {
            return X;
        }
        return EMPTY;
    }
}
