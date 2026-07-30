package ir.codelighthouse.bazikhooneh;

import android.os.Bundle;
import android.widget.EditText;
import android.widget.TextView;
import ir.codelighthouse.bazikhooneh.account.*;
import java.time.*;
import org.json.*;

public final class UsernameChangeActivity extends NavigableActivity {
  private AccountManagementClient client;
  private TextView status;

  @Override
  protected void onCreate(Bundle s) {
    super.onCreate(s);
    setContentView(R.layout.activity_username_change);
    SessionStore store = new SessionStore(this);
    client = new AccountManagementClient(BuildConfig.API_BASE_URL, store.token(), listener);
    status = findViewById(R.id.username_status);
    findViewById(R.id.change_username).setOnClickListener(v -> submit());
    client.get("profile", "/api/v1/accounts/me/");
  }

  private String text(int id) {
    return ((EditText) findViewById(id)).getText().toString().trim();
  }

  private void submit() {
    try {
      client.post(
          "change",
          "/api/v1/accounts/username/",
          new JSONObject()
              .put("username", text(R.id.new_username))
              .put("current_password", text(R.id.username_password)));
    } catch (JSONException ignored) {
    }
  }

  private String remaining(String value) {
    try {
      long hours =
          Math.max(
              1,
              Duration.between(Instant.now(), OffsetDateTime.parse(value).toInstant()).toHours());
      long days = hours / 24;
      return days > 0
          ? getString(R.string.username_remaining_days, days)
          : getString(R.string.username_remaining_hours, hours);
    } catch (Exception ignored) {
      return value;
    }
  }

  private final AccountManagementClient.Listener listener =
      new AccountManagementClient.Listener() {
        public void onSuccess(String op, JSONObject j) {
          runOnUiThread(
              () -> {
                if ("profile".equals(op)) {
                  ((EditText) findViewById(R.id.new_username)).setHint(j.optString("username"));
                  String n = j.optString("next_username_change_at", "");
                  if (!n.isEmpty() && !"null".equals(n))
                    status.setText(getString(R.string.username_next_change, remaining(n)));
                } else {
                  new SessionStore(UsernameChangeActivity.this)
                      .updateUsername(j.optString("username"));
                  status.setText(R.string.changes_saved);
                }
              });
        }

        public void onError(String e) {
          runOnUiThread(
              () -> status.setText(AccountErrorMessages.get(UsernameChangeActivity.this, e)));
        }
      };
}
