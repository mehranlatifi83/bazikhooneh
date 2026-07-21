package ir.codelighthouse.bazikhooneh;

import android.os.Bundle;
import android.widget.CheckBox;

public final class SettingsActivity extends NavigableActivity {
    public static final String PREFS = "app_settings";
    public static final String HAPTIC = "haptic";
    public static final String SOUND = "sound";
    public static final String LARGE_TEXT = "large_text";
    public static final String HIGH_CONTRAST = "high_contrast";
    public static final String REDUCE_MOTION = "reduce_motion";
    public static final String NOTIFICATIONS = "notifications";
    public static final String DETAILED_ANNOUNCEMENTS = "detailed_announcements";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_settings);
        bind(R.id.setting_haptic, HAPTIC, true);
        bind(R.id.setting_sound, SOUND, true);
        bind(R.id.setting_large_text, LARGE_TEXT, false);
        bind(R.id.setting_high_contrast, HIGH_CONTRAST, false);
        bind(R.id.setting_reduce_motion, REDUCE_MOTION, false);
        bind(R.id.setting_notifications, NOTIFICATIONS, true);
        bind(R.id.setting_detailed_announcements, DETAILED_ANNOUNCEMENTS, true);
    }

    private void bind(int viewId, String key, boolean defaultValue) {
        CheckBox box = findViewById(viewId);
        box.setChecked(getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(key, defaultValue));
        box.setOnCheckedChangeListener((button, checked) ->
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(key, checked).apply());
    }
}
