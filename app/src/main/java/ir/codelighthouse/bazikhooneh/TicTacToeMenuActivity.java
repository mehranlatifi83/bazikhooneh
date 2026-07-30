package ir.codelighthouse.bazikhooneh;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.RadioGroup;
import ir.codelighthouse.bazikhooneh.navigation.AppNavigator;
import ir.codelighthouse.bazikhooneh.feature.tictactoe.TicTacToeGameActivity;

public final class TicTacToeMenuActivity extends NavigableActivity {
    private RadioGroup difficulty;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_tic_tac_toe_menu);
        difficulty = findViewById(R.id.menu_bot_difficulty);
        findViewById(R.id.menu_local).setOnClickListener(v -> openGame("local", 1));
        findViewById(R.id.menu_bot).setOnClickListener(v -> {
            difficulty.setVisibility(View.VISIBLE);
            findViewById(R.id.menu_start_bot).setVisibility(View.VISIBLE);
            difficulty.announceForAccessibility(getString(R.string.choose_bot_difficulty));
        });
        findViewById(R.id.menu_start_bot).setOnClickListener(v -> openGame("bot", difficultyValue()));
        findViewById(R.id.menu_online).setOnClickListener(v -> openOnline());
        findViewById(R.id.menu_guide).setOnClickListener(v ->
                startActivity(new Intent(this, TicTacToeGuideActivity.class)));
    }

    private int difficultyValue() {
        int checked = difficulty.getCheckedRadioButtonId();
        if (checked == R.id.difficulty_easy) return 0;
        if (checked == R.id.difficulty_hard) return 2;
        return 1;
    }

    private void openGame(String mode, int botDifficulty) {
        startActivity(new Intent(this, TicTacToeGameActivity.class)
                .putExtra(TicTacToeGameActivity.EXTRA_MODE, mode)
                .putExtra(TicTacToeGameActivity.EXTRA_DIFFICULTY, botDifficulty));
    }

    private void openOnline() {
        AppNavigator.openRooms(this, "three_piece_tic_tac_toe");
    }
}
