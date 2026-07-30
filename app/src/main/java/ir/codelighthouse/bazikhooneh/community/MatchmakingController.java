package ir.codelighthouse.bazikhooneh.community;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

public final class MatchmakingController {
    public interface Listener {
        void onUpdate(JSONObject data, String error);
    }

    private static final long POLL_DELAY_MS = 2_000L;
    private final CommunityClient client;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean active;

    public MatchmakingController(CommunityClient client) {
        this.client = client;
    }

    public void start(String gameKey, Listener listener) {
        cancel(false);
        active = true;
        client.quickMatch(gameKey, (data, error) -> handle(data, error, listener));
    }

    public void cancel(boolean notifyServer) {
        active = false;
        handler.removeCallbacksAndMessages(null);
        if (notifyServer) client.cancelMatchmaking((data, error) -> { });
    }

    private void handle(JSONObject data, String error, Listener listener) {
        if (!active) return;
        boolean matched = data != null && "matched".equals(data.optString("status"));
        listener.onUpdate(data, error);
        if (error != null || matched) {
            active = false;
            return;
        }
        handler.postDelayed(() ->
                client.matchmakingStatus((next, nextError) ->
                        handle(next, nextError, listener)), POLL_DELAY_MS);
    }
}
