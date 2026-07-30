package ir.codelighthouse.bazikhooneh.game.tictactoe;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Chooses placement and movement actions for the O player. */
public final class TicTacToeBot {
  private static final int HARD_SEARCH_DEPTH = 8;
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

    // Winning now is always better than defending a threat.
    for (BotAction action : actions) {
      if (winner(applyCopy(board, action, Mark.O)) == Mark.O) {
        return action;
      }
    }

    actions = bestDefensiveActions(board, actions);
    if (difficulty == BotDifficulty.MEDIUM) {
      return chooseMediumAction(board, actions);
    }
    return chooseBestAction(board, actions);
  }

  private List<BotAction> bestDefensiveActions(Mark[] board, List<BotAction> actions) {
    if (countImmediateWins(board, Mark.X) == 0) {
      return actions;
    }
    int fewestReplies = Integer.MAX_VALUE;
    List<BotAction> best = new ArrayList<>();
    for (BotAction action : actions) {
      int opponentWins = countImmediateWins(applyCopy(board, action, Mark.O), Mark.X);
      if (opponentWins < fewestReplies) {
        fewestReplies = opponentWins;
        best.clear();
        best.add(action);
      } else if (opponentWins == fewestReplies) {
        best.add(action);
      }
    }
    return best;
  }

  private BotAction chooseMediumAction(Mark[] board, List<BotAction> actions) {
    int bestScore = Integer.MIN_VALUE;
    List<BotAction> best = new ArrayList<>();
    for (BotAction action : actions) {
      Mark[] next = applyCopy(board, action, Mark.O);
      int score =
          countImmediateWins(next, Mark.O) * 20
              - countImmediateWins(next, Mark.X) * 25
              + heuristic(next);
      if (score > bestScore) {
        bestScore = score;
        best.clear();
        best.add(action);
      } else if (score == bestScore) {
        best.add(action);
      }
    }
    return chooseRandom(best);
  }

  private BotAction chooseBestAction(Mark[] board, List<BotAction> actions) {
    int bestScore = Integer.MIN_VALUE;
    BotAction bestAction = actions.get(0);
    Map<String, Integer> cache = new HashMap<>();
    for (BotAction action : actions) {
      int score =
          minimax(
              applyCopy(board, action, Mark.O),
              Mark.X,
              HARD_SEARCH_DEPTH - 1,
              Integer.MIN_VALUE,
              Integer.MAX_VALUE,
              cache);
      if (score > bestScore) {
        bestScore = score;
        bestAction = action;
      }
    }
    return bestAction;
  }

  private int minimax(
      Mark[] board, Mark turn, int depth, int alpha, int beta, Map<String, Integer> cache) {
    Mark winner = winner(board);
    if (winner == Mark.O) {
      return 100 + depth;
    }
    if (winner == Mark.X) {
      return -100 - depth;
    }
    if (depth == 0) {
      return heuristic(board)
          + countImmediateWins(board, Mark.O) * 15
          - countImmediateWins(board, Mark.X) * 18;
    }

    String key = stateKey(board, turn, depth);
    Integer cached = cache.get(key);
    if (cached != null) {
      return cached;
    }

    List<BotAction> actions = availableActions(board, turn);
    if (actions.isEmpty()) {
      return turn == Mark.O ? -50 : 50;
    }
    int best = turn == Mark.O ? Integer.MIN_VALUE : Integer.MAX_VALUE;
    boolean pruned = false;
    for (BotAction action : actions) {
      int score =
          minimax(applyCopy(board, action, turn), turn.opponent(), depth - 1, alpha, beta, cache);
      if (turn == Mark.O) {
        best = Math.max(best, score);
        alpha = Math.max(alpha, best);
      } else {
        best = Math.min(best, score);
        beta = Math.min(beta, best);
      }
      if (beta <= alpha) {
        pruned = true;
        break;
      }
    }
    if (!pruned) {
      cache.put(key, best);
    }
    return best;
  }

  private String stateKey(Mark[] board, Mark turn, int depth) {
    StringBuilder key = new StringBuilder(board.length + 3);
    for (Mark mark : board) {
      key.append(mark.ordinal());
    }
    key.append(':').append(turn.ordinal()).append(':').append(depth);
    return key.toString();
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

  private int countImmediateWins(Mark[] board, Mark mark) {
    int wins = 0;
    for (BotAction action : availableActions(board, mark)) {
      if (winner(applyCopy(board, action, mark)) == mark) {
        wins++;
      }
    }
    return wins;
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
        if (board[destination] == Mark.EMPTY) {
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
