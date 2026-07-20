package ir.codelighthouse.bazikhooneh.game.tictactoe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Pure Java implementation of classic 3x3 Tic-Tac-Toe. */
public final class TicTacToeGame {
    public static final int BOARD_SIZE = 3;
    public static final int CELL_COUNT = BOARD_SIZE * BOARD_SIZE;

    private static final int[][] WINNING_LINES = {
            {0, 1, 2}, {3, 4, 5}, {6, 7, 8},
            {0, 3, 6}, {1, 4, 7}, {2, 5, 8},
            {0, 4, 8}, {2, 4, 6}
    };

    private final Mark[] board = new Mark[CELL_COUNT];
    private final List<Integer> moveHistory = new ArrayList<>();
    private Mark currentPlayer;
    private GameStatus status;

    public TicTacToeGame() {
        reset();
    }

    public MoveResult play(int cellIndex) {
        if (cellIndex < 0 || cellIndex >= CELL_COUNT) {
            return MoveResult.OUT_OF_BOUNDS;
        }
        if (status != GameStatus.IN_PROGRESS) {
            return MoveResult.GAME_FINISHED;
        }
        if (board[cellIndex] != Mark.EMPTY) {
            return MoveResult.CELL_OCCUPIED;
        }

        board[cellIndex] = currentPlayer;
        moveHistory.add(cellIndex);
        status = calculateStatus();
        if (status == GameStatus.IN_PROGRESS) {
            currentPlayer = currentPlayer.opponent();
        }
        return MoveResult.ACCEPTED;
    }

    public void reset() {
        for (int index = 0; index < CELL_COUNT; index++) {
            board[index] = Mark.EMPTY;
        }
        moveHistory.clear();
        currentPlayer = Mark.X;
        status = GameStatus.IN_PROGRESS;
    }

    public Mark getCell(int cellIndex) {
        if (cellIndex < 0 || cellIndex >= CELL_COUNT) {
            throw new IndexOutOfBoundsException("Cell index must be between 0 and 8");
        }
        return board[cellIndex];
    }

    public Mark getCurrentPlayer() {
        return currentPlayer;
    }

    public GameStatus getStatus() {
        return status;
    }

    public List<Integer> getMoveHistory() {
        return Collections.unmodifiableList(moveHistory);
    }

    private GameStatus calculateStatus() {
        for (int[] line : WINNING_LINES) {
            Mark first = board[line[0]];
            if (first != Mark.EMPTY && first == board[line[1]] && first == board[line[2]]) {
                return first == Mark.X ? GameStatus.X_WON : GameStatus.O_WON;
            }
        }
        return moveHistory.size() == CELL_COUNT ? GameStatus.DRAW : GameStatus.IN_PROGRESS;
    }
}
