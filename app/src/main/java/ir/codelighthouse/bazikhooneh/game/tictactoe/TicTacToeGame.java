package ir.codelighthouse.bazikhooneh.game.tictactoe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Pure Java implementation of the three-piece 3x3 game. */
public final class TicTacToeGame {
  public static final int BOARD_SIZE = 3;
  public static final int CELL_COUNT = BOARD_SIZE * BOARD_SIZE;
  public static final int PIECES_PER_PLAYER = 3;
  private static final int MOVEMENT_ACTION_OFFSET = 100;

  private static final int[][] WINNING_LINES = {
    {0, 1, 2}, {3, 4, 5}, {6, 7, 8},
    {0, 3, 6}, {1, 4, 7}, {2, 5, 8},
    {0, 4, 8}, {2, 4, 6}
  };

  private final Mark[] board = new Mark[CELL_COUNT];
  private final List<Integer> actionHistory = new ArrayList<>();
  private Mark currentPlayer;
  private GameStatus status;
  private GamePhase phase;

  public TicTacToeGame() {
    reset();
  }

  public MoveResult play(int cellIndex) {
    if (!isValidIndex(cellIndex)) {
      return MoveResult.OUT_OF_BOUNDS;
    }
    if (status != GameStatus.IN_PROGRESS) {
      return MoveResult.GAME_FINISHED;
    }
    if (phase != GamePhase.PLACEMENT) {
      return MoveResult.WRONG_PHASE;
    }
    if (board[cellIndex] != Mark.EMPTY) {
      return MoveResult.CELL_OCCUPIED;
    }

    board[cellIndex] = currentPlayer;
    actionHistory.add(cellIndex);
    finishTurn();
    if (status == GameStatus.IN_PROGRESS && countPieces() == PIECES_PER_PLAYER * 2) {
      phase = GamePhase.MOVEMENT;
    }
    return MoveResult.ACCEPTED;
  }

  public MoveResult move(int source, int destination) {
    if (!isValidIndex(source) || !isValidIndex(destination)) {
      return MoveResult.OUT_OF_BOUNDS;
    }
    if (status != GameStatus.IN_PROGRESS) {
      return MoveResult.GAME_FINISHED;
    }
    if (phase != GamePhase.MOVEMENT) {
      return MoveResult.WRONG_PHASE;
    }
    if (board[source] != currentPlayer) {
      return MoveResult.SOURCE_NOT_OWNED;
    }
    if (board[destination] != Mark.EMPTY) {
      return MoveResult.CELL_OCCUPIED;
    }
    board[source] = Mark.EMPTY;
    board[destination] = currentPlayer;
    actionHistory.add(encodeMovement(source, destination));
    finishTurn();
    return MoveResult.ACCEPTED;
  }

  private void finishTurn() {
    status = calculateStatus();
    if (status == GameStatus.IN_PROGRESS) {
      currentPlayer = currentPlayer.opponent();
    }
  }

  public void reset() {
    for (int index = 0; index < CELL_COUNT; index++) {
      board[index] = Mark.EMPTY;
    }
    actionHistory.clear();
    currentPlayer = Mark.X;
    status = GameStatus.IN_PROGRESS;
    phase = GamePhase.PLACEMENT;
  }

  public Mark getCell(int cellIndex) {
    if (!isValidIndex(cellIndex)) {
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

  public GamePhase getPhase() {
    return phase;
  }

  public List<Integer> getActionHistory() {
    return Collections.unmodifiableList(actionHistory);
  }

  public static boolean isMovementAction(int encodedAction) {
    return encodedAction >= MOVEMENT_ACTION_OFFSET;
  }

  public static int movementSource(int encodedAction) {
    return (encodedAction - MOVEMENT_ACTION_OFFSET) / CELL_COUNT;
  }

  public static int movementDestination(int encodedAction) {
    return (encodedAction - MOVEMENT_ACTION_OFFSET) % CELL_COUNT;
  }

  private static int encodeMovement(int source, int destination) {
    return MOVEMENT_ACTION_OFFSET + source * CELL_COUNT + destination;
  }

  private static boolean isValidIndex(int index) {
    return index >= 0 && index < CELL_COUNT;
  }

  private int countPieces() {
    int count = 0;
    for (Mark mark : board) {
      if (mark != Mark.EMPTY) {
        count++;
      }
    }
    return count;
  }

  private GameStatus calculateStatus() {
    for (int[] line : WINNING_LINES) {
      Mark first = board[line[0]];
      if (first != Mark.EMPTY && first == board[line[1]] && first == board[line[2]]) {
        return first == Mark.X ? GameStatus.X_WON : GameStatus.O_WON;
      }
    }
    return GameStatus.IN_PROGRESS;
  }
}
