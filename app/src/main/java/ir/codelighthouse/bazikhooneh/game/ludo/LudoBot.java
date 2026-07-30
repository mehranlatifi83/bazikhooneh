package ir.codelighthouse.bazikhooneh.game.ludo;

import java.util.List;
import java.util.Random;

public final class LudoBot {
  private final Random random;

  public LudoBot() {
    this(new Random());
  }

  LudoBot(Random random) {
    this.random = random;
  }

  public int choose(LudoGame game, List<Integer> legal) {
    int player = game.currentPlayer();
    for (int piece : legal)
      if (game.progress(player, piece) + game.die() == LudoGame.FINISH) return piece;
    for (int piece : legal) {
      int next =
          game.progress(player, piece) == LudoGame.HOME
              ? 0
              : game.progress(player, piece) + game.die();
      int global = LudoGame.globalPosition(player, next);
      if (global >= 0 && !LudoGame.isSafe(global))
        for (int other = 0; other < LudoGame.PLAYERS; other++)
          if (other != player)
            for (int target = 0; target < LudoGame.PIECES; target++)
              if (LudoGame.globalPosition(other, game.progress(other, target)) == global)
                return piece;
    }
    for (int piece : legal) if (game.progress(player, piece) == LudoGame.HOME) return piece;
    return legal.get(random.nextInt(legal.size()));
  }
}
