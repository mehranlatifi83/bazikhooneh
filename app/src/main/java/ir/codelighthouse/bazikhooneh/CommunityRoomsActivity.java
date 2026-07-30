package ir.codelighthouse.bazikhooneh;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import ir.codelighthouse.bazikhooneh.account.SessionStore;
import ir.codelighthouse.bazikhooneh.catalog.GameCatalog;
import ir.codelighthouse.bazikhooneh.catalog.GameDefinition;
import ir.codelighthouse.bazikhooneh.community.CommunityClient;
import ir.codelighthouse.bazikhooneh.community.MatchmakingController;

public final class CommunityRoomsActivity extends NavigableActivity {
    public static final String EXTRA_GAME_KEY = "game_key";
    public static final String EXTRA_ROOM_CODE = "room_code";

    private CommunityClient client;
    private MatchmakingController matchmaking;
    private LinearLayout roomList;
    private TextView status;
    private Spinner gameSelector;
    private List<GameDefinition> onlineGames;
    private String preferredGame = "";
    private String incomingCode = "";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_community_rooms);
        SessionStore store = new SessionStore(this);
        if (!store.isSignedIn()) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }

        client = new CommunityClient(this, BuildConfig.API_BASE_URL, store.token());
        matchmaking = new MatchmakingController(client);
        preferredGame = getIntent().getStringExtra(EXTRA_GAME_KEY);
        if (preferredGame == null) preferredGame = "";
        incomingCode = normalizedRoomCode(getIntent().getStringExtra(EXTRA_ROOM_CODE));

        roomList = findViewById(R.id.community_rooms_list);
        status = findViewById(R.id.community_status);
        setupGameSelector();
        findViewById(R.id.community_create).setOnClickListener(v -> createRoom());
        findViewById(R.id.community_join).setOnClickListener(v -> joinEnteredRoom());
        findViewById(R.id.community_quick_match).setOnClickListener(v -> startQuickMatch());
        showPreferredGame();
    }

    private void setupGameSelector() {
        gameSelector = findViewById(R.id.community_game);
        onlineGames = GameCatalog.onlineGames();
        List<String> titles = new ArrayList<>();
        int selected = 0;
        for (int index = 0; index < onlineGames.size(); index++) {
            GameDefinition game = onlineGames.get(index);
            titles.add(getString(game.titleRes));
            if (game.id.equals(preferredGame)) selected = index;
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, titles);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        gameSelector.setAdapter(adapter);
        gameSelector.setSelection(selected);

        boolean fixedGame = !preferredGame.isEmpty();
        findViewById(R.id.community_game_label)
                .setVisibility(fixedGame ? View.GONE : View.VISIBLE);
        gameSelector.setVisibility(fixedGame ? View.GONE : View.VISIBLE);
    }

    private void showPreferredGame() {
        GameDefinition game = GameCatalog.find(preferredGame);
        if (game != null) {
            status.setText(getString(R.string.rooms_for_game, getString(game.titleRes)));
        }
    }

    private void loadRooms() {
        setLoading(true);
        client.list((data, error) -> runOnUiThread(() -> {
            setLoading(false);
            roomList.removeAllViews();
            if (error != null) {
                show(error);
                return;
            }
            if (!incomingCode.isEmpty()) {
                String code = incomingCode;
                incomingCode = "";
                joinRoom(code);
                return;
            }
            renderRooms(data.optJSONArray("results"));
        }));
    }

    private void renderRooms(JSONArray rooms) {
        if (rooms == null || rooms.length() == 0) {
            show(getString(R.string.community_no_rooms));
            return;
        }
        for (int index = 0; index < rooms.length(); index++) {
            JSONObject room = rooms.optJSONObject(index);
            if (room != null) addRoom(room);
        }
    }

    private void addRoom(JSONObject room) {
        JSONArray members = room.optJSONArray("members");
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setMinHeight(dp(72));
        button.setText(getString(
                R.string.community_room_item,
                room.optString("title"),
                room.optString("code"),
                members == null ? 0 : members.length()));
        button.setOnClickListener(v -> {
            String code = room.optString("code");
            if (room.optJSONObject("membership") != null) openRoom(code);
            else joinRoom(code);
        });
        roomList.addView(button, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private void createRoom() {
        String title = ((EditText) findViewById(R.id.community_title))
                .getText().toString().trim();
        setLoading(true);
        client.create(title, (data, error) -> runOnUiThread(() -> {
            setLoading(false);
            if (error != null) show(error);
            else openRoom(data.optString("code"));
        }));
    }

    private void joinEnteredRoom() {
        String code = normalizedRoomCode(((EditText) findViewById(
                R.id.community_code)).getText().toString());
        if (code.isEmpty()) {
            show(getString(R.string.room_code_required));
            return;
        }
        joinRoom(code);
    }

    private void joinRoom(String code) {
        setLoading(true);
        client.join(code, (data, error) -> runOnUiThread(() -> {
            setLoading(false);
            if (error != null) {
                show(error);
            } else if ("join_requested".equals(data.optString("detail"))) {
                show(getString(R.string.join_request_sent));
            } else {
                openRoom(code);
            }
        }));
    }

    private void startQuickMatch() {
        String gameKey = selectedGameKey();
        if (gameKey.isEmpty()) {
            show(getString(R.string.error_generic));
            return;
        }
        preferredGame = gameKey;
        setLoading(true);
        show(getString(R.string.searching_for_players));
        matchmaking.start(gameKey, (data, error) -> runOnUiThread(() -> {
            if (error != null) {
                setLoading(false);
                show(error);
                return;
            }
            if (data != null && "matched".equals(data.optString("status"))) {
                setLoading(false);
                JSONObject room = data.optJSONObject("room");
                if (room == null) show(getString(R.string.error_generic));
                else openRoom(room.optString("code"));
            }
        }));
    }

    private String selectedGameKey() {
        if (!preferredGame.isEmpty()) return preferredGame;
        int position = gameSelector.getSelectedItemPosition();
        return position >= 0 && position < onlineGames.size()
                ? onlineGames.get(position).id : "";
    }

    private void openRoom(String code) {
        startActivity(new Intent(this, CommunityRoomActivity.class)
                .putExtra(EXTRA_ROOM_CODE, code)
                .putExtra(EXTRA_GAME_KEY, preferredGame));
    }

    private void setLoading(boolean loading) {
        findViewById(R.id.community_progress)
                .setVisibility(loading ? View.VISIBLE : View.GONE);
        findViewById(R.id.community_create).setEnabled(!loading);
        findViewById(R.id.community_join).setEnabled(!loading);
        findViewById(R.id.community_quick_match).setEnabled(!loading);
    }

    private void show(String text) {
        status.setText(text);
        status.announceForAccessibility(text);
    }

    private String normalizedRoomCode(String value) {
        if (value == null) return "";
        String code = value.trim().toUpperCase(Locale.ROOT);
        return code.matches("[A-Z0-9]{6}") ? code : "";
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override protected void onResume() {
        super.onResume();
        if (client != null) loadRooms();
    }

    @Override protected void onDestroy() {
        if (matchmaking != null) matchmaking.cancel(true);
        super.onDestroy();
    }
}
