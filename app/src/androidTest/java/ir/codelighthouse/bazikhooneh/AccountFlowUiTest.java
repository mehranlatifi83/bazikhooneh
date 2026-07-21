package ir.codelighthouse.bazikhooneh;

import android.view.View;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

@RunWith(AndroidJUnit4.class)
public final class AccountFlowUiTest {
    @Test public void loginAndRegistrationHaveSeparateFields() {
        try (ActivityScenario<LoginActivity> scenario = ActivityScenario.launch(LoginActivity.class)) {
            scenario.onActivity(activity -> {
                assertNotNull(activity.findViewById(R.id.login_username));
                assertNotNull(activity.findViewById(R.id.login_password));
                assertNotNull(activity.findViewById(R.id.open_register));
            });
        }
        try (ActivityScenario<RegisterActivity> scenario = ActivityScenario.launch(RegisterActivity.class)) {
            scenario.onActivity(activity -> assertNotNull(activity.findViewById(R.id.register_display_name)));
        }
    }

    @Test public void passwordResetStartsAtEmailStepOnly() {
        try (ActivityScenario<PasswordResetActivity> scenario = ActivityScenario.launch(PasswordResetActivity.class)) {
            scenario.onActivity(activity -> {
                assertEquals(View.VISIBLE, activity.findViewById(R.id.reset_request_step).getVisibility());
                assertEquals(View.GONE, activity.findViewById(R.id.reset_confirm_step).getVisibility());
            });
        }
    }

    @Test public void accessibilityOptionsAreGlobalSettings() {
        try (ActivityScenario<SettingsActivity> scenario = ActivityScenario.launch(SettingsActivity.class)) {
            scenario.onActivity(activity -> {
                assertNotNull(activity.findViewById(R.id.setting_large_text));
                assertNotNull(activity.findViewById(R.id.setting_high_contrast));
            });
        }
    }
}
