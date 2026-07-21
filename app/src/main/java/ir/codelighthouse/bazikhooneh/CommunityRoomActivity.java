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

public final class CommunityRoomActivity extends NavigableActivity implements CommunityClient.Events {
    private CommunityClient client;
    private String code;
    private boolean moderator;
    private boolean callActive;
    private boolean startingGame;
    private LinearLayout members, messages;
    private TextView status;
    private final Handler reconnectHandler = new Handler(Looper.getMainLooper());
    private boolean destroyed;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); setContentView(R.layout.activity_community_room);
        code = getIntent().getStringExtra("room_code");
        SessionStore store = new SessionStore(this);
        if (!store.isSignedIn() || code == null) { finish(); return; }
        client = new CommunityClient(BuildConfig.API_BASE_URL, store.token());
        members = findViewById(R.id.community_members_list);
        messages = findViewById(R.id.community_messages_list);
        status = findViewById(R.id.community_room_status);
        findViewById(R.id.community_send).setOnClickListener(v -> send());
        findViewById(R.id.community_start_call).setOnClickListener(v -> startCall());
        findViewById(R.id.community_join_call).setOnClickListener(v -> openCall());
        findViewById(R.id.community_start_tic).setOnClickListener(v -> startGame("three_piece_tic_tac_toe"));
        findViewById(R.id.community_start_ludo).setOnClickListener(v -> startGame("ludo"));
        client.details(code, (data, error) -> runOnUiThread(() -> {
            if (error != null) { show(error); return; } renderRoom(data);
        }));
        client.messages(code, (data, error) -> runOnUiThread(() -> {
            if (data != null) renderMessages(data.optJSONArray("results"));
        }));
        client.connect(code, this);
    }

    private void renderRoom(JSONObject room) {
        ((TextView)findViewById(R.id.community_room_header)).setText(
                room.optString("title") + " — " + code);
        JSONObject own = room.optJSONObject("membership");
        String role = own == null ? "member" : own.optString("role");
        moderator = "owner".equals(role) || "admin".equals(role);
        callActive = room.optJSONObject("active_call") != null;
        findViewById(R.id.community_start_call).setVisibility(moderator && !callActive ? View.VISIBLE : View.GONE);
        findViewById(R.id.community_join_call).setVisibility(callActive ? View.VISIBLE : View.GONE);
        findViewById(R.id.community_start_tic).setVisibility(moderator ? View.VISIBLE : View.GONE);
        findViewById(R.id.community_start_ludo).setVisibility(moderator ? View.VISIBLE : View.GONE);
        members.removeAllViews();
        JSONArray values = room.optJSONArray("members");
        if (values != null) for (int i=0; i<values.length(); i++) {
            JSONObject item=values.optJSONObject(i); if(item==null)continue;
            TextView row=new TextView(this); row.setText(getString(R.string.community_member_item,
                    item.optString("display_name"),item.optString("username"),item.optString("role")));
            row.setTextSize(16);row.setPadding(8,8,8,8);row.setFocusable(true);members.addView(row);
            if(moderator&&!item.optString("username").equalsIgnoreCase(new SessionStore(this).username())){
                row.setOnLongClickListener(v->{showMemberMenu(row,item.optString("username"),item.optString("role"));return true;});}
        }
    }
    private void showMemberMenu(View anchor,String username,String role){PopupMenu menu=new PopupMenu(this,anchor);
        menu.getMenu().add(R.string.mute_room_chat);menu.getMenu().add(R.string.ban_from_call);menu.getMenu().add(R.string.ban_from_room);
        if("member".equals(role))menu.getMenu().add(R.string.make_room_admin);else if("admin".equals(role))menu.getMenu().add(R.string.remove_room_admin);
        menu.setOnMenuItemClickListener(item->{int id=item.getTitle().toString().hashCode();String action;
            if(item.getTitle().equals(getString(R.string.mute_room_chat)))action="mute_chat";
            else if(item.getTitle().equals(getString(R.string.ban_from_call)))action="ban_call";
            else if(item.getTitle().equals(getString(R.string.ban_from_room)))action="ban";
            else if(item.getTitle().equals(getString(R.string.make_room_admin)))action="promote";else action="demote";
            client.moderateRoom(code,username,action,(d,e)->runOnUiThread(()->{if(e!=null)show(e);else client.details(code,(r,x)->runOnUiThread(()->{if(r!=null)renderRoom(r);}));}));return true;});menu.show();}

    private void renderMessages(JSONArray values) {
        messages.removeAllViews(); if(values==null)return;
        for(int i=0;i<values.length();i++)addMessage(values.optJSONObject(i),false);
        scrollBottom();
    }
    private void addMessage(JSONObject item, boolean announce) {
        if(item==null)return; JSONObject sender=item.optJSONObject("sender");
        String name=sender==null?"":sender.optString("display_name");
        TextView row=new TextView(this);row.setText(getString(R.string.community_message_item,name,item.optString("text")));
        row.setTextSize(17);row.setPadding(12,12,12,12);row.setFocusable(true);messages.addView(row);
        if(announce)row.announceForAccessibility(row.getText()); scrollBottom();
    }
    private void scrollBottom(){findViewById(R.id.community_chat_scroll).post(()->
            ((ScrollView)findViewById(R.id.community_chat_scroll)).fullScroll(View.FOCUS_DOWN));}
    private void send(){EditText input=findViewById(R.id.community_message_input);String text=input.getText().toString().trim();
        if(!text.isEmpty()){client.sendChat(text);input.setText("");}}
    private void startCall(){client.startCall(code,false,(data,error)->runOnUiThread(()->{
        if(error!=null)show(error);else openCall();}));}
    private void openCall(){startActivity(new Intent(this,VoiceCallActivity.class).putExtra("room_code",code));}
    private void startGame(String key){startingGame=true;client.startGame(code,key,(data,error)->runOnUiThread(()->{
        if(error!=null){startingGame=false;show(error);return;} JSONObject player=data.optJSONObject("player");
        if("ludo".equals(key))openLudo(player);else openTic(player);}));}
    private void openTic(JSONObject player){if(player==null)return;JSONObject game=player.optJSONObject("game");
        getSharedPreferences("online_session",MODE_PRIVATE).edit().putString("room",game.optString("room_code"))
                .putString("symbol",player.optString("symbol")).putString("token",player.optString("reconnect_token")).apply();
        startActivity(new Intent(this,MainActivity.class).putExtra(MainActivity.EXTRA_MODE,"online"));}
    private void openLudo(JSONObject player){if(player==null)return;
        getSharedPreferences("ludo_online",MODE_PRIVATE).edit().putString("room",player.optString("room_code"))
                .putInt("color",player.optInt("color")).putString("token",player.optString("reconnect_token")).apply();
        startActivity(new Intent(this,LudoOnlineActivity.class));}
    private void joinSelectedGame(JSONObject event){String key=event.optString("game_key"),legacy=event.optString("legacy_room_code");
        if(startingGame){startingGame=false;return;} if(legacy.isEmpty())return; CommunityClient.Callback cb=(data,error)->runOnUiThread(()->{
            if(error!=null)show(error);else if("ludo".equals(key))openLudo(data);else openTic(data);});
        if("ludo".equals(key))client.joinLudo(legacy,cb);else client.joinTicTacToe(legacy,cb);}
    private void show(String text){status.setText(text);status.announceForAccessibility(text);}
    @Override public void onOpen(){runOnUiThread(()->show(getString(R.string.room_connected)));}
    @Override public void onEvent(JSONObject event){runOnUiThread(()->{String type=event.optString("event");
        if("room_state".equals(type))renderRoom(event.optJSONObject("room"));
        else if("chat_message".equals(type))addMessage(event.optJSONObject("message"),true);
        else if("game_selected".equals(type))joinSelectedGame(event);
        else if("call_started".equals(type)||"call_ended".equals(type))client.details(code,(d,e)->runOnUiThread(()->{if(d!=null)renderRoom(d);}));
        else if("member_joined".equals(type)||"member_left".equals(type)||"moderation".equals(type))client.details(code,(d,e)->runOnUiThread(()->{if(d!=null)renderRoom(d);}));
    });}
    @Override public void onClosed(){runOnUiThread(()->{show(getString(R.string.room_disconnected));scheduleReconnect();});}
    @Override public void onError(String error){runOnUiThread(()->{show(error);scheduleReconnect();});}
    private void scheduleReconnect(){reconnectHandler.removeCallbacksAndMessages(null);if(!destroyed)reconnectHandler.postDelayed(()->client.connect(code,this),2000);}
    @Override protected void onDestroy(){destroyed=true;reconnectHandler.removeCallbacksAndMessages(null);if(client!=null)client.disconnect();super.onDestroy();}
}
