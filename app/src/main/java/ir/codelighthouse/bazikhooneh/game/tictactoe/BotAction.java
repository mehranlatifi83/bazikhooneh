package ir.codelighthouse.bazikhooneh.game.tictactoe;

public final class BotAction {
  private final int source;
  private final int destination;

  private BotAction(int source, int destination) {
    this.source = source;
    this.destination = destination;
  }

  public static BotAction place(int destination) {
    return new BotAction(-1, destination);
  }

  public static BotAction move(int source, int destination) {
    return new BotAction(source, destination);
  }

  public boolean isMovement() {
    return source >= 0;
  }

  public int getSource() {
    return source;
  }

  public int getDestination() {
    return destination;
  }
}
