package ir.codelighthouse.bazikhooneh.catalog;

import java.util.Arrays;
import java.util.List;
import ir.codelighthouse.bazikhooneh.TicTacToeMenuActivity;
import ir.codelighthouse.bazikhooneh.TicTacToeGuideActivity;
import ir.codelighthouse.bazikhooneh.R;
import ir.codelighthouse.bazikhooneh.LudoMenuActivity;
import ir.codelighthouse.bazikhooneh.LudoGuideActivity;

public final class GameCatalog {
    private GameCatalog() { }

    public static List<GameDefinition> availableGames() {
        return Arrays.asList(new GameDefinition(
                "three_piece_tic_tac_toe", R.string.tic_tac_toe_card,
                R.string.tic_tac_toe_description, TicTacToeMenuActivity.class,
                TicTacToeGuideActivity.class, null),new GameDefinition(
                "ludo",R.string.ludo_card,R.string.ludo_description,LudoMenuActivity.class,LudoGuideActivity.class,null));
    }
}
