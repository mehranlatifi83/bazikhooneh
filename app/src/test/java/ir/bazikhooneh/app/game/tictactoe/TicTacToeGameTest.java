package ir.bazikhooneh.app.game.tictactoe;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TicTacToeGameTest {
    private TicTacToeGame game;

    @Before
    public void setUp() {
        game = new TicTacToeGame();
    }

    @Test
    public void newGameStartsEmptyWithX() {
        assertEquals(GameStatus.IN_PROGRESS, game.getStatus());
        assertEquals(Mark.X, game.getCurrentPlayer());
        for (int index = 0; index < TicTacToeGame.CELL_COUNT; index++) {
            assertEquals(Mark.EMPTY, game.getCell(index));
        }
    }

    @Test
    public void acceptedMovePlacesMarkAndChangesTurn() {
        assertEquals(MoveResult.ACCEPTED, game.play(4));
        assertEquals(Mark.X, game.getCell(4));
        assertEquals(Mark.O, game.getCurrentPlayer());
    }

    @Test
    public void occupiedCellIsRejectedWithoutChangingTurn() {
        game.play(4);
        assertEquals(MoveResult.CELL_OCCUPIED, game.play(4));
        assertEquals(Mark.O, game.getCurrentPlayer());
        assertEquals(1, game.getMoveHistory().size());
    }

    @Test
    public void outOfBoundsMoveIsRejected() {
        assertEquals(MoveResult.OUT_OF_BOUNDS, game.play(-1));
        assertEquals(MoveResult.OUT_OF_BOUNDS, game.play(9));
    }

    @Test
    public void detectsRowWin() {
        playMoves(0, 3, 1, 4, 2);
        assertEquals(GameStatus.X_WON, game.getStatus());
    }

    @Test
    public void detectsColumnWin() {
        playMoves(0, 1, 3, 2, 6);
        assertEquals(GameStatus.X_WON, game.getStatus());
    }

    @Test
    public void detectsDiagonalWin() {
        playMoves(0, 1, 4, 2, 8);
        assertEquals(GameStatus.X_WON, game.getStatus());
    }

    @Test
    public void detectsDraw() {
        playMoves(0, 1, 2, 4, 3, 5, 7, 6, 8);
        assertEquals(GameStatus.DRAW, game.getStatus());
    }

    @Test
    public void moveAfterFinishedGameIsRejected() {
        playMoves(0, 3, 1, 4, 2);
        assertEquals(MoveResult.GAME_FINISHED, game.play(8));
    }

    @Test
    public void resetClearsBoardAndHistory() {
        playMoves(0, 1, 4);
        game.reset();
        assertEquals(Mark.X, game.getCurrentPlayer());
        assertEquals(GameStatus.IN_PROGRESS, game.getStatus());
        assertTrue(game.getMoveHistory().isEmpty());
        assertEquals(Mark.EMPTY, game.getCell(4));
    }

    private void playMoves(int... moves) {
        for (int move : moves) {
            game.play(move);
        }
    }
}
