package ir.codelighthouse.bazikhooneh.feature.tictactoe;

import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;
import ir.codelighthouse.bazikhooneh.BuildConfig;
import ir.codelighthouse.bazikhooneh.R;
import ir.codelighthouse.bazikhooneh.account.SessionStore;
import ir.codelighthouse.bazikhooneh.community.RoomSharing;
import ir.codelighthouse.bazikhooneh.core.preferences.AppPreferences;
import ir.codelighthouse.bazikhooneh.core.ui.NavigableActivity;
import ir.codelighthouse.bazikhooneh.game.tictactoe.BotAction;
import ir.codelighthouse.bazikhooneh.game.tictactoe.BotDifficulty;
import ir.codelighthouse.bazikhooneh.game.tictactoe.GamePhase;
import ir.codelighthouse.bazikhooneh.game.tictactoe.GameStatus;
import ir.codelighthouse.bazikhooneh.game.tictactoe.Mark;
import ir.codelighthouse.bazikhooneh.game.tictactoe.MoveResult;
import ir.codelighthouse.bazikhooneh.game.tictactoe.TicTacToeBot;
import ir.codelighthouse.bazikhooneh.game.tictactoe.TicTacToeGame;
import ir.codelighthouse.bazikhooneh.navigation.AppNavigator;
import ir.codelighthouse.bazikhooneh.online.OnlineGameClient;
import ir.codelighthouse.bazikhooneh.online.OnlineGameState;
import ir.codelighthouse.bazikhooneh.online.OnlineSession;
import ir.codelighthouse.bazikhooneh.security.SecurePreferences;
import java.util.ArrayList;
import java.util.Locale;

public final class TicTacToeGameActivity extends NavigableActivity {
  public static final String EXTRA_MODE = "game_mode";
  public static final String EXTRA_DIFFICULTY = "bot_difficulty";
  private static final String STATE_ACTIONS = "state_actions";
  private static final String STATE_BOT_MODE = "state_bot_mode";
  private static final String STATE_ONLINE_MODE = "state_online_mode";
  private static final String STATE_DIFFICULTY = "state_difficulty";
  private static final String STATE_SELECTED_SOURCE = "state_selected_source";
  private static final String STATE_PENDING_ANNOUNCEMENT = "state_pending_announcement";
  private static final long BOT_MOVE_DELAY_MS = 500L;
  private static final String ONLINE_PREFS = "online_session";
  private static final String PREF_ROOM = "room";
  private static final String PREF_SYMBOL = "symbol";
  private static final String PREF_TOKEN = "token";

  private final TicTacToeGame game = new TicTacToeGame();
  private final TicTacToeBot bot = new TicTacToeBot();
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final Button[] cells = new Button[TicTacToeGame.CELL_COUNT];
  private TextView statusText;
  private Spinner difficultySpinner;
  private LinearLayout onlineControls;
  private EditText roomCodeInput;
  private TextView roomInformation;
  private Button rematchButton;
  private boolean botMode;
  private boolean onlineMode;
  private boolean botThinking;
  private int selectedSource = -1;
  private String pendingHumanAnnouncement;
  private OnlineGameClient onlineClient;
  private SessionStore sessionStore;
  private OnlineGameState onlineState;
  private String onlineSymbol;
  private int reconnectAttempts;
  private boolean reconnectAllowed;
  private boolean onlineActionPending;
  private boolean onlineConnected;
  private boolean leavingRoom;
  private long onlineStartAt;
  private boolean opponentConnected = true;
  private String accountToken = "";
  private String communityRoomCode = "";
  private String communityRoomTitle = "";
  private ToneGenerator toneGenerator;
  private final Runnable reconnectRunnable = this::restoreOnlineSession;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);
    toneGenerator = new ToneGenerator(AudioManager.STREAM_MUSIC, 35);

    statusText = findViewById(R.id.game_status);
    difficultySpinner = findViewById(R.id.difficulty_spinner);
    onlineControls = findViewById(R.id.online_controls);
    roomCodeInput = findViewById(R.id.room_code_input);
    roomInformation = findViewById(R.id.room_information);
    rematchButton = findViewById(R.id.rematch_button);
    onlineClient = new OnlineGameClient(this, BuildConfig.API_BASE_URL, onlineListener);
    sessionStore = new SessionStore(this);
    SecurePreferences roomPreferences = SecurePreferences.open(this, ONLINE_PREFS);
    communityRoomCode = roomPreferences.getString("community_room", "");
    communityRoomTitle = roomPreferences.getString("community_title", getString(R.string.app_name));
    restoreAccountSession();
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
    rematchButton.setOnClickListener(view -> onlineClient.requestRematch());
    findViewById(R.id.leave_room_button).setOnClickListener(view -> leaveOnlineRoom());
    findViewById(R.id.copy_room_code_button).setOnClickListener(view -> copyRoomCode());
    findViewById(R.id.share_room_code_button).setOnClickListener(view -> shareRoomCode());
    findViewById(R.id.return_to_room_button)
        .setOnClickListener(view -> RoomSharing.openRoom(this, communityRoomCode));

    restoreState(savedInstanceState);
    render(false);
    if (onlineMode) {
      if (communityRoomCode.isEmpty()) {
        AppNavigator.openRooms(this, "three_piece_tic_tac_toe");
        finish();
        return;
      }
      restoreOnlineSession();
    }
    if (botMode && game.getCurrentPlayer() == Mark.O) {
      scheduleBotAction();
    }
  }

  private void configureGameOptions(Bundle state) {
    String requestedMode = getIntent().getStringExtra(EXTRA_MODE);
    botMode = state != null ? state.getBoolean(STATE_BOT_MODE, false) : "bot".equals(requestedMode);
    onlineMode =
        state != null ? state.getBoolean(STATE_ONLINE_MODE, false) : "online".equals(requestedMode);
    int difficultyPosition =
        state == null
            ? getIntent().getIntExtra(EXTRA_DIFFICULTY, BotDifficulty.MEDIUM.ordinal())
            : state.getInt(STATE_DIFFICULTY, BotDifficulty.MEDIUM.ordinal());
    RadioGroup modeGroup = findViewById(R.id.game_mode_group);
    modeGroup.check(onlineMode ? R.id.mode_online : botMode ? R.id.mode_bot : R.id.mode_local);

    ArrayAdapter<CharSequence> adapter =
        ArrayAdapter.createFromResource(
            this, R.array.bot_difficulties, android.R.layout.simple_spinner_item);
    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
    difficultySpinner.setAdapter(adapter);
    difficultySpinner.setSelection(difficultyPosition, false);
    updateModeControls();

    modeGroup.setOnCheckedChangeListener(
        (group, checkedId) -> {
          botMode = checkedId == R.id.mode_bot;
          onlineMode = checkedId == R.id.mode_online;
          reconnectAllowed = false;
          onlineClient.disconnect();
          handler.removeCallbacks(reconnectRunnable);
          onlineState = null;
          onlineSymbol = null;
          roomInformation.setText("");
          updateModeControls();
          restartGame();
          if (onlineMode) restoreOnlineSession();
        });
    difficultySpinner.setOnItemSelectedListener(
        new AdapterView.OnItemSelectedListener() {
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
    if (onlineMode) {
      onOnlineCellClicked(cellIndex);
      return;
    }
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
      announce(
          cellMark == Mark.EMPTY
              ? getString(R.string.select_piece_first)
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
    performMoveHaptic(cells[destination]);
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
    handler.postDelayed(
        () -> {
          BotAction action = bot.chooseAction(game, selectedDifficulty());
          String botAnnouncement = "";
          if (action != null) {
            if (action.isMovement()) {
              game.move(action.getSource(), action.getDestination());
            } else {
              game.play(action.getDestination());
            }
            performMoveHaptic(cells[action.getDestination()]);
            botAnnouncement =
                actionAnnouncement(Mark.O, action.getSource(), action.getDestination());
          }
          botThinking = false;
          render(true);
          String humanPart = pendingHumanAnnouncement == null ? "" : pendingHumanAnnouncement + " ";
          pendingHumanAnnouncement = null;
          announce(humanPart + botAnnouncement + " " + statusMessage());
        },
        BOT_MOVE_DELAY_MS);
  }

  private String actionAnnouncement(Mark mark, int source, int destination) {
    if (source >= 0) {
      return getString(
          R.string.piece_moved_announcement,
          markName(mark),
          positionName(source),
          positionName(destination));
    }
    return getString(R.string.piece_placed_announcement, markName(mark), positionName(destination));
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
    if (!onlineMode) {
      announce(getString(R.string.game_restarted));
    }
    cells[0].requestFocus();
  }

  private void render(boolean animateMove) {
    if (onlineMode) {
      renderOnline(animateMove);
      return;
    }
    findViewById(R.id.game_board).setVisibility(View.VISIBLE);
    for (int index = 0; index < cells.length; index++) {
      Mark mark = game.getCell(index);
      Button cell = cells[index];
      cell.setText(mark == Mark.EMPTY ? "" : mark.name());
      applyPieceAppearance(cell, mark);
      boolean humanCanPlay =
          game.getStatus() == GameStatus.IN_PROGRESS
              && !botThinking
              && (!botMode || game.getCurrentPlayer() == Mark.X);
      cell.setEnabled(humanCanPlay);
      cell.setAlpha(index == selectedSource ? 0.6f : 1f);
      String value = mark == Mark.EMPTY ? getString(R.string.empty_cell) : markName(mark);
      String description =
          getString(
              R.string.cell_description,
              index / TicTacToeGame.BOARD_SIZE + 1,
              index % TicTacToeGame.BOARD_SIZE + 1,
              value);
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
    boolean detailed =
        getSharedPreferences(AppPreferences.PREFS, MODE_PRIVATE)
            .getBoolean(AppPreferences.DETAILED_ANNOUNCEMENTS, true);
    statusText.announceForAccessibility(
        (detailed ? message : statusText.getText().toString()).trim());
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
          game.move(
              TicTacToeGame.movementSource(action), TicTacToeGame.movementDestination(action));
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
    outState.putBoolean(STATE_ONLINE_MODE, onlineMode);
    outState.putInt(STATE_DIFFICULTY, selectedDifficulty().ordinal());
    outState.putInt(STATE_SELECTED_SOURCE, selectedSource);
    outState.putString(STATE_PENDING_ANNOUNCEMENT, pendingHumanAnnouncement);
  }

  @Override
  protected void onDestroy() {
    handler.removeCallbacksAndMessages(null);
    onlineClient.disconnect();
    toneGenerator.release();
    super.onDestroy();
  }

  private void updateModeControls() {
    difficultySpinner.setEnabled(false);
    difficultySpinner.setVisibility(View.GONE);
    onlineControls.setVisibility(onlineMode ? View.VISIBLE : View.GONE);
    findViewById(R.id.restart_button).setVisibility(onlineMode ? View.GONE : View.VISIBLE);
  }

  private void onOnlineCellClicked(int index) {
    if (onlineState == null
        || !"active".equals(onlineState.roomState)
        || !onlineState.currentPlayer.equals(onlineSymbol)) {
      return;
    }
    char value = onlineState.board.charAt(index);
    if ("placement".equals(onlineState.phase)) {
      if (value == '.') {
        onlineActionPending = true;
        onlineClient.place(index);
        renderOnline(false);
      } else announce(getString(R.string.cell_occupied));
      return;
    }
    if (value == onlineSymbol.charAt(0)) {
      selectedSource = index;
      renderOnline(false);
      announce(getString(R.string.piece_selected, positionName(index)));
    } else if (selectedSource < 0) {
      announce(getString(R.string.online_select_own_piece));
    } else if (value != '.') {
      announce(getString(R.string.cell_occupied));
    } else {
      onlineActionPending = true;
      onlineClient.move(selectedSource, index);
      selectedSource = -1;
      renderOnline(false);
    }
  }

  private void renderOnline(boolean animateMove) {
    boolean hasRoom = onlineState != null;
    findViewById(R.id.room_share_actions).setVisibility(hasRoom ? View.VISIBLE : View.GONE);
    findViewById(R.id.room_management_actions).setVisibility(hasRoom ? View.VISIBLE : View.GONE);
    findViewById(R.id.return_to_room_button)
        .setVisibility(communityRoomCode.isEmpty() ? View.GONE : View.VISIBLE);
    roomCodeInput.setEnabled(!hasRoom);
    boolean showBoard = hasRoom && !"waiting".equals(onlineState.roomState);
    findViewById(R.id.game_board).setVisibility(showBoard ? View.VISIBLE : View.GONE);
    for (int index = 0; index < cells.length; index++) {
      char value = onlineState == null ? '.' : onlineState.board.charAt(index);
      Button cell = cells[index];
      cell.setText(value == '.' ? "" : String.valueOf(value));
      applyPieceAppearance(cell, value == '.' ? Mark.EMPTY : value == 'X' ? Mark.X : Mark.O);
      boolean canPlay =
          onlineState != null
              && "active".equals(onlineState.roomState)
              && android.os.SystemClock.elapsedRealtime() >= onlineStartAt
              && onlineConnected
              && onlineState.currentPlayer.equals(onlineSymbol)
              && "active".equals(onlineState.status)
              && !onlineActionPending;
      cell.setEnabled(canPlay);
      cell.setAlpha(index == selectedSource ? 0.6f : 1f);
      String readable =
          value == '.' ? getString(R.string.empty_cell) : markName(value == 'X' ? Mark.X : Mark.O);
      String description =
          getString(R.string.cell_description, index / 3 + 1, index % 3 + 1, readable);
      if (index == selectedSource) description += ", " + getString(R.string.selected);
      cell.setContentDescription(description);
      if (animateMove && value != '.') {
        cell.animate().cancel();
        cell.setScaleX(0.85f);
        cell.setScaleY(0.85f);
        cell.animate().scaleX(1f).scaleY(1f).setDuration(140).start();
      }
    }
    if (onlineState != null) statusText.setText(onlineStatusMessage());
    boolean finished = onlineState != null && "finished".equals(onlineState.roomState);
    rematchButton.setVisibility(finished ? View.VISIBLE : View.GONE);
    if (finished
        && (("X".equals(onlineSymbol) && onlineState.rematchX)
            || ("O".equals(onlineSymbol) && onlineState.rematchO))) {
      rematchButton.setEnabled(false);
      roomInformation.setText(R.string.rematch_requested);
    } else {
      rematchButton.setEnabled(true);
      boolean opponentRequested =
          onlineState != null
              && (("X".equals(onlineSymbol) && onlineState.rematchO)
                  || ("O".equals(onlineSymbol) && onlineState.rematchX));
      if (finished && opponentRequested) {
        roomInformation.setText(R.string.rematch_opponent_requested);
      }
    }
  }

  private String onlineStatusMessage() {
    if ("closed".equals(onlineState.roomState)) {
      return getString(R.string.opponent_left_room);
    }
    if ("waiting".equals(onlineState.roomState)) {
      return getString(R.string.online_waiting, onlineState.roomCode);
    }
    if (android.os.SystemClock.elapsedRealtime() < onlineStartAt)
      return getString(
          R.string.online_starting_countdown,
          Math.max(1, (onlineStartAt - android.os.SystemClock.elapsedRealtime() + 999) / 1000));
    if ("x_won".equals(onlineState.status)) return getString(R.string.player_won, markName(Mark.X));
    if ("o_won".equals(onlineState.status)) return getString(R.string.player_won, markName(Mark.O));
    String turn =
        onlineState.currentPlayer.equals(onlineSymbol)
            ? getString(
                R.string.online_your_turn, markName("X".equals(onlineSymbol) ? Mark.X : Mark.O))
            : getString(
                R.string.online_opponent_turn,
                markName("X".equals(onlineSymbol) ? Mark.X : Mark.O));
    String opponent =
        "X".equals(onlineSymbol) ? onlineState.oDisplayName : onlineState.xDisplayName;
    return opponent.isEmpty()
        ? turn
        : getString(
            R.string.online_status_with_opponent,
            turn,
            opponent,
            opponentConnected
                ? getString(R.string.online_label)
                : getString(R.string.offline_label));
  }

  private final OnlineGameClient.Listener onlineListener =
      new OnlineGameClient.Listener() {
        @Override
        public void onSession(OnlineSession session) {
          runOnUiThread(
              () -> {
                onlineSymbol = session.symbol;
                reconnectAllowed = true;
                onlineState = session.game;
                SecurePreferences secure =
                    SecurePreferences.open(TicTacToeGameActivity.this, ONLINE_PREFS);
                secure.putString(PREF_ROOM, session.game.roomCode);
                secure.putString(PREF_SYMBOL, session.symbol);
                secure.putString(PREF_TOKEN, session.token);
                roomCodeInput.setText(
                    communityRoomCode.isEmpty() ? session.game.roomCode : communityRoomCode);
                String message =
                    "waiting".equals(session.game.roomState)
                        ? getString(R.string.room_created, session.game.roomCode)
                        : getString(
                            R.string.room_joined,
                            session.game.roomCode,
                            markName("X".equals(session.symbol) ? Mark.X : Mark.O));
                roomInformation.setText(message);
                renderOnline(false);
                announce(message);
              });
        }

        @Override
        public void onState(OnlineGameState state) {
          runOnUiThread(
              () -> {
                OnlineGameState previous = onlineState;
                int oldVersion = onlineState == null ? -1 : onlineState.version;
                onlineState = state;
                if (previous != null
                    && "waiting".equals(previous.roomState)
                    && "active".equals(state.roomState)) startOnlineCountdown();
                onlineActionPending = false;
                if (leavingRoom
                    && state.outcomeReason.equals(
                        onlineSymbol.toLowerCase(Locale.ROOT) + "_left")) {
                  finishLocalLeave();
                  return;
                }
                renderOnline(state.version > oldVersion);
                String action = onlineActionAnnouncement(previous, state);
                announce(
                    action.isEmpty()
                        ? onlineStatusMessage()
                        : action + " " + onlineStatusMessage());
              });
        }

        @Override
        public void onConnected() {
          reconnectAttempts = 0;
          handler.removeCallbacks(reconnectRunnable);
          onlineConnected = true;
          onlineActionPending = onlineClient.hasPendingAction();
          runOnUiThread(() -> renderOnline(false));
        }

        @Override
        public void onDisconnected() {
          runOnUiThread(
              () -> {
                onlineConnected = false;
                if (leavingRoom) {
                  finishLocalLeave();
                  return;
                }
                statusText.setText(R.string.online_disconnected);
                onlineActionPending = onlineClient.hasPendingAction();
                announce(getString(R.string.online_disconnected));
              });
        }

        @Override
        public void onPresence(String symbol, boolean connected) {
          if (symbol.equals(onlineSymbol)) return;
          runOnUiThread(
              () -> {
                opponentConnected = connected;
                String message =
                    getString(
                        connected ? R.string.opponent_connected : R.string.opponent_disconnected);
                roomInformation.setText(message);
                if (onlineState != null) statusText.setText(onlineStatusMessage());
                announce(message);
              });
        }

        @Override
        public void onError(String error) {
          runOnUiThread(
              () -> {
                if (leavingRoom) {
                  finishLocalLeave();
                  return;
                }
                String message = onlineErrorMessage(error);
                onlineActionPending = false;
                renderOnline(false);
                statusText.setText(message);
                announce(message);
              });
        }
      };

  private void startOnlineCountdown() {
    onlineStartAt = android.os.SystemClock.elapsedRealtime() + 3000;
    renderOnline(false);
    Runnable tick =
        new Runnable() {
          public void run() {
            if (android.os.SystemClock.elapsedRealtime() < onlineStartAt) {
              statusText.setText(onlineStatusMessage());
              handler.postDelayed(this, 1000);
            } else {
              renderOnline(false);
              announce(getString(R.string.online_game_started));
            }
          }
        };
    handler.post(tick);
  }

  private void restoreOnlineSession() {
    SecurePreferences secure = SecurePreferences.open(this, ONLINE_PREFS);
    String room = secure.getString(PREF_ROOM, "");
    String symbol = secure.getString(PREF_SYMBOL, "");
    String token = secure.getString(PREF_TOKEN, "");
    if (room.isEmpty() || symbol.isEmpty() || token.isEmpty()) return;
    reconnectAllowed = true;
    onlineConnected = false;
    onlineSymbol = symbol;
    roomCodeInput.setText(communityRoomCode.isEmpty() ? room : communityRoomCode);
    statusText.setText(R.string.online_connecting);
    onlineClient.reconnect(room, token);
  }

  private String onlineActionAnnouncement(OnlineGameState previous, OnlineGameState current) {
    if (previous == null
        || current.version <= previous.version
        || previous.board.length() != current.board.length()) return "";
    int source = -1;
    int destination = -1;
    char moved = '.';
    for (int index = 0; index < current.board.length(); index++) {
      char before = previous.board.charAt(index);
      char after = current.board.charAt(index);
      if (before == after) continue;
      if (before != '.' && after == '.') source = index;
      if (before == '.' && after != '.') {
        destination = index;
        moved = after;
      }
    }
    if (destination < 0 || moved == '.') return "";
    Mark mark = moved == 'X' ? Mark.X : Mark.O;
    return actionAnnouncement(mark, source, destination);
  }

  private void leaveOnlineRoom() {
    handler.removeCallbacks(reconnectRunnable);
    reconnectAllowed = false;
    leavingRoom = true;
    onlineClient.leaveRoom();
    statusText.setText(R.string.leaving_room);
    announce(getString(R.string.leaving_room));
  }

  private void finishLocalLeave() {
    leavingRoom = false;
    onlineClient.disconnect();
    onlineConnected = false;
    SecurePreferences.open(this, ONLINE_PREFS).clear();
    onlineState = null;
    onlineSymbol = null;
    selectedSource = -1;
    roomCodeInput.setText("");
    roomInformation.setText(R.string.room_left);
    statusText.setText(R.string.room_left);
    renderOnline(false);
    announce(getString(R.string.room_left));
  }

  private void copyRoomCode() {
    String code =
        communityRoomCode.isEmpty()
            ? roomCodeInput.getText().toString().trim().toUpperCase(Locale.ROOT)
            : communityRoomCode;
    if (code.length() != 6) {
      announce(getString(R.string.room_code_required));
      return;
    }
    RoomSharing.copyCode(this, code);
    announce(getString(R.string.room_code_copied, code));
  }

  private void shareRoomCode() {
    String code =
        communityRoomCode.isEmpty()
            ? roomCodeInput.getText().toString().trim().toUpperCase(Locale.ROOT)
            : communityRoomCode;
    if (code.length() != 6) {
      announce(getString(R.string.room_code_required));
      return;
    }
    RoomSharing.share(this, communityRoomTitle, code);
  }

  private String onlineErrorMessage(String error) {
    switch (error) {
      case "room_not_found":
        return getString(R.string.error_room_not_found);
      case "room_unavailable":
        return getString(R.string.error_room_unavailable);
      case "connection_failed":
      case "not_connected":
        return getString(R.string.error_connection_failed);
      case "not_your_turn":
        return getString(R.string.error_not_your_turn);
      default:
        return getString(R.string.error_generic);
    }
  }

  @Override
  protected void onResume() {
    super.onResume();
    if (sessionStore != null) restoreAccountSession();
    if (cells[0] != null) render(false);
  }

  private void performMoveHaptic(View view) {
    if (getSharedPreferences(AppPreferences.PREFS, MODE_PRIVATE)
        .getBoolean(AppPreferences.HAPTIC, true)) {
      view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
    }
    if (getSharedPreferences(AppPreferences.PREFS, MODE_PRIVATE)
        .getBoolean(AppPreferences.SOUND, true)) {
      toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 80);
    }
  }

  private void applyPieceAppearance(Button cell, Mark mark) {
    boolean large =
        getSharedPreferences(AppPreferences.PREFS, MODE_PRIVATE)
            .getBoolean(AppPreferences.LARGE_TEXT, false);
    boolean contrast =
        getSharedPreferences(AppPreferences.PREFS, MODE_PRIVATE)
            .getBoolean(AppPreferences.HIGH_CONTRAST, false);
    cell.setTextSize(large ? 34 : 28);
    if (mark == Mark.X) cell.setTextColor(getColor(contrast ? R.color.black : R.color.piece_x));
    else if (mark == Mark.O)
      cell.setTextColor(getColor(contrast ? R.color.brand_primary_dark : R.color.piece_o));
    else cell.setTextColor(getColor(R.color.text_primary));
  }

  private void restoreAccountSession() {
    accountToken = sessionStore.token();
    onlineClient.setAccountToken(accountToken);
  }
}
