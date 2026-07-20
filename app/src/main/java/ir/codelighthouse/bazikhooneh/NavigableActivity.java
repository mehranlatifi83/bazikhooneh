package ir.codelighthouse.bazikhooneh;

import android.app.ActionBar;
import android.app.Activity;
import android.os.Bundle;
import android.view.MenuItem;

/** Common navigation behavior for every screen below the app home. */
public abstract class NavigableActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        ActionBar actionBar = getActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setHomeButtonEnabled(true);
        }
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finishAfterTransition();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
