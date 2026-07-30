package ir.codelighthouse.bazikhooneh.online;

import org.json.JSONException;
import org.json.JSONObject;

public final class OnlineSession {
  public final String playerId;
  public final String symbol;
  public final String token;
  public final String websocketPath;
  public final OnlineGameState game;

  private OnlineSession(JSONObject json) throws JSONException {
    playerId = json.getString("player_id");
    symbol = json.getString("symbol");
    token = json.getString("reconnect_token");
    websocketPath = json.getString("websocket_path");
    game = OnlineGameState.from(json.getJSONObject("game"));
  }

  public static OnlineSession from(JSONObject json) throws JSONException {
    return new OnlineSession(json);
  }
}
