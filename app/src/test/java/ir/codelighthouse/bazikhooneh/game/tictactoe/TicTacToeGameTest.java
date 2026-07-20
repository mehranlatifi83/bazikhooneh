package ir.codelighthouse.bazikhooneh.game.tictactoe;

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
    public void newGameStartsInPlacementPhaseWithX() {
        assertEquals(GamePhase.PLACEMENT, game.getPhase());
        assertEquals(GameStatus.IN_PROGRESS, game.getStatus());
        assertEquals(Mark.X, game.getCurrentPlayer());
    }

    @Test
    public void acceptedPlacementChangesTurn() {
        assertEquals(MoveResult.ACCEPTED, game.play(4));
        assertEquals(Mark.X, game.getCell(4));
        assertEquals(Mark.O, game.getCurrentPlayer());
    }

    @Test
    public void occupiedCellIsRejectedWithoutChangingTurn() {
        game.play(4);
        assertEquals(MoveResult.CELL_OCCUPIED, game.play(4));
        assertEquals(Mark.O, game.getCurrentPlayer());
        assertEquals(1, game.getActionHistory().size());
    }

    @Test
    public void detectsWinDuringPlacement() {
        place(0, 3, 1, 4, 2);
        assertEquals(GameStatus.X_WON, game.getStatus());
    }

    @Test
    public void switchesToMovementAfterSixPiecesWithoutWin() {
        enterMovementPhase();
        assertEquals(GamePhase.MOVEMENT, game.getPhase());
        assertEquals(Mark.X, game.getCurrentPlayer());
        assertEquals(MoveResult.WRONG_PHASE, game.play(6));
    }

    @Test
    public void movementRequiresOwnPieceAndAllowsAnyEmptyDestination() {
        enterMovementPhase();
        assertEquals(MoveResult.SOURCE_NOT_OWNED, game.move(1, 4));
        assertEquals(MoveResult.ACCEPTED, game.move(0, 6));
        assertEquals(Mark.EMPTY, game.getCell(0));
        assertEquals(Mark.X, game.getCell(6));
        assertEquals(Mark.O, game.getCurrentPlayer());
    }

    @Test
    public void detectsWinAfterMovement() {
        place(0, 3, 1, 5, 4, 8);
        game.move(4, 2);
        assertEquals(GameStatus.X_WON, game.getStatus());
    }

    @Test
    public void movementHistoryCanBeDecoded() {
        enterMovementPhase();
        game.move(7, 4);
        int action = game.getActionHistory().get(6);
        assertTrue(TicTacToeGame.isMovementAction(action));
        assertEquals(7, TicTacToeGame.movementSource(action));
        assertEquals(4, TicTacToeGame.movementDestination(action));
    }

    @Test
    public void resetClearsBoardHistoryAndPhase() {
        enterMovementPhase();
        game.reset();
        assertEquals(GamePhase.PLACEMENT, game.getPhase());
        assertEquals(Mark.X, game.getCurrentPlayer());
        assertTrue(game.getActionHistory().isEmpty());
        assertEquals(Mark.EMPTY, game.getCell(4));
    }

    private void enterMovementPhase() {
        place(0, 1, 2, 3, 7, 8);
    }

    private void place(int... cells) {
        for (int cell : cells) {
            game.play(cell);
        }
    }
}
