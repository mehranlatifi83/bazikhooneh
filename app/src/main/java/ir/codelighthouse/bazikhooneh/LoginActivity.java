package ir.codelighthouse.bazikhooneh;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import ir.codelighthouse.bazikhooneh.account.AccountClient;
import ir.codelighthouse.bazikhooneh.account.AccountSession;
import ir.codelighthouse.bazikhooneh.account.SessionStore;
import ir.codelighthouse.bazikhooneh.account.AccountErrorMessages;

public final class LoginActivity extends NavigableActivity {
    private EditText username;
    private EditText password;
    private TextView error;
    private AccountClient client;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_login);
        username = findViewById(R.id.login_username);
        password = findViewById(R.id.login_password);
        error = findViewById(R.id.login_error);
        client = new AccountClient(BuildConfig.API_BASE_URL, listener);
        findViewById(R.id.login_button).setOnClickListener(v -> login());
        findViewById(R.id.open_register).setOnClickListener(v ->
                startActivity(new Intent(this, RegisterActivity.class)));
        findViewById(R.id.forgot_password).setOnClickListener(v ->
                startActivity(new Intent(this, PasswordResetActivity.class)));
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

    private void setLoading(boolean loading) {
        findViewById(R.id.login_progress).setVisibility(loading ? View.VISIBLE : View.GONE);
        findViewById(R.id.login_button).setEnabled(!loading);
        findViewById(R.id.open_register).setEnabled(!loading);
    }

    private void showError(String message) {
        error.setText(message);
        error.setVisibility(View.VISIBLE);
        error.announceForAccessibility(message);
        setLoading(false);
    }

    private final AccountClient.Listener listener = new AccountClient.Listener() {
        @Override public void onSession(AccountSession session) {
            runOnUiThread(() -> {
                new SessionStore(LoginActivity.this).save(session);
                String roomCode = getIntent().getStringExtra("room_code");
                if (roomCode != null || getIntent().getBooleanExtra("open_online", false)) {
                    Intent lobby = new Intent(LoginActivity.this, OnlineLobbyActivity.class);
                    if (roomCode != null) lobby.putExtra("room_code", roomCode);
                    startActivity(lobby);
                }
                setResult(RESULT_OK);
                finish();
            });
        }
        @Override public void onLoggedOut() { }
        @Override public void onError(String value) {
            runOnUiThread(() -> showError(AccountErrorMessages.get(LoginActivity.this, value)));
        }
    };
}
