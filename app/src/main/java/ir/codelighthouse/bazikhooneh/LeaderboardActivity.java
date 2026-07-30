package ir.codelighthouse.bazikhooneh;

import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import ir.codelighthouse.bazikhooneh.account.SessionStore;
import ir.codelighthouse.bazikhooneh.community.CommunityClient;
import org.json.JSONArray;
import org.json.JSONObject;

public final class LeaderboardActivity extends NavigableActivity {
  @Override
  protected void onCreate(Bundle state) {
    super.onCreate(state);
    setContentView(R.layout.activity_leaderboard);
    SessionStore store = new SessionStore(this);
    if (!store.isSignedIn()) {
      finish();
      return;
    }
    CommunityClient client = new CommunityClient(this, BuildConfig.API_BASE_URL, store.token());
    TextView own = findViewById(R.id.player_stats), status = findViewById(R.id.leaderboard_status);
    LinearLayout list = findViewById(R.id.leaderboard_list);
    client.stats(
        (data, error) ->
            runOnUiThread(
                () -> {
                  if (data != null) {
                    JSONObject tic = data.optJSONObject("tic_tac_toe"),
                        ludo = data.optJSONObject("ludo");
                    own.setText(
                        getString(
                            R.string.player_stats_value,
                            data.optInt("score"),
                            tic == null ? 0 : tic.optInt("played"),
                            tic == null ? 0 : tic.optInt("wins"),
                            ludo == null ? 0 : ludo.optInt("played"),
                            ludo == null ? 0 : ludo.optInt("wins")));
                  } else if (error != null) status.setText(error);
                }));
    client.leaderboard(
        (data, error) ->
            runOnUiThread(
                () -> {
                  findViewById(R.id.leaderboard_progress).setVisibility(View.GONE);
                  if (error != null) {
                    status.setText(error);
                    status.announceForAccessibility(error);
                    return;
                  }
                  JSONArray values = data.optJSONArray("results");
                  if (values == null || values.length() == 0) {
                    status.setText(R.string.empty_leaderboard);
                    return;
                  }
                  status.setText("");
                  for (int i = 0; i < values.length(); i++) {
                    JSONObject item = values.optJSONObject(i);
                    TextView row = new TextView(this);
                    row.setText(
                        getString(
                            R.string.leaderboard_item,
                            i + 1,
                            item.optString("display_name"),
                            item.optString("username"),
                            item.optInt("score"),
                            item.optInt("tic_tac_toe_wins"),
                            item.optInt("ludo_wins")));
                    row.setTextSize(17);
                    row.setPadding(12, 16, 12, 16);
                    row.setFocusable(true);
                    list.addView(row);
                  }
                }));
  }
}
