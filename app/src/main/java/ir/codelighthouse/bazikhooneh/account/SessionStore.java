package ir.codelighthouse.bazikhooneh.account;

import android.content.Context;
import android.content.SharedPreferences;

public final class SessionStore {
    private static final String PREFS = "account_session";
    private final SharedPreferences preferences;

    public SessionStore(Context context) {
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public boolean isSignedIn() { return !token().isEmpty(); }
    public String token() { return preferences.getString("account_token", ""); }
    public String username() { return preferences.getString("username", ""); }
    public String displayName() { return preferences.getString("display_name", ""); }

    public void save(AccountSession session) {
        preferences.edit().putString("account_token", session.token)
                .putString("username", session.username)
                .putString("display_name", session.displayName).apply();
    }

    public void updateDisplayName(String displayName) {
        preferences.edit().putString("display_name", displayName).apply();
    }

    public void clear() { preferences.edit().clear().apply(); }
}
