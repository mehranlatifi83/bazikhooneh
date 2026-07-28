package ir.codelighthouse.bazikhooneh;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;
import ir.codelighthouse.bazikhooneh.account.SessionStore;
import ir.codelighthouse.bazikhooneh.community.CommunityClient;

public final class CommunityRoomsActivity extends NavigableActivity {
    public static final String EXTRA_GAME_KEY="game_key";
    public static final String EXTRA_ROOM_CODE="room_code";
    private CommunityClient client;
    private LinearLayout list;
    private TextView status;
    private final Handler matchmakingHandler = new Handler(Looper.getMainLooper());
    private String preferredGame="";
    private String incomingCode="";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_community_rooms);
        SessionStore store = new SessionStore(this);
        if (!store.isSignedIn()) { startActivity(new Intent(this, LoginActivity.class)); finish(); return; }
        client = new CommunityClient(this, BuildConfig.API_BASE_URL, store.token());
        preferredGame=getIntent().getStringExtra(EXTRA_GAME_KEY);if(preferredGame==null)preferredGame="";
        list = findViewById(R.id.community_rooms_list);
        status = findViewById(R.id.community_status);
        findViewById(R.id.community_create).setOnClickListener(v -> create());
        findViewById(R.id.community_join).setOnClickListener(v -> join());
        findViewById(R.id.community_quick_match).setOnClickListener(v -> quickMatch());
        if(!preferredGame.isEmpty())status.setText(getString(R.string.rooms_for_game,
                getString("ludo".equals(preferredGame)?R.string.ludo_title:R.string.tic_tac_toe_title)));
        String incoming=getIntent().getStringExtra(EXTRA_ROOM_CODE);if(incoming!=null&&incoming.length()==6)incomingCode=incoming.toUpperCase();
        load();
    }

    private void load() {
        loading(true);
        client.list((data, error) -> runOnUiThread(() -> {
            loading(false); list.removeAllViews();
            if (error != null) { show(error); return; }
            if(!incomingCode.isEmpty()){String code=incomingCode;incomingCode="";joinCode(code);return;}
            JSONArray rooms = data.optJSONArray("results");
            if (rooms == null || rooms.length() == 0) { show(getString(R.string.community_no_rooms)); return; }
            for (int index = 0; index < rooms.length(); index++) addRoom(rooms.optJSONObject(index));
        }));
    }

    private void addRoom(JSONObject room) {
        if (room == null) return;
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(getString(R.string.community_room_item, room.optString("title"),
                room.optString("code"), room.optJSONArray("members") == null ? 0
                        : room.optJSONArray("members").length()));
        button.setMinHeight(dp(72));
        button.setOnClickListener(v -> {if(room.optJSONObject("membership")!=null)open(room.optString("code"));else joinCode(room.optString("code"));});
        list.addView(button, new LinearLayout.LayoutParams(-1, -2));
    }

    private void create() {
        String title = ((EditText) findViewById(R.id.community_title)).getText().toString().trim();
        loading(true); client.create(title, (data, error) -> runOnUiThread(() -> {
            loading(false); if (error != null) show(error); else open(data.optString("code"));
        }));
    }

    private void join() {
        String code = ((EditText) findViewById(R.id.community_code)).getText().toString().trim().toUpperCase();
        if (code.length() != 6) { show(getString(R.string.room_code_required)); return; }
        joinCode(code);
    }
    private void joinCode(String code) {
        loading(true); client.join(code, (data, error) -> runOnUiThread(() -> {
            loading(false); if (error != null) show(error); else if("join_requested".equals(data.optString("detail")))show(getString(R.string.join_request_sent));else open(code);
        }));
    }

    private void quickMatch() {
        loading(true); show(getString(R.string.searching_for_players));
        client.quickMatch(preferredGame.isEmpty()?"three_piece_tic_tac_toe":preferredGame, (data, error) -> runOnUiThread(() -> {
            if (error != null) { loading(false); show(error); return; }
            handleMatch(data);
        }));
    }

    private void handleMatch(JSONObject data) {
        if (data != null && "matched".equals(data.optString("status"))) {
            loading(false); JSONObject room=data.optJSONObject("room");
            if(room!=null)open(room.optString("code"));else show(getString(R.string.error_generic));
            return;
        }
        matchmakingHandler.postDelayed(() -> client.matchmakingStatus((next, error) -> runOnUiThread(() -> {
            if (error != null) { loading(false); show(error); } else handleMatch(next);
        })), 2000);
    }

    private void open(String code) {
        startActivity(new Intent(this, CommunityRoomActivity.class).putExtra("room_code", code).putExtra(EXTRA_GAME_KEY,preferredGame));
    }
    private void loading(boolean value) {
        findViewById(R.id.community_progress).setVisibility(value ? View.VISIBLE : View.GONE);
        findViewById(R.id.community_create).setEnabled(!value);
        findViewById(R.id.community_join).setEnabled(!value);
    }
    private void show(String text) { status.setText(text); status.announceForAccessibility(text); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    @Override protected void onResume() { super.onResume(); if (client != null) load(); }
    @Override protected void onDestroy() {
        matchmakingHandler.removeCallbacksAndMessages(null);
        if (client != null) client.cancelMatchmaking((data, error) -> { });
        super.onDestroy();
    }
}
