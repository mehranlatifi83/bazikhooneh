package ir.codelighthouse.bazikhooneh;

import android.os.Bundle;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;

import ir.codelighthouse.bazikhooneh.game.tictactoe.GameStatus;
import ir.codelighthouse.bazikhooneh.game.tictactoe.Mark;
import ir.codelighthouse.bazikhooneh.game.tictactoe.MoveResult;
import ir.codelighthouse.bazikhooneh.game.tictactoe.TicTacToeGame;

public final class MainActivity extends AppCompatActivity {
    private static final String STATE_MOVES = "state_moves";

    private final TicTacToeGame game = new TicTacToeGame();
    private final Button[] cells = new Button[TicTacToeGame.CELL_COUNT];
    private TextView statusText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.game_status);
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

        restoreGame(savedInstanceState);
        render(false);
    }

    private void playMove(int cellIndex) {
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
    }

    private void restartGame() {
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
            cell.setEnabled(game.getStatus() == GameStatus.IN_PROGRESS);
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

    private void restoreGame(Bundle state) {
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
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putIntegerArrayList(STATE_MOVES, new ArrayList<>(game.getMoveHistory()));
    }
}
