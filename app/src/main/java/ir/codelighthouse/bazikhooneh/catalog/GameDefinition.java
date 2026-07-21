package ir.codelighthouse.bazikhooneh.catalog;

import android.app.Activity;

public final class GameDefinition {
    public final String id;
    public final int titleRes;
    public final int descriptionRes;
    public final Class<? extends Activity> activity;
    public final Class<? extends Activity> guideActivity;
    public final Class<? extends Activity> settingsActivity;

    public GameDefinition(String id, int titleRes, int descriptionRes,
                          Class<? extends Activity> activity,
                          Class<? extends Activity> guideActivity,
                          Class<? extends Activity> settingsActivity) {
        this.id = id;
        this.titleRes = titleRes;
        this.descriptionRes = descriptionRes;
        this.activity = activity;
        this.guideActivity = guideActivity;
        this.settingsActivity = settingsActivity;
    }
}
