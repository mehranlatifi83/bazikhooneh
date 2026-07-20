package ir.codelighthouse.bazikhooneh;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;

public final class HomeActivity extends Activity {
    private static final String ACCOUNT_PREFS = "account_session";
    private static final String APP_PREFS = "app_state";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_home);
        findViewById(R.id.open_tic_tac_toe).setOnClickListener(v ->
                startActivity(new Intent(this, MainActivity.class)));
        findViewById(R.id.open_profile).setOnClickListener(v ->
                startActivity(new Intent(this, ProfileActivity.class)));
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
    }
}
