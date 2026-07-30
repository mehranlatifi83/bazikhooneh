package ir.codelighthouse.bazikhooneh;

import android.app.AlertDialog;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import ir.codelighthouse.bazikhooneh.account.AccountErrorMessages;
import ir.codelighthouse.bazikhooneh.account.AccountManagementClient;
import ir.codelighthouse.bazikhooneh.account.SessionStore;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.json.JSONArray;
import org.json.JSONObject;

public final class SessionsActivity extends NavigableActivity {
  private AccountManagementClient client;
  private TextView status;
  private LinearLayout list;

  @Override
  protected void onCreate(Bundle state) {
    super.onCreate(state);
    setContentView(R.layout.activity_sessions);
    SessionStore store = new SessionStore(this);
    client = new AccountManagementClient(BuildConfig.API_BASE_URL, store.token(), listener);
    status = findViewById(R.id.sessions_status);
    list = findViewById(R.id.sessions_list);
    findViewById(R.id.revoke_sessions).setOnClickListener(v -> confirmRevokeAll());
    findViewById(R.id.sessions_retry).setOnClickListener(v -> load());
    load();
  }

  private void confirmRevokeAll() {
    new AlertDialog.Builder(this)
        .setTitle(R.string.revoke_other_sessions)
        .setMessage(R.string.revoke_sessions_confirmation)
        .setNegativeButton(R.string.cancel_action, null)
        .setPositiveButton(
            R.string.confirm_action,
            (dialog, which) -> client.delete("revoke_all", "/api/v1/accounts/sessions/"))
        .show();
  }

  private void confirmRevoke(JSONObject session) {
    String device = session.optString("device_name", getString(R.string.unknown_device));
    new AlertDialog.Builder(this)
        .setTitle(R.string.revoke_session)
        .setMessage(getString(R.string.revoke_session_confirmation, device))
        .setNegativeButton(R.string.cancel_action, null)
        .setPositiveButton(
            R.string.confirm_action,
            (dialog, which) ->
                client.delete(
                    "revoke_one", "/api/v1/accounts/sessions/" + session.optString("id") + "/"))
        .show();
  }

  private void load() {
    findViewById(R.id.sessions_loading).setVisibility(View.VISIBLE);
    findViewById(R.id.sessions_retry).setVisibility(View.GONE);
    client.get("list", "/api/v1/accounts/sessions/");
  }

  private String date(String raw) {
    try {
      return DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm")
          .withZone(ZoneId.systemDefault())
          .format(Instant.parse(raw));
    } catch (Exception error) {
      return raw;
    }
  }

  private void render(JSONObject response) {
    findViewById(R.id.sessions_loading).setVisibility(View.GONE);
    list.removeAllViews();
    JSONArray results = response.optJSONArray("results");
    status.setText(
        getString(R.string.active_sessions_count, results == null ? 0 : results.length()));
    for (int index = 0; results != null && index < results.length(); index++) {
      JSONObject session = results.optJSONObject(index);
      boolean current = session.optBoolean("current");
      String device = session.optString("device_name");
      if (device.isEmpty()) device = getString(R.string.unknown_device);
      String version = session.optString("app_version");

      LinearLayout card = new LinearLayout(this);
      card.setOrientation(LinearLayout.VERTICAL);
      card.setPadding(0, 16, 0, 16);
      TextView details = new TextView(this);
      details.setText(
          getString(
              current ? R.string.current_session_device_item : R.string.other_session_device_item,
              device,
              version,
              date(session.optString("created_at")),
              date(session.optString("last_used_at"))));
      details.setTextSize(17);
      card.addView(details);
      if (!current) {
        Button revoke = new Button(this);
        revoke.setText(getString(R.string.revoke_device_session, device));
        revoke.setAllCaps(false);
        revoke.setOnClickListener(v -> confirmRevoke(session));
        card.addView(revoke);
      }
      list.addView(card);
    }
  }

  private final AccountManagementClient.Listener listener =
      new AccountManagementClient.Listener() {
        @Override
        public void onSuccess(String operation, JSONObject response) {
          runOnUiThread(
              () -> {
                if ("list".equals(operation)) {
                  render(response);
                } else {
                  status.setText(R.string.session_revoked);
                  load();
                }
              });
        }

        @Override
        public void onError(String error) {
          runOnUiThread(
              () -> {
                findViewById(R.id.sessions_loading).setVisibility(View.GONE);
                findViewById(R.id.sessions_retry).setVisibility(View.VISIBLE);
                status.setText(AccountErrorMessages.get(SessionsActivity.this, error));
              });
        }
      };
}
