package ir.codelighthouse.bazikhooneh.game.tictactoe;

import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class TicTacToeBotTest {
    @Test
    public void easyBotChoosesAnAvailablePlacement() {
        TicTacToeGame game = gameAfter(0, 4, 8);
        BotAction action = new TicTacToeBot(new Random(1))
                .chooseAction(game, BotDifficulty.EASY);
        assertNotNull(action);
        assertFalse(action.isMovement());
        assertEquals(Mark.EMPTY, game.getCell(action.getDestination()));
    }

    @Test
    public void mediumBotBlocksBeforeTakingItsOwnWin() {
        TicTacToeGame game = gameAfter(0, 3, 1);
        BotAction action = new TicTacToeBot().chooseAction(game, BotDifficulty.MEDIUM);
        assertEquals(2, action.getDestination());
    }

    @Test
    public void mediumBotTakesWinWhenThereIsNoImmediateThreat() {
        TicTacToeGame game = gameAfter(0, 3, 2, 4, 7);
        BotAction action = new TicTacToeBot().chooseAction(game, BotDifficulty.MEDIUM);
        assertEquals(5, action.getDestination());
    }

    @Test
    public void hardBotReturnsLegalMovementAction() {
        TicTacToeGame game = gameAfter(0, 1, 2, 3, 7, 8);
        assertEquals(MoveResult.ACCEPTED, game.move(7, 4));
        BotAction action = new TicTacToeBot().chooseAction(game, BotDifficulty.HARD);
        assertNotNull(action);
        assertTrue(action.isMovement());
        assertEquals(Mark.O, game.getCell(action.getSource()));
        assertEquals(Mark.EMPTY, game.getCell(action.getDestination()));
        assertTrue(TicTacToeGame.areAdjacent(action.getSource(), action.getDestination()));
    }

    @Test
    public void botReturnsNullAfterGameEnds() {
        TicTacToeGame game = gameAfter(0, 3, 1, 4, 2);
        assertNull(new TicTacToeBot().chooseAction(game, BotDifficulty.HARD));
    }

    private TicTacToeGame gameAfter(int... placements) {
        TicTacToeGame game = new TicTacToeGame();
        for (int placement : placements) {
            game.play(placement);
        }
        return game;
    }
}
