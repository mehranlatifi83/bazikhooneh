package ir.codelighthouse.bazikhooneh;

import android.view.View;
import android.widget.Button;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public final class HomeActivityUiTest {
    @Rule public final ActivityScenarioRule<HomeActivity> rule =
            new ActivityScenarioRule<>(HomeActivity.class);

    @Test public void primaryDestinationsHaveReadableLabelsAndTouchTargets() {
        rule.getScenario().onActivity(activity -> {
            int[] ids = {R.id.open_tic_tac_toe, R.id.open_profile, R.id.open_settings, R.id.open_guide};
            int minimum = Math.round(48 * activity.getResources().getDisplayMetrics().density);
            for (int id : ids) {
                Button button = activity.findViewById(id);
                assertFalse(button.getText().toString().trim().isEmpty());
                assertTrue(button.getHeight() >= minimum);
                assertTrue(button.getVisibility() == View.VISIBLE);
            }
        });
    }
}
