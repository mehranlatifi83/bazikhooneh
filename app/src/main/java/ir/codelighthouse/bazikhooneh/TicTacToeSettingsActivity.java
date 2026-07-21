package ir.codelighthouse.bazikhooneh;
import android.os.Bundle;
import android.widget.CheckBox;
public final class TicTacToeSettingsActivity extends NavigableActivity {
    public static final String PREFS="tic_tac_toe_settings", LARGE_TEXT="large_text", HIGH_CONTRAST="high_contrast";
    @Override protected void onCreate(Bundle state){super.onCreate(state);setContentView(R.layout.activity_tic_tac_toe_settings);bind(R.id.setting_large_text,LARGE_TEXT);bind(R.id.setting_high_contrast,HIGH_CONTRAST);}
    private void bind(int id,String key){CheckBox box=findViewById(id);box.setChecked(getSharedPreferences(PREFS,MODE_PRIVATE).getBoolean(key,false));box.setOnCheckedChangeListener((b,c)->getSharedPreferences(PREFS,MODE_PRIVATE).edit().putBoolean(key,c).apply());}
}
