package ir.codelighthouse.bazikhooneh.game.tictactoe;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Chooses placement and movement actions for the O player. */
public final class TicTacToeBot {
    private static final int HARD_SEARCH_DEPTH = 6;
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

    public BotAction chooseAction(TicTacToeGame game, BotDifficulty difficulty) {
        if (game.getStatus() != GameStatus.IN_PROGRESS) {
            return null;
        }
        Mark[] board = copyBoard(game);
        List<BotAction> actions = availableActions(board, Mark.O);
        if (actions.isEmpty()) {
            return null;
        }
        if (difficulty == BotDifficulty.EASY) {
            return chooseRandom(actions);
        }

        // Defensive priority requested for medium and hard: stop an immediate X win first.
        if (hasImmediateWin(board, Mark.X)) {
            for (BotAction action : actions) {
                Mark[] next = applyCopy(board, action, Mark.O);
                if (!hasImmediateWin(next, Mark.X)) {
                    return action;
                }
            }
        }

        for (BotAction action : actions) {
            if (winner(applyCopy(board, action, Mark.O)) == Mark.O) {
                return action;
            }
        }

        if (difficulty == BotDifficulty.MEDIUM) {
            return chooseRandom(actions);
        }
        return chooseBestAction(board, actions);
    }

    private BotAction chooseBestAction(Mark[] board, List<BotAction> actions) {
        int bestScore = Integer.MIN_VALUE;
        BotAction bestAction = actions.get(0);
        for (BotAction action : actions) {
            int score = minimax(applyCopy(board, action, Mark.O), Mark.X,
                    HARD_SEARCH_DEPTH - 1);
            if (score > bestScore) {
                bestScore = score;
                bestAction = action;
            }
        }
        return bestAction;
    }

    private int minimax(Mark[] board, Mark turn, int depth) {
        Mark winner = winner(board);
        if (winner == Mark.O) {
            return 100 + depth;
        }
        if (winner == Mark.X) {
            return -100 - depth;
        }
        if (depth == 0) {
            return heuristic(board);
        }

        List<BotAction> actions = availableActions(board, turn);
        if (actions.isEmpty()) {
            return turn == Mark.O ? -50 : 50;
        }
        int best = turn == Mark.O ? Integer.MIN_VALUE : Integer.MAX_VALUE;
        for (BotAction action : actions) {
            int score = minimax(applyCopy(board, action, turn), turn.opponent(), depth - 1);
            best = turn == Mark.O ? Math.max(best, score) : Math.min(best, score);
        }
        return best;
    }

    private int heuristic(Mark[] board) {
        int score = 0;
        for (int[] line : WINNING_LINES) {
            int x = 0;
            int o = 0;
            for (int cell : line) {
                if (board[cell] == Mark.X) {
                    x++;
                } else if (board[cell] == Mark.O) {
                    o++;
                }
            }
            if (x == 0) {
                score += o * o;
            }
            if (o == 0) {
                score -= x * x;
            }
        }
        return score;
    }

    private boolean hasImmediateWin(Mark[] board, Mark mark) {
        for (BotAction action : availableActions(board, mark)) {
            if (winner(applyCopy(board, action, mark)) == mark) {
                return true;
            }
        }
        return false;
    }

    private List<BotAction> availableActions(Mark[] board, Mark mark) {
        List<BotAction> actions = new ArrayList<>();
        int pieceCount = count(board, mark);
        if (pieceCount < TicTacToeGame.PIECES_PER_PLAYER) {
            for (int destination = 0; destination < board.length; destination++) {
                if (board[destination] == Mark.EMPTY) {
                    actions.add(BotAction.place(destination));
                }
            }
            return actions;
        }
        for (int source = 0; source < board.length; source++) {
            if (board[source] != mark) {
                continue;
            }
            for (int destination = 0; destination < board.length; destination++) {
                if (board[destination] == Mark.EMPTY
                        && TicTacToeGame.areAdjacent(source, destination)) {
                    actions.add(BotAction.move(source, destination));
                }
            }
        }
        return actions;
    }

    private Mark[] applyCopy(Mark[] board, BotAction action, Mark mark) {
        Mark[] result = board.clone();
        if (action.isMovement()) {
            result[action.getSource()] = Mark.EMPTY;
        }
        result[action.getDestination()] = mark;
        return result;
    }

    private BotAction chooseRandom(List<BotAction> actions) {
        return actions.get(random.nextInt(actions.size()));
    }

    private Mark[] copyBoard(TicTacToeGame game) {
        Mark[] board = new Mark[TicTacToeGame.CELL_COUNT];
        for (int index = 0; index < board.length; index++) {
            board[index] = game.getCell(index);
        }
        return board;
    }

    private int count(Mark[] board, Mark mark) {
        int count = 0;
        for (Mark cell : board) {
            if (cell == mark) {
                count++;
            }
        }
        return count;
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
