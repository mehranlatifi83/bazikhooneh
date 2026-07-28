package ir.codelighthouse.bazikhooneh.account;

import android.content.Context;
import ir.codelighthouse.bazikhooneh.security.SecurePreferences;

public final class SessionStore {
    private static final String PREFS = "account_session";
    private final Context context;
    private final SecurePreferences preferences;

    public SessionStore(Context context) {
        this.context = context.getApplicationContext();
        preferences = SecurePreferences.open(this.context, PREFS);
    }

    public boolean isSignedIn() { return !token().isEmpty(); }
    public String token() { return preferences.getString("account_token", ""); }
    public String refreshToken() { return preferences.getString("refresh_token", ""); }
    public long accessExpiresAt() {
        try { return Long.parseLong(preferences.getString("access_expires_at", "0")); }
        catch (NumberFormatException ignored) { return 0; }
    }
    public boolean needsRefresh() {
        return !refreshToken().isEmpty() && System.currentTimeMillis() >= accessExpiresAt() - 120_000L;
    }
    public String username() { return preferences.getString("username", ""); }
    public String displayName() { return preferences.getString("display_name", ""); }

    public void save(AccountSession session) {
        preferences.putString("account_token", session.token);
        preferences.putString("refresh_token", session.refreshToken);
        preferences.putString("access_expires_at", String.valueOf(session.accessExpiresAt));
        preferences.putString("username", session.username);
        preferences.putString("display_name", session.displayName);
    }

    public void updateDisplayName(String displayName) {
        preferences.putString("display_name", displayName);
    }
    public void updateUsername(String username) { preferences.putString("username", username); }

    public void clear() {
        preferences.clear();
        SecurePreferences.open(context, "online_session").clear();
        SecurePreferences.open(context, "ludo_online").clear();
    }
}
