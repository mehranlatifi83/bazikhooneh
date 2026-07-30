package ir.codelighthouse.bazikhooneh.feature.account;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.method.HideReturnsTransformationMethod;
import android.text.method.PasswordTransformationMethod;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import ir.codelighthouse.bazikhooneh.BuildConfig;
import ir.codelighthouse.bazikhooneh.R;
import ir.codelighthouse.bazikhooneh.account.AccountClient;
import ir.codelighthouse.bazikhooneh.account.AccountErrorMessages;
import ir.codelighthouse.bazikhooneh.account.AccountSession;
import ir.codelighthouse.bazikhooneh.account.SessionStore;
import ir.codelighthouse.bazikhooneh.core.ui.NavigableActivity;

public final class RegisterActivity extends NavigableActivity {
  private AccountClient client;

  @Override
  protected void onCreate(Bundle state) {
    super.onCreate(state);
    setContentView(R.layout.activity_register);
    client = new AccountClient(BuildConfig.API_BASE_URL, listener);
    findViewById(R.id.register_submit).setOnClickListener(v -> register());
    ((CheckBox) findViewById(R.id.register_show_password))
        .setOnCheckedChangeListener(
            (b, shown) -> {
              toggle(R.id.register_password, shown);
              toggle(R.id.register_password_confirm, shown);
            });
    TextWatcher watcher =
        new TextWatcher() {
          public void beforeTextChanged(CharSequence s, int a, int b, int c) {}

          public void onTextChanged(CharSequence s, int a, int b, int c) {
            validateLive();
          }

          public void afterTextChanged(Editable e) {}
        };
    ((EditText) findViewById(R.id.register_username)).addTextChangedListener(watcher);
    ((EditText) findViewById(R.id.register_display_name)).addTextChangedListener(watcher);
    ((EditText) findViewById(R.id.register_password)).addTextChangedListener(watcher);
    ((EditText) findViewById(R.id.register_password_confirm)).addTextChangedListener(watcher);
    findViewById(R.id.register_submit).setEnabled(false);
  }

  private void validateLive() {
    String username = text(R.id.register_username),
        name = text(R.id.register_display_name),
        password = text(R.id.register_password),
        confirm = text(R.id.register_password_confirm);
    boolean format = username.matches("[A-Za-z0-9_]{3,30}");
    boolean valid = format && !name.isEmpty() && password.length() >= 8 && password.equals(confirm);
    findViewById(R.id.register_submit).setEnabled(valid);
    TextView message = findViewById(R.id.register_error);
    if (!confirm.isEmpty() && !password.equals(confirm)) {
      message.setText(R.string.passwords_do_not_match);
      message.setVisibility(View.VISIBLE);
    } else message.setVisibility(View.GONE);
  }

  private void toggle(int id, boolean shown) {
    EditText field = findViewById(id);
    int position = field.getSelectionStart();
    field.setTransformationMethod(
        shown
            ? HideReturnsTransformationMethod.getInstance()
            : PasswordTransformationMethod.getInstance());
    field.setSelection(Math.max(0, position));
  }

  private String text(int id) {
    return ((EditText) findViewById(id)).getText().toString().trim();
  }

  private void register() {
    String username = text(R.id.register_username),
        name = text(R.id.register_display_name),
        password = text(R.id.register_password);
    if (!username.matches("[A-Za-z0-9_]{3,30}") || password.length() < 8 || name.isEmpty()) {
      show(getString(R.string.registration_fields_required));
      return;
    }
    if (!password.equals(text(R.id.register_password_confirm))) {
      show(getString(R.string.passwords_do_not_match));
      return;
    }
    setLoading(true);
    client.register(username, name, password);
  }

  private void setLoading(boolean value) {
    findViewById(R.id.register_progress).setVisibility(value ? View.VISIBLE : View.GONE);
    findViewById(R.id.register_submit).setEnabled(!value);
  }

  private void show(String value) {
    TextView status = findViewById(R.id.register_error);
    status.setText(value);
    status.setVisibility(View.VISIBLE);
    status.announceForAccessibility(value);
    setLoading(false);
  }

  private final AccountClient.Listener listener =
      new AccountClient.Listener() {
        public void onSession(AccountSession s) {
          runOnUiThread(
              () -> {
                new SessionStore(RegisterActivity.this).save(s);
                setResult(RESULT_OK);
                finish();
              });
        }

        public void onLoggedOut() {}

        public void onError(String e) {
          runOnUiThread(() -> show(AccountErrorMessages.get(RegisterActivity.this, e)));
        }
      };
}
