package ir.codelighthouse.bazikhooneh;

import android.content.Intent;
import android.os.Bundle;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.Spinner;

public final class SettingsActivity extends NavigableActivity {
  public static final String PREFS = "app_settings";
  public static final String HAPTIC = "haptic";
  public static final String SOUND = "sound";
  public static final String LARGE_TEXT = "large_text";
  public static final String HIGH_CONTRAST = "high_contrast";
  public static final String REDUCE_MOTION = "reduce_motion";
  public static final String NOTIFICATIONS = "notifications";
  public static final String FRIEND_NOTIFICATIONS = "friend_notifications";
  public static final String GAME_NOTIFICATIONS = "game_notifications";
  public static final String THEME = "theme";
  public static final String DETAILED_ANNOUNCEMENTS = "detailed_announcements";

  @Override
  protected void onCreate(Bundle state) {
    super.onCreate(state);
    setContentView(R.layout.activity_settings);
    Spinner language = findViewById(R.id.setting_language);
    language.setAdapter(
        ArrayAdapter.createFromResource(
            this, R.array.language_choices, android.R.layout.simple_spinner_dropdown_item));
    language.setSelection(LanguageManager.selectedPosition(this));
    language.setOnItemSelectedListener(
        new AdapterView.OnItemSelectedListener() {
          boolean first = true;

          public void onNothingSelected(AdapterView<?> p) {}

          public void onItemSelected(AdapterView<?> p, android.view.View v, int position, long id) {
            if (first) {
              first = false;
              return;
            }
            String selected = position == 0 ? LanguageManager.PERSIAN : LanguageManager.ENGLISH;
            if (selected.equals(LanguageManager.selectedLanguage(SettingsActivity.this))) return;
            LanguageManager.select(SettingsActivity.this, selected);
            startActivity(
                new Intent(SettingsActivity.this, HomeActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
          }
        });
    bind(R.id.setting_haptic, HAPTIC, true);
    bind(R.id.setting_sound, SOUND, true);
    bind(R.id.setting_large_text, LARGE_TEXT, false);
    bind(R.id.setting_high_contrast, HIGH_CONTRAST, false);
    bind(R.id.setting_reduce_motion, REDUCE_MOTION, false);
    CheckBox notifications = findViewById(R.id.setting_notifications);
    notifications.setChecked(
        getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(NOTIFICATIONS, true));
    notifications.setOnCheckedChangeListener(
        (button, checked) -> {
          getSharedPreferences(PREFS, MODE_PRIVATE)
              .edit()
              .putBoolean(NOTIFICATIONS, checked)
              .apply();
          updateNotificationOptions(checked);
        });
    bind(R.id.setting_friend_notifications, FRIEND_NOTIFICATIONS, true);
    bind(R.id.setting_game_notifications, GAME_NOTIFICATIONS, true);
    bind(R.id.setting_detailed_announcements, DETAILED_ANNOUNCEMENTS, true);
    updateNotificationOptions(notifications.isChecked());
    Spinner theme = findViewById(R.id.setting_theme);
    theme.setAdapter(
        ArrayAdapter.createFromResource(
            this, R.array.theme_choices, android.R.layout.simple_spinner_dropdown_item));
    theme.setSelection(getSharedPreferences(PREFS, MODE_PRIVATE).getInt(THEME, 0));
    theme.setOnItemSelectedListener(
        new AdapterView.OnItemSelectedListener() {
          boolean first = true;

          public void onNothingSelected(AdapterView<?> p) {}

          public void onItemSelected(AdapterView<?> p, android.view.View v, int position, long id) {
            if (first) {
              first = false;
              return;
            }
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(THEME, position).apply();
            recreate();
          }
        });
  }

  private void updateNotificationOptions(boolean visible) {
    findViewById(R.id.setting_friend_notifications)
        .setVisibility(visible ? android.view.View.VISIBLE : android.view.View.GONE);
    findViewById(R.id.setting_game_notifications)
        .setVisibility(visible ? android.view.View.VISIBLE : android.view.View.GONE);
  }

  private void bind(int viewId, String key, boolean defaultValue) {
    CheckBox box = findViewById(viewId);
    box.setChecked(getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(key, defaultValue));
    box.setOnCheckedChangeListener(
        (button, checked) -> {
          getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(key, checked).apply();
          if (LARGE_TEXT.equals(key)) recreate();
        });
  }
}
