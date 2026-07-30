package ir.codelighthouse.bazikhooneh.feature.account;

import android.os.Bundle;
import android.widget.EditText;
import android.widget.TextView;
import ir.codelighthouse.bazikhooneh.BuildConfig;
import ir.codelighthouse.bazikhooneh.R;
import ir.codelighthouse.bazikhooneh.account.*;
import ir.codelighthouse.bazikhooneh.core.ui.NavigableActivity;
import org.json.*;

public final class PasswordChangeActivity extends NavigableActivity {
  private AccountManagementClient client;
  private TextView status;

  @Override
  protected void onCreate(Bundle s) {
    super.onCreate(s);
    setContentView(R.layout.activity_password_change);
    SessionStore store = new SessionStore(this);
    client = new AccountManagementClient(BuildConfig.API_BASE_URL, store.token(), listener);
    status = findViewById(R.id.password_status);
    findViewById(R.id.change_password).setOnClickListener(v -> submit());
  }

  private String text(int id) {
    return ((EditText) findViewById(id)).getText().toString();
  }

  private void submit() {
    try {
      client.post(
          "change",
          "/api/v1/accounts/password/",
          new JSONObject()
              .put("current_password", text(R.id.current_password))
              .put("new_password", text(R.id.new_password)));
    } catch (JSONException ignored) {
    }
  }

  private final AccountManagementClient.Listener listener =
      new AccountManagementClient.Listener() {
        public void onSuccess(String o, JSONObject j) {
          runOnUiThread(() -> status.setText(R.string.password_changed_other_sessions));
        }

        public void onError(String e) {
          runOnUiThread(
              () -> status.setText(AccountErrorMessages.get(PasswordChangeActivity.this, e)));
        }
      };
}
