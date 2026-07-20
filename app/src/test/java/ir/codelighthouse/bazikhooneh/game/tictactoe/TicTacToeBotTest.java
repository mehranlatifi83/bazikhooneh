package ir.codelighthouse.bazikhooneh.game.tictactoe;

import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TicTacToeBotTest {
    @Test
    public void easyBotChoosesAnAvailableCell() {
        TicTacToeGame game = gameAfter(0, 4, 8);
        int move = new TicTacToeBot(new Random(1)).chooseMove(game, BotDifficulty.EASY);
        assertTrue(move >= 0 && move < TicTacToeGame.CELL_COUNT);
        assertEquals(Mark.EMPTY, game.getCell(move));
    }

    @Test
    public void mediumBotTakesWinningMove() {
        TicTacToeGame game = gameAfter(0, 3, 1, 4, 8);
        assertEquals(5, new TicTacToeBot().chooseMove(game, BotDifficulty.MEDIUM));
    }

    @Test
    public void mediumBotBlocksImmediateLoss() {
        TicTacToeGame game = gameAfter(0, 4, 1);
        assertEquals(2, new TicTacToeBot().chooseMove(game, BotDifficulty.MEDIUM));
    }

    @Test
    public void hardBotNeverAllowsForcedWinFromOpening() {
        TicTacToeBot bot = new TicTacToeBot();
        for (int firstMove = 0; firstMove < TicTacToeGame.CELL_COUNT; firstMove++) {
            TicTacToeGame game = gameAfter(firstMove);
            int response = bot.chooseMove(game, BotDifficulty.HARD);
            assertTrue(response >= 0);
            assertEquals(Mark.EMPTY, game.getCell(response));
        }
    }

    @Test
    public void botReturnsMinusOneAfterGameEnds() {
        TicTacToeGame game = gameAfter(0, 3, 1, 4, 2);
        assertEquals(-1, new TicTacToeBot().chooseMove(game, BotDifficulty.HARD));
    }

    private TicTacToeGame gameAfter(int... moves) {
        TicTacToeGame game = new TicTacToeGame();
        for (int move : moves) {
            game.play(move);
        }
        return game;
    }
}
