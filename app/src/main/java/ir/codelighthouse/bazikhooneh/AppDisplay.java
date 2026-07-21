package ir.codelighthouse.bazikhooneh;
import android.content.*;import android.content.res.Configuration;
public final class AppDisplay{private AppDisplay(){}
 public static Context wrap(Context base){boolean large=base.getSharedPreferences(SettingsActivity.PREFS,Context.MODE_PRIVATE).getBoolean(SettingsActivity.LARGE_TEXT,false);if(!large)return base;Configuration config=new Configuration(base.getResources().getConfiguration());config.fontScale=Math.max(config.fontScale,1.2f);return base.createConfigurationContext(config);}
 public static boolean reduceMotion(Context context){return context.getSharedPreferences(SettingsActivity.PREFS,Context.MODE_PRIVATE).getBoolean(SettingsActivity.REDUCE_MOTION,false);}
}
