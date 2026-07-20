package ir.codelighthouse.bazikhooneh;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.ArrayList;

import ir.codelighthouse.bazikhooneh.game.tictactoe.GameStatus;
import ir.codelighthouse.bazikhooneh.game.tictactoe.BotDifficulty;
import ir.codelighthouse.bazikhooneh.game.tictactoe.Mark;
import ir.codelighthouse.bazikhooneh.game.tictactoe.MoveResult;
import ir.codelighthouse.bazikhooneh.game.tictactoe.TicTacToeBot;
import ir.codelighthouse.bazikhooneh.game.tictactoe.TicTacToeGame;

public final class MainActivity extends Activity {
    private static final String STATE_MOVES = "state_moves";
    private static final String STATE_BOT_MODE = "state_bot_mode";
    private static final String STATE_DIFFICULTY = "state_difficulty";
    private static final long BOT_MOVE_DELAY_MS = 450L;

    private final TicTacToeGame game = new TicTacToeGame();
    private final TicTacToeBot bot = new TicTacToeBot();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Button[] cells = new Button[TicTacToeGame.CELL_COUNT];
    private TextView statusText;
    private Spinner difficultySpinner;
    private boolean botMode;
    private boolean botThinking;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.game_status);
        difficultySpinner = findViewById(R.id.difficulty_spinner);
        configureGameOptions(savedInstanceState);
        int[] cellIds = {
                R.id.cell_0, R.id.cell_1, R.id.cell_2,
                R.id.cell_3, R.id.cell_4, R.id.cell_5,
                R.id.cell_6, R.id.cell_7, R.id.cell_8
        };
        for (int index = 0; index < cells.length; index++) {
            final int cellIndex = index;
            cells[index] = findViewById(cellIds[index]);
            cells[index].setOnClickListener(view -> playMove(cellIndex));
        }
        findViewById(R.id.restart_button).setOnClickListener(view -> restartGame());

        restoreMoves(savedInstanceState);
        render(false);
        if (botMode && game.getCurrentPlayer() == Mark.O) {
            scheduleBotMove();
        }
    }

    private void configureGameOptions(Bundle state) {
        botMode = state != null && state.getBoolean(STATE_BOT_MODE, false);
        int difficultyPosition = state == null ? BotDifficulty.MEDIUM.ordinal()
                : state.getInt(STATE_DIFFICULTY, BotDifficulty.MEDIUM.ordinal());

        RadioGroup modeGroup = findViewById(R.id.game_mode_group);
        modeGroup.check(botMode ? R.id.mode_bot : R.id.mode_local);

        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this,
                R.array.bot_difficulties, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        difficultySpinner.setAdapter(adapter);
        difficultySpinner.setSelection(difficultyPosition, false);
        difficultySpinner.setEnabled(botMode);

        modeGroup.setOnCheckedChangeListener((group, checkedId) -> {
            botMode = checkedId == R.id.mode_bot;
            difficultySpinner.setEnabled(botMode);
            restartGame();
        });
        difficultySpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            private boolean firstSelection = true;

            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (firstSelection) {
                    firstSelection = false;
                    return;
                }
                restartGame();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // Keep the current difficulty.
            }
        });
    }

    private void playMove(int cellIndex) {
        if (botThinking || (botMode && game.getCurrentPlayer() == Mark.O)) {
            return;
        }
        Mark playedMark = game.getCurrentPlayer();
        MoveResult result = game.play(cellIndex);
        if (result == MoveResult.CELL_OCCUPIED) {
            announce(getString(R.string.cell_occupied));
            return;
        }
        if (result != MoveResult.ACCEPTED) {
            return;
        }

        cells[cellIndex].performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        render(true);
        if (game.getStatus() == GameStatus.IN_PROGRESS) {
            int row = cellIndex / TicTacToeGame.BOARD_SIZE + 1;
            int column = cellIndex % TicTacToeGame.BOARD_SIZE + 1;
            announce(getString(R.string.move_announcement, markName(playedMark), row, column,
                    markName(game.getCurrentPlayer())));
        } else {
            announce(statusText.getText().toString());
        }
        if (botMode && game.getStatus() == GameStatus.IN_PROGRESS) {
            scheduleBotMove();
        }
    }

    private void scheduleBotMove() {
        botThinking = true;
        render(false);
        handler.postDelayed(() -> {
            int move = bot.chooseMove(game, selectedDifficulty());
            if (move >= 0) {
                game.play(move);
                cells[move].performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            }
            botThinking = false;
            render(true);
            announce(statusText.getText().toString());
        }, BOT_MOVE_DELAY_MS);
    }

    private BotDifficulty selectedDifficulty() {
        int position = difficultySpinner.getSelectedItemPosition();
        BotDifficulty[] values = BotDifficulty.values();
        return position >= 0 && position < values.length ? values[position] : BotDifficulty.MEDIUM;
    }

    private void restartGame() {
        handler.removeCallbacksAndMessages(null);
        botThinking = false;
        game.reset();
        render(false);
        announce(getString(R.string.game_restarted));
        cells[0].requestFocus();
    }

    private void render(boolean animateMove) {
        for (int index = 0; index < cells.length; index++) {
            Mark mark = game.getCell(index);
            Button cell = cells[index];
            cell.setText(mark == Mark.EMPTY ? "" : mark.name());
            boolean humanCanPlay = game.getStatus() == GameStatus.IN_PROGRESS
                    && !botThinking && (!botMode || game.getCurrentPlayer() == Mark.X);
            cell.setEnabled(humanCanPlay);
            int row = index / TicTacToeGame.BOARD_SIZE + 1;
            int column = index % TicTacToeGame.BOARD_SIZE + 1;
            String value = mark == Mark.EMPTY ? getString(R.string.empty_cell) : markName(mark);
            cell.setContentDescription(getString(R.string.cell_description, row, column, value));
            if (animateMove && mark != Mark.EMPTY) {
                cell.animate().cancel();
                cell.setScaleX(0.85f);
                cell.setScaleY(0.85f);
                cell.animate().scaleX(1f).scaleY(1f).setDuration(140).start();
            }
        }

        statusText.setText(statusMessage());
    }

    private String statusMessage() {
        if (botThinking) {
            return getString(R.string.bot_thinking);
        }
        switch (game.getStatus()) {
            case X_WON:
                return getString(R.string.player_won, markName(Mark.X));
            case O_WON:
                return getString(R.string.player_won, markName(Mark.O));
            case DRAW:
                return getString(R.string.game_draw);
            case IN_PROGRESS:
            default:
                return getString(R.string.player_turn, markName(game.getCurrentPlayer()));
        }
    }

    private String markName(Mark mark) {
        return mark == Mark.X ? getString(R.string.mark_x) : getString(R.string.mark_o);
    }

    private void announce(String message) {
        statusText.announceForAccessibility(message);
    }

    private void restoreMoves(Bundle state) {
        if (state == null) {
            return;
        }
        ArrayList<Integer> moves = state.getIntegerArrayList(STATE_MOVES);
        if (moves == null) {
            return;
        }
        for (Integer move : moves) {
            if (move != null) {
                game.play(move);
            }
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putIntegerArrayList(STATE_MOVES, new ArrayList<>(game.getMoveHistory()));
        outState.putBoolean(STATE_BOT_MODE, botMode);
        outState.putInt(STATE_DIFFICULTY, selectedDifficulty().ordinal());
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
