package ir.codelighthouse.bazikhooneh;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

public final class LanguageActivity extends Activity {
  @Override
  protected void attachBaseContext(Context base) {
    super.attachBaseContext(AppDisplay.wrap(base));
  }

  @Override
  protected void onCreate(Bundle state) {
    super.onCreate(state);
    if (LanguageManager.hasSelection(this)) {
      openHome();
      return;
    }
    if (getActionBar() != null) getActionBar().hide();
    setContentView(R.layout.activity_language);
    findViewById(R.id.choose_persian).setOnClickListener(v -> choose(LanguageManager.PERSIAN));
    findViewById(R.id.choose_english).setOnClickListener(v -> choose(LanguageManager.ENGLISH));
  }

  private void choose(String language) {
    LanguageManager.select(this, language);
    openHome();
  }

  private void openHome() {
    Intent home =
        new Intent(this, HomeActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
    if (getIntent().getData() != null) home.setData(getIntent().getData());
    startActivity(home);
    finish();
  }
}
