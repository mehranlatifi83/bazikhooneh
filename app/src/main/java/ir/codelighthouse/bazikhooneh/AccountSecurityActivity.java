package ir.codelighthouse.bazikhooneh;

import android.content.Intent;
import android.os.Bundle;

public final class AccountSecurityActivity extends NavigableActivity {
  @Override
  protected void onCreate(Bundle state) {
    super.onCreate(state);
    setContentView(R.layout.activity_account_security);
    findViewById(R.id.open_username_change)
        .setOnClickListener(v -> open(UsernameChangeActivity.class));
    findViewById(R.id.open_email_verification)
        .setOnClickListener(v -> open(EmailVerificationActivity.class));
    findViewById(R.id.open_password_change)
        .setOnClickListener(v -> open(PasswordChangeActivity.class));
    findViewById(R.id.open_sessions).setOnClickListener(v -> open(SessionsActivity.class));
    findViewById(R.id.open_account_delete)
        .setOnClickListener(v -> open(DeleteAccountActivity.class));
  }

  private void open(Class<?> activity) {
    startActivity(new Intent(this, activity));
  }
}
