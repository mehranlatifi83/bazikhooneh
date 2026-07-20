package ir.codelighthouse.bazikhooneh;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import ir.codelighthouse.bazikhooneh.account.AccountClient;
import ir.codelighthouse.bazikhooneh.account.AccountSession;
import ir.codelighthouse.bazikhooneh.account.SessionStore;

public final class LoginActivity extends Activity {
    private EditText username;
    private EditText displayName;
    private EditText password;
    private TextView error;
    private AccountClient client;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_login);
        username = findViewById(R.id.login_username);
        displayName = findViewById(R.id.login_display_name);
        password = findViewById(R.id.login_password);
        error = findViewById(R.id.login_error);
        client = new AccountClient(BuildConfig.API_BASE_URL, listener);
        findViewById(R.id.login_button).setOnClickListener(v -> login());
        findViewById(R.id.register_button).setOnClickListener(v -> register());
    }

    private boolean validBaseFields() {
        if (username.getText().toString().trim().length() < 3 || password.length() < 8) {
            showError(getString(R.string.account_fields_required));
            return false;
        }
        return true;
    }

    private void login() {
        if (!validBaseFields()) return;
        setLoading(true);
        client.login(username.getText().toString().trim(), password.getText().toString());
    }

    private void register() {
        if (!validBaseFields()) return;
        String name = displayName.getText().toString().trim();
        if (name.isEmpty()) { showError(getString(R.string.display_name_required)); return; }
        setLoading(true);
        client.register(username.getText().toString().trim(), name, password.getText().toString());
    }

    private void setLoading(boolean loading) {
        findViewById(R.id.login_progress).setVisibility(loading ? View.VISIBLE : View.GONE);
        findViewById(R.id.login_button).setEnabled(!loading);
        findViewById(R.id.register_button).setEnabled(!loading);
    }

    private void showError(String message) {
        error.setText(message);
        error.setVisibility(View.VISIBLE);
        error.announceForAccessibility(message);
        setLoading(false);
    }

    private final AccountClient.Listener listener = new AccountClient.Listener() {
        @Override public void onSession(AccountSession session) {
            runOnUiThread(() -> { new SessionStore(LoginActivity.this).save(session); setResult(RESULT_OK); finish(); });
        }
        @Override public void onLoggedOut() { }
        @Override public void onError(String value) {
            runOnUiThread(() -> showError(getString(R.string.account_error, value)));
        }
    };
}
