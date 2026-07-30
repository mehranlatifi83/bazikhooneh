package ir.codelighthouse.bazikhooneh;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Button;
import android.widget.LinearLayout;
import android.content.Context;
import ir.codelighthouse.bazikhooneh.account.SessionStore;
import ir.codelighthouse.bazikhooneh.account.AccountClient;
import ir.codelighthouse.bazikhooneh.account.AccountSession;
import ir.codelighthouse.bazikhooneh.account.NotificationSync;
import ir.codelighthouse.bazikhooneh.catalog.GameCatalog;
import ir.codelighthouse.bazikhooneh.catalog.GameDefinition;
import ir.codelighthouse.bazikhooneh.navigation.AppNavigator;
import ir.codelighthouse.bazikhooneh.navigation.RoomLink;

public final class HomeActivity extends Activity {
    private static final String APP_PREFS = "app_state";
    @Override protected void attachBaseContext(Context base) { super.attachBaseContext(AppDisplay.wrap(base)); }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (!LanguageManager.hasSelection(this)) {
            startActivity(new Intent(this, LanguageActivity.class).setData(getIntent().getData()));
            finish();
            return;
        }
        if (AppDisplay.reduceMotion(this)) getWindow().setWindowAnimations(0);
        if (getActionBar() != null) getActionBar().hide();
        setContentView(R.layout.activity_home);
        renderGames();
        findViewById(R.id.open_profile).setOnClickListener(v -> {
            Class<?> destination = new SessionStore(this).isSignedIn()
                    ? ProfileActivity.class : LoginActivity.class;
            startActivity(new Intent(this, destination));
        });
        findViewById(R.id.open_settings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        findViewById(R.id.open_community_rooms).setOnClickListener(v ->
                AppNavigator.openRooms(this, ""));
        findViewById(R.id.open_guide).setOnClickListener(v ->
                startActivity(new Intent(this, GuideActivity.class)));
        findViewById(R.id.open_friends).setOnClickListener(v -> {
            Class<?> destination = new SessionStore(this).isSignedIn() ? FriendsActivity.class : LoginActivity.class;
            startActivity(new Intent(this, destination));
        });
        findViewById(R.id.open_notifications).setOnClickListener(v -> {
            Class<?> destination = new SessionStore(this).isSignedIn() ? NotificationsActivity.class : LoginActivity.class;
            startActivity(new Intent(this, destination));
        });
        findViewById(R.id.open_leaderboard).setOnClickListener(v -> {
            Class<?> destination = new SessionStore(this).isSignedIn() ? LeaderboardActivity.class : LoginActivity.class;
            startActivity(new Intent(this, destination));
        });
        boolean routed = routeDeepLink(getIntent());
        if (!routed && !getSharedPreferences(APP_PREFS, MODE_PRIVATE).getBoolean("guide_seen", false)) {
            getSharedPreferences(APP_PREFS, MODE_PRIVATE).edit().putBoolean("guide_seen", true).apply();
            startActivity(new Intent(this, GuideActivity.class));
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        routeDeepLink(intent);
    }

    private boolean routeDeepLink(Intent intent) {
        String code = RoomLink.parseCode(intent == null ? null : intent.getData());
        if (code.isEmpty()) return false;
        intent.setData(null);
        AppNavigator.openRoom(this, code);
        return true;
    }

    @Override protected void onResume() {
        super.onResume();
        String displayName = new SessionStore(this).displayName();
        TextView welcome = findViewById(R.id.home_welcome);
        welcome.setText(displayName.isEmpty() ? getString(R.string.home_guest)
                : getString(R.string.home_welcome, displayName));
        ((Button) findViewById(R.id.open_profile)).setText(
                new SessionStore(this).isSignedIn() ? R.string.profile_title : R.string.account_login);
        refreshSessionIfNeeded();
    }

    private void refreshSessionIfNeeded() {
        SessionStore store = new SessionStore(this);
        if (store.isSignedIn() && store.refreshToken().isEmpty()) {
            refreshOrUpgrade(store, true);
            return;
        }
        if (!store.needsRefresh()) {
            NotificationSync.refresh(this);
            NotificationJobService.schedule(this);
            return;
        }
        refreshOrUpgrade(store, false);
    }

    private void refreshOrUpgrade(SessionStore store, boolean upgrade) {
        AccountClient client = new AccountClient(BuildConfig.API_BASE_URL, new AccountClient.Listener() {
            @Override public void onSession(AccountSession session) {
                store.save(session);
                runOnUiThread(() -> {
                    NotificationSync.refresh(HomeActivity.this);
                    NotificationJobService.schedule(HomeActivity.this);
                });
            }
            @Override public void onLoggedOut() {}
            @Override public void onError(String error) {
                if ("invalid_refresh_token".equals(error)
                        || "refresh_token_reused".equals(error)
                        || "expired_refresh_token".equals(error)) {
                    store.clear();
                }
            }
        });
        if (upgrade) client.upgrade(store.token()); else client.refresh(store.refreshToken());
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
