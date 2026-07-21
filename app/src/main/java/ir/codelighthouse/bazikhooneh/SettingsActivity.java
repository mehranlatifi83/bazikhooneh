package ir.codelighthouse.bazikhooneh;

import android.os.Bundle;
import android.widget.CheckBox;

public final class SettingsActivity extends NavigableActivity {
    public static final String PREFS = "app_settings";
    public static final String HAPTIC = "haptic";
    public static final String SOUND = "sound";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_settings);
        bind(R.id.setting_haptic, HAPTIC, true);
        bind(R.id.setting_sound, SOUND, true);
    }

    private void bind(int viewId, String key, boolean defaultValue) {
        CheckBox box = findViewById(viewId);
        box.setChecked(getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(key, defaultValue));
        box.setOnCheckedChangeListener((button, checked) ->
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(key, checked).apply());
    }
}
