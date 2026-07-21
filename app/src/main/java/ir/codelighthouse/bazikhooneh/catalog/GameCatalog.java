package ir.codelighthouse.bazikhooneh.catalog;

import java.util.Collections;
import java.util.List;
import ir.codelighthouse.bazikhooneh.TicTacToeMenuActivity;
import ir.codelighthouse.bazikhooneh.TicTacToeGuideActivity;
import ir.codelighthouse.bazikhooneh.R;

public final class GameCatalog {
    private GameCatalog() { }

    public static List<GameDefinition> availableGames() {
        return Collections.singletonList(new GameDefinition(
                "three_piece_tic_tac_toe", R.string.tic_tac_toe_card,
                R.string.tic_tac_toe_description, TicTacToeMenuActivity.class,
                TicTacToeGuideActivity.class, null));
    }
}
