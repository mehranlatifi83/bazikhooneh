package ir.codelighthouse.bazikhooneh.catalog;

import java.util.Arrays;
import java.util.List;
import java.util.ArrayList;
import ir.codelighthouse.bazikhooneh.TicTacToeMenuActivity;
import ir.codelighthouse.bazikhooneh.TicTacToeGuideActivity;
import ir.codelighthouse.bazikhooneh.R;
import ir.codelighthouse.bazikhooneh.feature.ludo.LudoMenuActivity;
import ir.codelighthouse.bazikhooneh.feature.ludo.LudoGuideActivity;

public final class GameCatalog {
    private GameCatalog() { }

    public static List<GameDefinition> availableGames() {
        return Arrays.asList(new GameDefinition(
                "three_piece_tic_tac_toe", R.string.tic_tac_toe_card,
                R.string.tic_tac_toe_description, TicTacToeMenuActivity.class,
                TicTacToeGuideActivity.class, null, true), new GameDefinition(
                "ludo", R.string.ludo_card, R.string.ludo_description,
                LudoMenuActivity.class, LudoGuideActivity.class, null, true));
    }

    public static List<GameDefinition> onlineGames() {
        List<GameDefinition> games = new ArrayList<>();
        for (GameDefinition game : availableGames()) {
            if (game.supportsOnlinePlay) games.add(game);
        }
        return games;
    }

    public static GameDefinition find(String id) {
        for (GameDefinition game : availableGames()) {
            if (game.id.equals(id)) return game;
        }
        return null;
    }
}
