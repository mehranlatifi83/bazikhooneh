package ir.codelighthouse.bazikhooneh.online;

import org.json.JSONException;
import org.json.JSONObject;

public final class OnlineGameState {
    public final String roomCode;
    public final String roomState;
    public final String board;
    public final String currentPlayer;
    public final String phase;
    public final String status;
    public final int version;
    public final boolean rematchX;
    public final boolean rematchO;
    public final String outcomeReason;

    private OnlineGameState(JSONObject json) throws JSONException {
        roomCode = json.getString("room_code");
        roomState = json.getString("room_state");
        board = json.getString("board");
        currentPlayer = json.getString("current_player");
        phase = json.getString("phase");
        status = json.getString("status");
        version = json.getInt("version");
        rematchX = json.optBoolean("rematch_x", false);
        rematchO = json.optBoolean("rematch_o", false);
        outcomeReason = json.optString("outcome_reason", "");
    }

    public static OnlineGameState from(JSONObject json) throws JSONException {
        return new OnlineGameState(json);
    }
}
