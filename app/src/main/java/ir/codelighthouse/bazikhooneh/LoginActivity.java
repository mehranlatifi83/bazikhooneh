package ir.codelighthouse.bazikhooneh;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.CheckBox;
import android.text.method.HideReturnsTransformationMethod;
import android.text.method.PasswordTransformationMethod;
import android.text.TextWatcher;import android.text.Editable;
import ir.codelighthouse.bazikhooneh.account.AccountClient;
import ir.codelighthouse.bazikhooneh.account.AccountSession;
import ir.codelighthouse.bazikhooneh.account.SessionStore;
import ir.codelighthouse.bazikhooneh.account.AccountErrorMessages;
import ir.codelighthouse.bazikhooneh.navigation.AppNavigator;

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
        ((CheckBox)findViewById(R.id.login_show_password)).setOnCheckedChangeListener((button,shown)->{
            int position=password.getSelectionStart();password.setTransformationMethod(shown?HideReturnsTransformationMethod.getInstance():PasswordTransformationMethod.getInstance());password.setSelection(Math.max(0,position));
        });
        TextWatcher watcher=new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int b,int c){}public void onTextChanged(CharSequence s,int a,int b,int c){findViewById(R.id.login_button).setEnabled(username.getText().toString().trim().length()>=3&&password.length()>=8);error.setVisibility(View.GONE);}public void afterTextChanged(Editable e){}};username.addTextChangedListener(watcher);password.addTextChangedListener(watcher);findViewById(R.id.login_button).setEnabled(false);
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
                if (AppNavigator.consumePendingRoom(LoginActivity.this)) {
                    setResult(RESULT_OK);
                    finish();
                    return;
                }
                String roomCode = getIntent().getStringExtra("room_code");
                String gameKey = getIntent().getStringExtra(CommunityRoomsActivity.EXTRA_GAME_KEY);
                if (roomCode != null || getIntent().getBooleanExtra("open_online", false)) {
                    Intent lobby = new Intent(LoginActivity.this, CommunityRoomsActivity.class);
                    if (roomCode != null) lobby.putExtra(CommunityRoomsActivity.EXTRA_ROOM_CODE, roomCode);
                    if (gameKey != null) lobby.putExtra(CommunityRoomsActivity.EXTRA_GAME_KEY, gameKey);
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
