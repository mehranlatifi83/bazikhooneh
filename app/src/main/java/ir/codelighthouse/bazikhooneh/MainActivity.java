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

import ir.codelighthouse.bazikhooneh.game.tictactoe.BotAction;
import ir.codelighthouse.bazikhooneh.game.tictactoe.BotDifficulty;
import ir.codelighthouse.bazikhooneh.game.tictactoe.GamePhase;
import ir.codelighthouse.bazikhooneh.game.tictactoe.GameStatus;
import ir.codelighthouse.bazikhooneh.game.tictactoe.Mark;
import ir.codelighthouse.bazikhooneh.game.tictactoe.MoveResult;
import ir.codelighthouse.bazikhooneh.game.tictactoe.TicTacToeBot;
import ir.codelighthouse.bazikhooneh.game.tictactoe.TicTacToeGame;

public final class MainActivity extends Activity {
    private static final String STATE_ACTIONS = "state_actions";
    private static final String STATE_BOT_MODE = "state_bot_mode";
    private static final String STATE_DIFFICULTY = "state_difficulty";
    private static final String STATE_SELECTED_SOURCE = "state_selected_source";
    private static final String STATE_PENDING_ANNOUNCEMENT = "state_pending_announcement";
    private static final long BOT_MOVE_DELAY_MS = 500L;

    private final TicTacToeGame game = new TicTacToeGame();
    private final TicTacToeBot bot = new TicTacToeBot();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Button[] cells = new Button[TicTacToeGame.CELL_COUNT];
    private TextView statusText;
    private Spinner difficultySpinner;
    private boolean botMode;
    private boolean botThinking;
    private int selectedSource = -1;
    private String pendingHumanAnnouncement;

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
            cells[index].setOnClickListener(view -> onCellClicked(cellIndex));
        }
        findViewById(R.id.restart_button).setOnClickListener(view -> restartGame());

        restoreState(savedInstanceState);
        render(false);
        if (botMode && game.getCurrentPlayer() == Mark.O) {
            scheduleBotAction();
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
                } else {
                    restartGame();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // Keep the current difficulty.
            }
        });
    }

    private void onCellClicked(int cellIndex) {
        if (botThinking || (botMode && game.getCurrentPlayer() == Mark.O)) {
            return;
        }
        if (game.getPhase() == GamePhase.PLACEMENT) {
            placeHumanPiece(cellIndex);
        } else {
            handleMovementSelection(cellIndex);
        }
    }

    private void placeHumanPiece(int destination) {
        Mark mark = game.getCurrentPlayer();
        MoveResult result = game.play(destination);
        if (result == MoveResult.CELL_OCCUPIED) {
            announce(getString(R.string.cell_occupied));
            return;
        }
        if (result == MoveResult.ACCEPTED) {
            completeHumanAction(mark, -1, destination);
        }
    }

    private void handleMovementSelection(int cellIndex) {
        Mark cellMark = game.getCell(cellIndex);
        Mark currentPlayer = game.getCurrentPlayer();
        if (cellMark == currentPlayer) {
            selectedSource = cellIndex;
            render(false);
            announce(getString(R.string.piece_selected, positionName(cellIndex)));
            return;
        }
        if (selectedSource < 0) {
            announce(cellMark == Mark.EMPTY ? getString(R.string.select_piece_first)
                    : getString(R.string.not_your_piece));
            return;
        }

        MoveResult result = game.move(selectedSource, cellIndex);
        if (result == MoveResult.CELL_OCCUPIED) {
            announce(getString(R.string.cell_occupied));
            return;
        }
        if (result == MoveResult.ACCEPTED) {
            int source = selectedSource;
            selectedSource = -1;
            completeHumanAction(currentPlayer, source, cellIndex);
        }
    }

    private void completeHumanAction(Mark mark, int source, int destination) {
        cells[destination].performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        String actionAnnouncement = actionAnnouncement(mark, source, destination);
        render(true);
        if (game.getStatus() != GameStatus.IN_PROGRESS) {
            announce(actionAnnouncement + " " + statusMessage());
            return;
        }
        if (botMode) {
            pendingHumanAnnouncement = actionAnnouncement;
            scheduleBotAction();
        } else {
            announce(actionAnnouncement + " " + statusMessage());
        }
    }

    private void scheduleBotAction() {
        botThinking = true;
        render(false);
        handler.postDelayed(() -> {
            BotAction action = bot.chooseAction(game, selectedDifficulty());
            String botAnnouncement = "";
            if (action != null) {
                if (action.isMovement()) {
                    game.move(action.getSource(), action.getDestination());
                } else {
                    game.play(action.getDestination());
                }
                cells[action.getDestination()].performHapticFeedback(
                        HapticFeedbackConstants.KEYBOARD_TAP);
                botAnnouncement = actionAnnouncement(Mark.O, action.getSource(),
                        action.getDestination());
            }
            botThinking = false;
            render(true);
            String humanPart = pendingHumanAnnouncement == null ? "" : pendingHumanAnnouncement + " ";
            pendingHumanAnnouncement = null;
            announce(humanPart + botAnnouncement + " " + statusMessage());
        }, BOT_MOVE_DELAY_MS);
    }

    private String actionAnnouncement(Mark mark, int source, int destination) {
        if (source >= 0) {
            return getString(R.string.piece_moved_announcement, markName(mark),
                    positionName(source), positionName(destination));
        }
        return getString(R.string.piece_placed_announcement, markName(mark),
                positionName(destination));
    }

    private String positionName(int cellIndex) {
        int row = cellIndex / TicTacToeGame.BOARD_SIZE + 1;
        int column = cellIndex % TicTacToeGame.BOARD_SIZE + 1;
        return getString(R.string.cell_position, row, column);
    }

    private BotDifficulty selectedDifficulty() {
        int position = difficultySpinner.getSelectedItemPosition();
        BotDifficulty[] values = BotDifficulty.values();
        return position >= 0 && position < values.length ? values[position] : BotDifficulty.MEDIUM;
    }

    private void restartGame() {
        handler.removeCallbacksAndMessages(null);
        botThinking = false;
        selectedSource = -1;
        pendingHumanAnnouncement = null;
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
            cell.setAlpha(index == selectedSource ? 0.6f : 1f);
            String value = mark == Mark.EMPTY ? getString(R.string.empty_cell) : markName(mark);
            String description = getString(R.string.cell_description,
                    index / TicTacToeGame.BOARD_SIZE + 1,
                    index % TicTacToeGame.BOARD_SIZE + 1, value);
            if (index == selectedSource) {
                description += ", " + getString(R.string.selected);
            }
            cell.setContentDescription(description);
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
                if (game.getPhase() == GamePhase.MOVEMENT) {
                    return getString(R.string.player_move_turn, markName(game.getCurrentPlayer()));
                }
                return getString(R.string.player_turn, markName(game.getCurrentPlayer()));
        }
    }

    private String markName(Mark mark) {
        return mark == Mark.X ? getString(R.string.mark_x) : getString(R.string.mark_o);
    }

    private void announce(String message) {
        statusText.announceForAccessibility(message.trim());
    }

    private void restoreState(Bundle state) {
        if (state == null) {
            return;
        }
        ArrayList<Integer> actions = state.getIntegerArrayList(STATE_ACTIONS);
        if (actions != null) {
            for (Integer action : actions) {
                if (action == null) {
                    continue;
                }
                if (TicTacToeGame.isMovementAction(action)) {
                    game.move(TicTacToeGame.movementSource(action),
                            TicTacToeGame.movementDestination(action));
                } else {
                    game.play(action);
                }
            }
        }
        selectedSource = state.getInt(STATE_SELECTED_SOURCE, -1);
        pendingHumanAnnouncement = state.getString(STATE_PENDING_ANNOUNCEMENT);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putIntegerArrayList(STATE_ACTIONS, new ArrayList<>(game.getActionHistory()));
        outState.putBoolean(STATE_BOT_MODE, botMode);
        outState.putInt(STATE_DIFFICULTY, selectedDifficulty().ordinal());
        outState.putInt(STATE_SELECTED_SOURCE, selectedSource);
        outState.putString(STATE_PENDING_ANNOUNCEMENT, pendingHumanAnnouncement);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
