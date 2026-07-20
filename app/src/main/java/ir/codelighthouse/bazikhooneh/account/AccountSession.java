package ir.codelighthouse.bazikhooneh.account;

import org.json.JSONException;
import org.json.JSONObject;

public final class AccountSession {
    public final String token;
    public final String username;
    public final String displayName;
    public final String avatarColor;

    private AccountSession(String token, JSONObject account) throws JSONException {
        this.token = token;
        username = account.getString("username");
        displayName = account.getString("display_name");
        avatarColor = account.optString("avatar_color", "#2E7D32");
    }

    public static AccountSession from(JSONObject json) throws JSONException {
        return new AccountSession(json.getString("access_token"), json.getJSONObject("account"));
    }
}
