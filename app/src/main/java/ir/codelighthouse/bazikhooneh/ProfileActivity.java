package ir.codelighthouse.bazikhooneh;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import java.util.Locale;
import ir.codelighthouse.bazikhooneh.account.ProfileClient;
import ir.codelighthouse.bazikhooneh.account.AccountClient;
import ir.codelighthouse.bazikhooneh.account.AccountSession;
import ir.codelighthouse.bazikhooneh.account.SessionStore;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public final class ProfileActivity extends Activity {
    private static final String[] COLORS = {"#2E7D32", "#C62828", "#1565C0", "#6A1B9A", "#EF6C00", "#455A64"};
    private EditText displayName;
    private Spinner avatarColor;
    private TextView username;
    private TextView stats;
    private TextView history;
    private View avatarPreview;
    private ProfileClient client;
    private SessionStore sessionStore;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_profile);
        displayName = findViewById(R.id.profile_display_name);
        avatarColor = findViewById(R.id.profile_avatar_color);
        username = findViewById(R.id.profile_username);
        stats = findViewById(R.id.profile_stats);
        history = findViewById(R.id.profile_history);
        avatarPreview = findViewById(R.id.profile_avatar_preview);
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this,
                R.array.avatar_colors, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        avatarColor.setAdapter(adapter);
        sessionStore = new SessionStore(this);
        String token = sessionStore.token();
        if (token.isEmpty()) {
            findViewById(R.id.profile_content).setVisibility(View.GONE);
            findViewById(R.id.profile_signed_out).setVisibility(View.VISIBLE);
            return;
        }
        client = new ProfileClient(BuildConfig.API_BASE_URL, token, listener);
        findViewById(R.id.profile_save).setOnClickListener(v -> saveProfile());
        findViewById(R.id.profile_logout).setOnClickListener(v -> logout(token));
        client.load();
    }

    private void saveProfile() {
        String name = displayName.getText().toString().trim();
        if (name.isEmpty()) return;
        client.update(name, COLORS[avatarColor.getSelectedItemPosition()]);
    }

    private final ProfileClient.Listener listener = new ProfileClient.Listener() {
        @Override public void onProfile(JSONObject profile) {
            runOnUiThread(() -> renderProfile(profile));
        }
        @Override public void onHistory(JSONObject value) {
            runOnUiThread(() -> renderHistory(value));
        }
        @Override public void onError() {
            runOnUiThread(() -> findViewById(R.id.profile_root).announceForAccessibility(
                    getString(R.string.profile_load_error)));
        }
    };

    private void renderProfile(JSONObject profile) {
        try {
            String name = profile.getString("display_name");
            String user = profile.getString("username");
            String color = profile.getString("avatar_color");
            JSONObject values = profile.getJSONObject("stats");
            displayName.setText(name);
            username.setText(getString(R.string.profile_username_value, user));
            stats.setText(getString(R.string.profile_stats_value, values.getInt("played"),
                    values.getInt("wins"), values.getInt("losses")));
            int colorIndex = 0;
            for (int i = 0; i < COLORS.length; i++) if (COLORS[i].equals(color)) colorIndex = i;
            avatarColor.setSelection(colorIndex);
            avatarPreview.setBackgroundColor(Color.parseColor(color));
            sessionStore.updateDisplayName(name);
        } catch (JSONException ignored) { }
    }

    private void renderHistory(JSONObject response) {
        try {
            JSONArray results = response.getJSONArray("results");
            if (results.length() == 0) {
                history.setText(R.string.profile_no_matches);
                return;
            }
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < results.length(); i++) {
                JSONObject match = results.getJSONObject(i);
                JSONObject opponent = match.getJSONObject("opponent");
                String result = "win".equals(match.getString("result"))
                        ? getString(R.string.match_win) : getString(R.string.match_loss);
                if (i > 0) text.append("\n");
                text.append(getString(R.string.match_history_item, result,
                        opponent.getString("display_name"), match.getInt("round")));
            }
            history.setText(text.toString());
        } catch (JSONException ignored) { }
    }

    private void logout(String token) {
        new AccountClient(BuildConfig.API_BASE_URL, new AccountClient.Listener() {
            @Override public void onSession(AccountSession session) { }
            @Override public void onLoggedOut() {
                runOnUiThread(() -> { sessionStore.clear(); finish(); });
            }
            @Override public void onError(String error) {
                runOnUiThread(() -> { sessionStore.clear(); finish(); });
            }
        }).logout(token);
    }
}
