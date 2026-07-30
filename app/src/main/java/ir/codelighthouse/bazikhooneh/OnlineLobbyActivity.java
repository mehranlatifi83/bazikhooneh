package ir.codelighthouse.bazikhooneh;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import ir.codelighthouse.bazikhooneh.account.SessionStore;
import ir.codelighthouse.bazikhooneh.online.OnlineGameClient;
import ir.codelighthouse.bazikhooneh.feature.tictactoe.TicTacToeGameActivity;
import ir.codelighthouse.bazikhooneh.online.OnlineGameState;
import ir.codelighthouse.bazikhooneh.online.OnlineSession;
import ir.codelighthouse.bazikhooneh.security.SecurePreferences;

public final class OnlineLobbyActivity extends NavigableActivity {
    private EditText codeInput;
    private TextView status;
    private OnlineGameClient client;
    private boolean openingGame;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_online_lobby);
        codeInput = findViewById(R.id.lobby_room_code);
        status = findViewById(R.id.lobby_status);
        SessionStore store = new SessionStore(this);
        if (!store.isSignedIn()) { startActivity(new Intent(this, LoginActivity.class)); finish(); return; }
        client = new OnlineGameClient(this, BuildConfig.API_BASE_URL, listener);
        client.setAccountToken(store.token());
        String invitedCode = getIntent().getStringExtra("room_code");
        if (invitedCode != null) codeInput.setText(invitedCode);
        findViewById(R.id.lobby_create).setOnClickListener(v -> { setLoading(true); client.createRoom(); });
        findViewById(R.id.lobby_join).setOnClickListener(v -> join());
    }

    private void join() {
        String code = codeInput.getText().toString().trim().toUpperCase();
        if (code.length() != 6) { showStatus(getString(R.string.room_code_required)); return; }
        setLoading(true);
        client.joinRoom(code);
    }

    private void setLoading(boolean loading) {
        findViewById(R.id.lobby_progress).setVisibility(loading ? View.VISIBLE : View.GONE);
        findViewById(R.id.lobby_create).setEnabled(!loading);
        findViewById(R.id.lobby_join).setEnabled(!loading);
        if (loading) showStatus(getString(R.string.online_connecting));
    }

    private void showStatus(String value) {
        status.setText(value);
        status.announceForAccessibility(value);
    }

    private final OnlineGameClient.Listener listener = new OnlineGameClient.Listener() {
        @Override public void onSession(OnlineSession session) {
            runOnUiThread(() -> {
                SecurePreferences secure = SecurePreferences.open(OnlineLobbyActivity.this, "online_session");
                secure.putString("room", session.game.roomCode);
                secure.putString("symbol", session.symbol);
                secure.putString("token", session.token);
                openingGame = true;
                startActivity(new Intent(
                        OnlineLobbyActivity.this, TicTacToeGameActivity.class)
                        .putExtra(TicTacToeGameActivity.EXTRA_MODE, "online"));
                finish();
            });
        }
        @Override public void onState(OnlineGameState state) { }
        @Override public void onConnected() { }
        @Override public void onDisconnected() { if (!openingGame) runOnUiThread(() -> setLoading(false)); }
        @Override public void onPresence(String symbol, boolean connected) { }
        @Override public void onError(String error) {
            runOnUiThread(() -> { setLoading(false); showStatus(getString(R.string.online_error, error)); });
        }
    };

    @Override protected void onDestroy() {
        if (client != null) client.disconnect();
        super.onDestroy();
    }
}
