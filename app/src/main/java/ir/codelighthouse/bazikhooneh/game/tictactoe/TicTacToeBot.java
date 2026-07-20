package ir.codelighthouse.bazikhooneh.game.tictactoe;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Chooses a move for the O player without depending on Android APIs. */
public final class TicTacToeBot {
    private static final int[][] WINNING_LINES = {
            {0, 1, 2}, {3, 4, 5}, {6, 7, 8},
            {0, 3, 6}, {1, 4, 7}, {2, 5, 8},
            {0, 4, 8}, {2, 4, 6}
    };

    private final Random random;

    public TicTacToeBot() {
        this(new Random());
    }

    TicTacToeBot(Random random) {
        this.random = random;
    }

    public int chooseMove(TicTacToeGame game, BotDifficulty difficulty) {
        Mark[] board = copyBoard(game);
        List<Integer> available = availableCells(board);
        if (available.isEmpty() || game.getStatus() != GameStatus.IN_PROGRESS) {
            return -1;
        }

        switch (difficulty) {
            case HARD:
                return chooseBestMove(board);
            case MEDIUM:
                int tacticalMove = findWinningMove(board, Mark.O);
                if (tacticalMove >= 0) {
                    return tacticalMove;
                }
                int blockingMove = findWinningMove(board, Mark.X);
                return blockingMove >= 0 ? blockingMove : chooseRandom(available);
            case EASY:
            default:
                return chooseRandom(available);
        }
    }

    private int chooseBestMove(Mark[] board) {
        int bestScore = Integer.MIN_VALUE;
        int bestMove = -1;
        for (int cell : availableCells(board)) {
            board[cell] = Mark.O;
            int score = minimax(board, false, 0);
            board[cell] = Mark.EMPTY;
            if (score > bestScore) {
                bestScore = score;
                bestMove = cell;
            }
        }
        return bestMove;
    }

    private int minimax(Mark[] board, boolean maximizing, int depth) {
        Mark winner = winner(board);
        if (winner == Mark.O) {
            return 10 - depth;
        }
        if (winner == Mark.X) {
            return depth - 10;
        }
        List<Integer> available = availableCells(board);
        if (available.isEmpty()) {
            return 0;
        }

        int bestScore = maximizing ? Integer.MIN_VALUE : Integer.MAX_VALUE;
        Mark mark = maximizing ? Mark.O : Mark.X;
        for (int cell : available) {
            board[cell] = mark;
            int score = minimax(board, !maximizing, depth + 1);
            board[cell] = Mark.EMPTY;
            bestScore = maximizing ? Math.max(bestScore, score) : Math.min(bestScore, score);
        }
        return bestScore;
    }

    private int findWinningMove(Mark[] board, Mark mark) {
        for (int cell : availableCells(board)) {
            board[cell] = mark;
            boolean wins = winner(board) == mark;
            board[cell] = Mark.EMPTY;
            if (wins) {
                return cell;
            }
        }
        return -1;
    }

    private int chooseRandom(List<Integer> available) {
        return available.get(random.nextInt(available.size()));
    }

    private Mark[] copyBoard(TicTacToeGame game) {
        Mark[] board = new Mark[TicTacToeGame.CELL_COUNT];
        for (int index = 0; index < board.length; index++) {
            board[index] = game.getCell(index);
        }
        return board;
    }

    private List<Integer> availableCells(Mark[] board) {
        List<Integer> available = new ArrayList<>();
        for (int index = 0; index < board.length; index++) {
            if (board[index] == Mark.EMPTY) {
                available.add(index);
            }
        }
        return available;
    }

    private Mark winner(Mark[] board) {
        for (int[] line : WINNING_LINES) {
            Mark first = board[line[0]];
            if (first != Mark.EMPTY && first == board[line[1]] && first == board[line[2]]) {
                return first;
            }
        }
        return Mark.EMPTY;
    }
}
