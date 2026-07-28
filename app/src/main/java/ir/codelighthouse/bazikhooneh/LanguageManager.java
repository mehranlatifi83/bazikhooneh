package ir.codelighthouse.bazikhooneh;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.Locale;

public final class LanguageManager {
    public static final String LANGUAGE = "language";
    public static final String LANGUAGE_SELECTED = "language_selected";
    public static final String PERSIAN = "fa";
    public static final String ENGLISH = "en";
    private LanguageManager() {}
    public static SharedPreferences preferences(Context context) { return context.getSharedPreferences(SettingsActivity.PREFS, Context.MODE_PRIVATE); }
    public static boolean hasSelection(Context context) { return preferences(context).getBoolean(LANGUAGE_SELECTED, false); }
    public static String selectedLanguage(Context context) { return preferences(context).getString(LANGUAGE, PERSIAN.equals(Locale.getDefault().getLanguage()) ? PERSIAN : ENGLISH); }
    public static void select(Context context, String language) { preferences(context).edit().putString(LANGUAGE, PERSIAN.equals(language) ? PERSIAN : ENGLISH).putBoolean(LANGUAGE_SELECTED, true).apply(); }
    public static int selectedPosition(Context context) { return PERSIAN.equals(selectedLanguage(context)) ? 0 : 1; }
}
