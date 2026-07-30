package ir.codelighthouse.bazikhooneh.account;

import org.json.JSONException;
import org.json.JSONObject;

public final class AccountSession {
  public final String token;
  public final String refreshToken;
  public final long accessExpiresAt;
  public final String username;
  public final String displayName;
  public final String avatarColor;

  private AccountSession(String token, JSONObject account) throws JSONException {
    this.token = token;
    refreshToken = account.optString("_refresh_token");
    accessExpiresAt = account.optLong("_access_expires_at");
    username = account.getString("username");
    displayName = account.getString("display_name");
    avatarColor = account.optString("avatar_color", "#2E7D32");
  }

  public static AccountSession from(JSONObject json) throws JSONException {
    JSONObject account = json.getJSONObject("account");
    account.put("_refresh_token", json.optString("refresh_token"));
    account.put(
        "_access_expires_at",
        System.currentTimeMillis() + json.optLong("access_expires_in", 3600) * 1000L);
    return new AccountSession(json.getString("access_token"), account);
  }
}
