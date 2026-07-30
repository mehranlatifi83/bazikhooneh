package ir.codelighthouse.bazikhooneh;

import android.content.Intent;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.TextView;
import ir.codelighthouse.bazikhooneh.account.*;
import org.json.*;

public final class DeleteAccountActivity extends NavigableActivity {
  private AccountManagementClient client;
  private SessionStore store;
  private TextView status;

  @Override
  protected void onCreate(Bundle state) {
    super.onCreate(state);
    setContentView(R.layout.activity_delete_account);
    store = new SessionStore(this);
    status = findViewById(R.id.delete_status);
    client = new AccountManagementClient(BuildConfig.API_BASE_URL, store.token(), listener);
    findViewById(R.id.delete_account).setOnClickListener(v -> submit());
  }

  private String text(int id) {
    return ((EditText) findViewById(id)).getText().toString().trim();
  }

  private void submit() {
    try {
      client.delete(
          "delete",
          "/api/v1/accounts/me/",
          new JSONObject()
              .put("confirmation", text(R.id.delete_confirmation))
              .put("current_password", text(R.id.delete_password)));
    } catch (JSONException ignored) {
    }
  }

  private final AccountManagementClient.Listener listener =
      new AccountManagementClient.Listener() {
        public void onSuccess(String op, JSONObject json) {
          runOnUiThread(
              () -> {
                store.clear();
                startActivity(
                    new Intent(DeleteAccountActivity.this, HomeActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK));
                finishAffinity();
              });
        }

        public void onError(String error) {
          runOnUiThread(
              () -> status.setText(AccountErrorMessages.get(DeleteAccountActivity.this, error)));
        }
      };
}
