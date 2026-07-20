package ir.codelighthouse.bazikhooneh;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Button;
import android.widget.LinearLayout;
import ir.codelighthouse.bazikhooneh.account.SessionStore;
import ir.codelighthouse.bazikhooneh.catalog.GameCatalog;
import ir.codelighthouse.bazikhooneh.catalog.GameDefinition;

public final class HomeActivity extends Activity {
    private static final String ACCOUNT_PREFS = "account_session";
    private static final String APP_PREFS = "app_state";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_home);
        renderGames();
        findViewById(R.id.open_profile).setOnClickListener(v -> {
            Class<?> destination = new SessionStore(this).isSignedIn()
                    ? ProfileActivity.class : LoginActivity.class;
            startActivity(new Intent(this, destination));
        });
        findViewById(R.id.open_settings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        findViewById(R.id.open_guide).setOnClickListener(v ->
                startActivity(new Intent(this, GuideActivity.class)));
        if (getIntent().getData() != null && "room".equals(getIntent().getData().getHost())) {
            String code = getIntent().getData().getLastPathSegment();
            if (code != null && code.length() == 6) {
                startActivity(new Intent(this, MainActivity.class).putExtra("room_code", code));
            }
        }
        if (!getSharedPreferences(APP_PREFS, MODE_PRIVATE).getBoolean("guide_seen", false)) {
            getSharedPreferences(APP_PREFS, MODE_PRIVATE).edit().putBoolean("guide_seen", true).apply();
            startActivity(new Intent(this, GuideActivity.class));
        }
    }

    @Override protected void onResume() {
        super.onResume();
        String displayName = getSharedPreferences(ACCOUNT_PREFS, MODE_PRIVATE)
                .getString("display_name", "");
        TextView welcome = findViewById(R.id.home_welcome);
        welcome.setText(displayName.isEmpty() ? getString(R.string.home_guest)
                : getString(R.string.home_welcome, displayName));
        ((Button) findViewById(R.id.open_profile)).setText(
                new SessionStore(this).isSignedIn() ? R.string.profile_title : R.string.account_login);
    }

    private void renderGames() {
        LinearLayout catalog = findViewById(R.id.game_catalog);
        catalog.removeAllViews();
        int margin = Math.round(12 * getResources().getDisplayMetrics().density);
        for (GameDefinition game : GameCatalog.availableGames()) {
            Button card = new Button(this);
            card.setAllCaps(false);
            card.setText(getString(game.titleRes) + "\n" + getString(game.descriptionRes));
            card.setTextSize(18);
            card.setMinHeight(Math.round(88 * getResources().getDisplayMetrics().density));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            params.topMargin = margin;
            card.setLayoutParams(params);
            card.setOnClickListener(v -> startActivity(new Intent(this, game.activity)));
            catalog.addView(card);
        }
    }
}
