package ir.codelighthouse.bazikhooneh.core.ui;

import android.content.*;
import android.content.res.Configuration;
import ir.codelighthouse.bazikhooneh.core.localization.LanguageManager;
import ir.codelighthouse.bazikhooneh.core.preferences.AppPreferences;

public final class AppDisplay {
  private AppDisplay() {}

  public static Context wrap(Context base) {
    android.content.SharedPreferences prefs =
        base.getSharedPreferences(AppPreferences.PREFS, Context.MODE_PRIVATE);
    Configuration config = new Configuration(base.getResources().getConfiguration());
    config.setLocale(new java.util.Locale(LanguageManager.selectedLanguage(base)));
    config.setLayoutDirection(config.locale);
    if (prefs.getBoolean(AppPreferences.LARGE_TEXT, false))
      config.fontScale = Math.max(config.fontScale, 1.2f);
    int theme = prefs.getInt(AppPreferences.THEME, 0);
    if (theme == 1)
      config.uiMode =
          (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | Configuration.UI_MODE_NIGHT_NO;
    else if (theme == 2)
      config.uiMode =
          (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | Configuration.UI_MODE_NIGHT_YES;
    return base.createConfigurationContext(config);
  }

  public static boolean reduceMotion(Context context) {
    return context
        .getSharedPreferences(AppPreferences.PREFS, Context.MODE_PRIVATE)
        .getBoolean(AppPreferences.REDUCE_MOTION, false);
  }
}
