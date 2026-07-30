package ir.codelighthouse.bazikhooneh.feature.account;

import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import ir.codelighthouse.bazikhooneh.BuildConfig;
import ir.codelighthouse.bazikhooneh.R;
import ir.codelighthouse.bazikhooneh.account.AccountErrorMessages;
import ir.codelighthouse.bazikhooneh.account.AccountManagementClient;
import ir.codelighthouse.bazikhooneh.account.SessionStore;
import ir.codelighthouse.bazikhooneh.core.ui.NavigableActivity;
import org.json.JSONException;
import org.json.JSONObject;

public final class EmailVerificationActivity extends NavigableActivity {
  private AccountManagementClient client;
  private TextView status;

  @Override
  protected void onCreate(Bundle state) {
    super.onCreate(state);
    setContentView(R.layout.activity_email_verification);
    client =
        new AccountManagementClient(
            BuildConfig.API_BASE_URL, new SessionStore(this).token(), listener);
    status = findViewById(R.id.email_status);
    findViewById(R.id.request_email_code).setOnClickListener(v -> requestCode());
    findViewById(R.id.confirm_email).setOnClickListener(v -> confirm());
    findViewById(R.id.change_email).setOnClickListener(v -> showRequestStep());
  }

  private String text(int id) {
    return ((EditText) findViewById(id)).getText().toString().trim();
  }

  private void requestCode() {
    try {
      client.post(
          "request",
          "/api/v1/accounts/email/request/",
          new JSONObject().put("email", text(R.id.security_email)));
    } catch (JSONException ignored) {
    }
  }

  private void confirm() {
    try {
      client.post(
          "confirm",
          "/api/v1/accounts/email/confirm/",
          new JSONObject().put("code", text(R.id.security_code)));
    } catch (JSONException ignored) {
    }
  }

  private void showRequestStep() {
    findViewById(R.id.email_request_step).setVisibility(View.VISIBLE);
    findViewById(R.id.email_confirm_step).setVisibility(View.GONE);
    status.setText("");
  }

  private void showConfirmStep(JSONObject json) {
    findViewById(R.id.email_request_step).setVisibility(View.GONE);
    findViewById(R.id.email_confirm_step).setVisibility(View.VISIBLE);
    ((TextView) findViewById(R.id.email_code_destination))
        .setText(getString(R.string.code_sent_to, text(R.id.security_email)));
    String code = json.optString("development_code", "");
    if (!code.isEmpty()) ((EditText) findViewById(R.id.security_code)).setText(code);
    findViewById(R.id.security_code).requestFocus();
  }

  private final AccountManagementClient.Listener listener =
      new AccountManagementClient.Listener() {
        public void onSuccess(String operation, JSONObject json) {
          runOnUiThread(
              () -> {
                if ("request".equals(operation)) {
                  showConfirmStep(json);
                  status.setText(R.string.verification_sent);
                } else status.setText(R.string.changes_saved);
              });
        }

        public void onError(String error) {
          runOnUiThread(
              () ->
                  status.setText(AccountErrorMessages.get(EmailVerificationActivity.this, error)));
        }
      };
}
