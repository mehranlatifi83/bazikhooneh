package ir.codelighthouse.bazikhooneh.feature.tictactoe;

import android.content.Context;
import android.content.Intent;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import ir.codelighthouse.bazikhooneh.R;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Device tests for the main local game flow and essential accessibility metadata. */
@RunWith(AndroidJUnit4.class)
public final class TicTacToeGameActivityUiTest {
    @Rule
    public final ActivityScenarioRule<TicTacToeGameActivity> activityRule =
            new ActivityScenarioRule<>(TicTacToeGameActivity.class);

    @Test
    public void everyBoardCellHasAccessibleLabelAndTouchSize() {
        activityRule.getScenario().onActivity(activity -> {
            int[] ids = {
                    R.id.cell_0, R.id.cell_1, R.id.cell_2,
                    R.id.cell_3, R.id.cell_4, R.id.cell_5,
                    R.id.cell_6, R.id.cell_7, R.id.cell_8
            };
            int minimumTouchSize = Math.round(48
                    * activity.getResources().getDisplayMetrics().density);
            for (int id : ids) {
                Button cell = activity.findViewById(id);
                assertNotNull(cell);
                assertNotNull(cell.getContentDescription());
                assertTrue(cell.getContentDescription().length() > 0);
                assertTrue(cell.getWidth() >= minimumTouchSize);
                assertTrue(cell.getHeight() >= minimumTouchSize);
            }
        });
    }

    @Test
    public void localGameCanBeWonAndRestarted() {
        activityRule.getScenario().onActivity(activity -> {
            activity.findViewById(R.id.cell_0).performClick();
            activity.findViewById(R.id.cell_3).performClick();
            activity.findViewById(R.id.cell_1).performClick();
            activity.findViewById(R.id.cell_4).performClick();
            activity.findViewById(R.id.cell_2).performClick();

            TextView status = activity.findViewById(R.id.game_status);
            assertEquals(activity.getString(R.string.player_won,
                    activity.getString(R.string.mark_x)), status.getText().toString());

            activity.findViewById(R.id.restart_button).performClick();
            assertEquals("", ((Button) activity.findViewById(R.id.cell_0)).getText().toString());
            assertEquals(activity.getString(R.string.player_turn,
                    activity.getString(R.string.mark_x)), status.getText().toString());
        });
    }

    @Test
    public void botControlsArePresentAndDifficultyStartsDisabled() {
        activityRule.getScenario().onActivity(activity -> {
            assertNotNull(activity.findViewById(R.id.mode_local));
            assertNotNull(activity.findViewById(R.id.mode_bot));
            Spinner difficulty = activity.findViewById(R.id.difficulty_spinner);
            assertNotNull(difficulty);
            assertFalse(difficulty.isEnabled());
            assertEquals(3, difficulty.getCount());
        });
    }

    @Test
    public void localGameEntersMovementPhaseAndMovesSelectedPiece() {
        activityRule.getScenario().onActivity(activity -> {
            int[] placements = {
                    R.id.cell_0, R.id.cell_1, R.id.cell_2,
                    R.id.cell_3, R.id.cell_7, R.id.cell_8
            };
            for (int id : placements) {
                activity.findViewById(id).performClick();
            }

            TextView status = activity.findViewById(R.id.game_status);
            assertEquals(activity.getString(R.string.player_move_turn,
                    activity.getString(R.string.mark_x)), status.getText().toString());

            activity.findViewById(R.id.cell_0).performClick();
            assertTrue(activity.findViewById(R.id.cell_0).getAlpha() < 1f);
            activity.findViewById(R.id.cell_6).performClick();

            assertEquals("", ((Button) activity.findViewById(R.id.cell_0)).getText().toString());
            assertEquals("X", ((Button) activity.findViewById(R.id.cell_6)).getText().toString());
            assertEquals(activity.getString(R.string.player_move_turn,
                    activity.getString(R.string.mark_o)), status.getText().toString());
        });
    }

    @Test
    public void onlineGameCanResumeBeforeServerStateArrives() {
        Context context = ApplicationProvider.getApplicationContext();
        context.getSharedPreferences("online_session", Context.MODE_PRIVATE).edit().clear().commit();
        Intent intent = new Intent(context, TicTacToeGameActivity.class)
                .putExtra(TicTacToeGameActivity.EXTRA_MODE, "online");
        try (ActivityScenario<TicTacToeGameActivity> scenario =
                     ActivityScenario.launch(intent)) {
            scenario.onActivity(activity -> {
                assertNotNull(activity.findViewById(R.id.online_controls));
                assertNotNull(activity.findViewById(R.id.game_status));
            });
        }
    }
}
