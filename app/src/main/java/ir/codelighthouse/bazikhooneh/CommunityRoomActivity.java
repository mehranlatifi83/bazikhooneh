package ir.codelighthouse.bazikhooneh;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.*;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import ir.codelighthouse.bazikhooneh.account.SessionStore;
import ir.codelighthouse.bazikhooneh.community.CommunityClient;
import ir.codelighthouse.bazikhooneh.community.RoomSharing;
import ir.codelighthouse.bazikhooneh.feature.tictactoe.TicTacToeGameActivity;
import ir.codelighthouse.bazikhooneh.feature.ludo.LudoOnlineGameActivity;
import ir.codelighthouse.bazikhooneh.security.SecurePreferences;

public final class CommunityRoomActivity extends NavigableActivity implements CommunityClient.Events {
    private CommunityClient client;
    private String code;
    private boolean moderator;
    private boolean callActive;
    private boolean startingGame;
    private JSONObject currentRoom;
    private long shownJoinRequest;
    private long replyToMessage;
    private LinearLayout members, messages;
    private TextView status;
    private String ownUsername;
    private final Map<Long, TextView> messageViews = new HashMap<>();
    private final Map<Long, View> messageActionViews = new HashMap<>();
    private final Map<Long, View> messageContainers = new HashMap<>();
    private final Handler reconnectHandler = new Handler(Looper.getMainLooper());
    private boolean destroyed;
    private boolean socketVerified;
    private boolean reconnectScheduled;
    private boolean everConnected;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); setContentView(R.layout.activity_community_room);
        code = getIntent().getStringExtra("room_code");
        SessionStore store = new SessionStore(this);
        ownUsername = store.username();
        if (!store.isSignedIn() || code == null) { finish(); return; }
        client = new CommunityClient(this, BuildConfig.API_BASE_URL, store.token());
        members = findViewById(R.id.community_members_list);
        messages = findViewById(R.id.community_messages_list);
        status = findViewById(R.id.community_room_status);
        findViewById(R.id.community_send).setOnClickListener(v -> send());
        findViewById(R.id.community_share).setOnClickListener(v -> shareRoom());
        findViewById(R.id.community_invite).setOnClickListener(v -> inviteFriend());
        findViewById(R.id.community_start_call).setOnClickListener(v -> startCall());
        findViewById(R.id.community_join_call).setOnClickListener(v -> openCall());
        findViewById(R.id.community_start_tic).setOnClickListener(v -> startGame("three_piece_tic_tac_toe"));
        findViewById(R.id.community_start_ludo).setOnClickListener(v -> startGame("ludo"));
        findViewById(R.id.community_join_game).setOnClickListener(v -> joinActiveGame());
        findViewById(R.id.community_history).setOnClickListener(v -> startActivity(
                new Intent(this, RoomEventsActivity.class).putExtra("room_code", code)));
        findViewById(R.id.community_settings).setOnClickListener(v -> showRoomSettings());
        findViewById(R.id.community_leave).setOnClickListener(v -> confirmLeave());
        client.details(code, (data, error) -> runOnUiThread(() -> {
            if (error != null) { show(error); return; } renderRoom(data);
        }));
        refreshMessages();
        client.connect(code, this);
    }

    private void renderRoom(JSONObject room) {
        if(room==null)return;currentRoom=room;
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
        findViewById(R.id.community_settings).setVisibility(moderator ? View.VISIBLE : View.GONE);
        JSONObject activeGame=room.optJSONObject("active_game");View panel=findViewById(R.id.community_active_game_panel);
        panel.setVisibility(activeGame==null?View.GONE:View.VISIBLE);
        if(activeGame!=null){String gameName=getString("ludo".equals(activeGame.optString("game_key"))?R.string.ludo_title:R.string.tic_tac_toe_title);
            JSONArray participants=activeGame.optJSONArray("participants");((TextView)findViewById(R.id.community_active_game_status)).setText(
                    getString(R.string.active_room_game,gameName,participants==null?0:participants.length(),activeGame.optInt("capacity")));
            ((Button)findViewById(R.id.community_join_game)).setText(activeGame.optBoolean("is_participant")?R.string.resume_active_game:R.string.join_active_game);}
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
        JSONArray requests=room.optJSONArray("pending_join_requests");
        if(moderator&&requests!=null&&requests.length()>0)showJoinRequest(requests.optJSONObject(0));
    }
    private void showJoinRequest(JSONObject request){if(request==null||shownJoinRequest==request.optLong("id"))return;shownJoinRequest=request.optLong("id");JSONObject account=request.optJSONObject("account");String name=account==null?"":account.optString("display_name");
        new android.app.AlertDialog.Builder(this).setMessage(getString(R.string.join_request_message,name))
                .setNegativeButton(R.string.reject,(d,w)->resolveRequest(request.optLong("id"),false))
                .setPositiveButton(R.string.approve,(d,w)->resolveRequest(request.optLong("id"),true)).show();}
    private void resolveRequest(long id,boolean approve){client.resolveJoinRequest(code,id,approve,(data,error)->runOnUiThread(()->{shownJoinRequest=0;if(error!=null)show(error);else client.details(code,(room,e)->runOnUiThread(()->renderRoom(room)));}));}
    private void showRoomSettings(){if(currentRoom==null)return;LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);int pad=Math.round(16*getResources().getDisplayMetrics().density);form.setPadding(pad,pad,pad,pad);
        EditText title=new EditText(this);title.setHint(R.string.community_room_name);title.setText(currentRoom.optString("title"));form.addView(title);
        CheckBox publicRoom=new CheckBox(this);publicRoom.setText(R.string.public_room);publicRoom.setChecked("public".equals(currentRoom.optString("privacy")));form.addView(publicRoom);
        CheckBox approval=new CheckBox(this);approval.setText(R.string.require_join_approval);approval.setChecked("request".equals(currentRoom.optString("join_policy")));form.addView(approval);
        new android.app.AlertDialog.Builder(this).setTitle(R.string.room_settings).setView(form).setNegativeButton(android.R.string.cancel,null).setPositiveButton(R.string.save,(d,w)->client.updateRoom(code,title.getText().toString().trim(),publicRoom.isChecked()?"public":"private",approval.isChecked()?"request":"open",(room,error)->runOnUiThread(()->{if(error!=null)show(error);else renderRoom(room);}))).show();}
    private void confirmLeave(){new android.app.AlertDialog.Builder(this).setMessage(R.string.leave_room_confirmation).setNegativeButton(android.R.string.cancel,null)
            .setPositiveButton(R.string.leave_room,(d,w)->client.leave(code,(data,error)->runOnUiThread(()->{if(error!=null)show(error);else finish();}))).show();}
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
        messages.removeAllViews(); messageViews.clear();messageActionViews.clear();messageContainers.clear(); if(values==null)return;
        for(int i=0;i<values.length();i++)addMessage(values.optJSONObject(i),false);
        scrollBottom();
    }
    private void addMessage(JSONObject item, boolean announce) {
        if(item==null)return;long id=item.optLong("id");if(id>0&&messageViews.containsKey(id))return; JSONObject sender=item.optJSONObject("sender");
        String name=sender==null?"":sender.optString("display_name");
        LinearLayout container=new LinearLayout(this);container.setOrientation(LinearLayout.VERTICAL);
        TextView row=new TextView(this);boolean deleted=item.optBoolean("deleted");row.setText(deleted?getString(R.string.deleted_message):getString(R.string.community_message_item,name,item.optString("text")));
        row.setTextSize(17);row.setPadding(12,12,12,12);row.setFocusable(true);container.addView(row);
        Button actions=new Button(this);actions.setText(getString(R.string.message_actions_for,name));actions.setAllCaps(false);
        actions.setMinHeight(dp(48));actions.setVisibility(deleted?View.GONE:View.VISIBLE);actions.setOnClickListener(v->showMessageMenu(actions,item));container.addView(actions);
        messages.addView(container);
        messageViews.put(id,row);
        messageActionViews.put(id,actions);
        messageContainers.put(id,container);
        String senderUsername=sender==null?"":sender.optString("username");
        row.setOnLongClickListener(v->{showMessageMenu(row,item);return true;});
        if(announce)row.announceForAccessibility(row.getText()); scrollBottom();
    }
    private void showMessageMenu(View anchor,JSONObject message){PopupMenu menu=new PopupMenu(this,anchor);
        JSONObject sender=message.optJSONObject("sender");boolean own=sender!=null&&sender.optString("username").equalsIgnoreCase(ownUsername);
        menu.getMenu().add(R.string.reply_message);
        if(own)menu.getMenu().add(R.string.edit_message);if(own||moderator)menu.getMenu().add(R.string.delete_message);
        menu.setOnMenuItemClickListener(item->{if(item.getTitle().equals(getString(R.string.reply_message)))selectReply(message);else if(item.getTitle().equals(getString(R.string.edit_message)))editMessage(message);else deleteMessage(message.optLong("id"));return true;});menu.show();}
    private void selectReply(JSONObject message){replyToMessage=message.optLong("id");JSONObject sender=message.optJSONObject("sender");EditText input=findViewById(R.id.community_message_input);
        input.setHint(getString(R.string.replying_to,sender==null?"":sender.optString("display_name")));input.requestFocus();}
    private void editMessage(JSONObject message){final EditText input=new EditText(this);input.setText(message.optString("text"));input.setSelection(input.length());
        new android.app.AlertDialog.Builder(this).setTitle(R.string.edit_message).setView(input).setNegativeButton(android.R.string.cancel,null)
                .setPositiveButton(R.string.save,(dialog,which)->{String value=input.getText().toString().trim();if(!value.isEmpty())client.editMessage(code,message.optLong("id"),value,(d,e)->runOnUiThread(()->{if(e!=null)show(e);}));}).show();}
    private void deleteMessage(long id){new android.app.AlertDialog.Builder(this).setMessage(R.string.delete_message_confirmation)
            .setNegativeButton(android.R.string.cancel,null).setPositiveButton(R.string.delete_message,(d,w)->client.deleteMessage(code,id,(data,error)->runOnUiThread(()->{if(error!=null)show(error);}))).show();}
    private void updateMessage(JSONObject item){long id=item.optLong("id");TextView row=messageViews.get(id);if(row==null){addMessage(item,false);return;}
        JSONObject sender=item.optJSONObject("sender");row.setText(getString(R.string.community_message_item,sender==null?"":sender.optString("display_name"),item.optString("text")));}
    private void removeMessage(long id){View container=messageContainers.remove(id);messageViews.remove(id);messageActionViews.remove(id);if(container!=null){messages.removeView(container);show(getString(R.string.message_removed));}}
    private void scrollBottom(){findViewById(R.id.community_chat_scroll).post(()->
            ((ScrollView)findViewById(R.id.community_chat_scroll)).fullScroll(View.FOCUS_DOWN));}
    private void refreshMessages(){client.messages(code,(data,error)->runOnUiThread(()->{if(error!=null){show(error);return;}renderMessages(data.optJSONArray("results"));}));}
    private void send(){EditText input=findViewById(R.id.community_message_input);String text=input.getText().toString().trim();
        if(text.isEmpty())return;Button button=findViewById(R.id.community_send);button.setEnabled(false);long reply=replyToMessage;
        client.sendMessage(code,text,reply,(message,error)->runOnUiThread(()->{button.setEnabled(true);if(error!=null){show(getString(R.string.message_send_failed,error));return;}
            addMessage(message,false);if(text.equals(input.getText().toString().trim()))input.setText("");replyToMessage=0;input.setHint(R.string.community_message_hint);}));}
    private void shareRoom() {
        String title = currentRoom == null
                ? getString(R.string.app_name) : currentRoom.optString("title");
        RoomSharing.share(this, title, code);
    }
    private void inviteFriend(){EditText username=new EditText(this);username.setHint(R.string.account_username);
        new android.app.AlertDialog.Builder(this).setTitle(R.string.invite_friend_to_room).setMessage(R.string.invite_friend_help).setView(username)
                .setNegativeButton(android.R.string.cancel,null).setPositiveButton(R.string.send_invitation,(d,w)->{String value=username.getText().toString().trim();if(value.isEmpty())return;
                    client.invite(code,value,(data,error)->runOnUiThread(()->show(error==null?getString(R.string.room_invitation_sent,value):error)));}).show();}
    private void startCall(){client.startCall(code,false,(data,error)->runOnUiThread(()->{
        if(error!=null)show(error);else openCall();}));}
    private void openCall(){startActivity(new Intent(this,VoiceCallActivity.class).putExtra("room_code",code));}
    private void joinActiveGame(){client.joinGame(code,(data,error)->runOnUiThread(()->{if(error!=null){show(error);return;}openGamePayload(data);}));}
    private void openGamePayload(JSONObject data){if(data==null)return;String key=data.optString("game_key");JSONObject player=data.optJSONObject("player");if("ludo".equals(key))openLudo(player);else openTic(player);}
    private void startGame(String key){startingGame=true;client.startGame(code,key,(data,error)->runOnUiThread(()->{
        if(error!=null){startingGame=false;show(error);return;} JSONObject player=data.optJSONObject("player");
        if("ludo".equals(key))openLudo(player);else openTic(player);}));}
    private void openTic(JSONObject player){if(player==null)return;JSONObject game=player.optJSONObject("game");
        SecurePreferences secure=SecurePreferences.open(this,"online_session");
        secure.putString("community_room", code);
        secure.putString("room",game.optString("room_code"));
        secure.putString("symbol",player.optString("symbol"));
        secure.putString("token",player.optString("reconnect_token"));
        startActivity(new Intent(this,TicTacToeGameActivity.class).putExtra(TicTacToeGameActivity.EXTRA_MODE,"online"));}
    private void openLudo(JSONObject player){if(player==null)return;
        SecurePreferences secure=SecurePreferences.open(this,"ludo_online");
        secure.putString("community_room", code);
        secure.putString("room",player.optString("room_code"));
        secure.putInt("color",player.optInt("color"));
        secure.putString("token",player.optString("reconnect_token"));
        startActivity(new Intent(this,LudoOnlineGameActivity.class));}
    private void joinSelectedGame(JSONObject event){if(startingGame){startingGame=false;return;}String name=getString("ludo".equals(event.optString("game_key"))?R.string.ludo_title:R.string.tic_tac_toe_title);
        new android.app.AlertDialog.Builder(this).setMessage(getString(R.string.room_game_started,name))
                .setNegativeButton(R.string.not_now,null).setPositiveButton(R.string.join_active_game,(d,w)->joinActiveGame()).show();}
    private void show(String text){status.setText(text);status.announceForAccessibility(text);}
    @Override public void onOpen(){runOnUiThread(()->{socketVerified=false;if(!client.ping())scheduleReconnect();});}
    @Override public void onEvent(JSONObject event){runOnUiThread(()->{String type=event.optString("event");
        if("room_state".equals(type))renderRoom(event.optJSONObject("room"));
        else if("pong".equals(type)){socketVerified=true;reconnectScheduled=false;reconnectHandler.removeCallbacksAndMessages(null);
            if(!everConnected){everConnected=true;show(getString(R.string.room_connected));}
            else{status.setText(R.string.room_connected);refreshMessages();
                client.details(code,(data,error)->runOnUiThread(()->{if(data!=null)renderRoom(data);}));}}
        else if("chat_message".equals(type))addMessage(event.optJSONObject("message"),true);
        else if("chat_edited".equals(type))updateMessage(event.optJSONObject("message"));
        else if("chat_deleted".equals(type))removeMessage(event.optLong("message_id"));
        else if("game_selected".equals(type))joinSelectedGame(event);
        else if("game_participant_joined".equals(type))client.details(code,(d,e)->runOnUiThread(()->{if(d!=null)renderRoom(d);}));
        else if("join_request".equals(type)&&moderator){JSONObject request=new JSONObject();try{request.put("id",event.optLong("request_id"));request.put("account",event.optJSONObject("account"));}catch(Exception ignored){}showJoinRequest(request);}
        else if("room_updated".equals(type))client.details(code,(d,e)->runOnUiThread(()->{if(d!=null)renderRoom(d);}));
        else if("call_started".equals(type)||"call_ended".equals(type))client.details(code,(d,e)->runOnUiThread(()->{if(d!=null)renderRoom(d);}));
        else if("member_joined".equals(type)||"member_left".equals(type)||"moderation".equals(type))client.details(code,(d,e)->runOnUiThread(()->{if(d!=null)renderRoom(d);}));
        else if("error".equals(type))show(event.optString("error",getString(R.string.error_generic)));
    });}
    @Override public void onClosed(){runOnUiThread(this::scheduleReconnect);}
    @Override public void onError(String error){runOnUiThread(this::scheduleReconnect);}
    private void scheduleReconnect(){if(destroyed||reconnectScheduled)return;reconnectScheduled=true;status.setText(R.string.room_reconnecting);reconnectHandler.removeCallbacksAndMessages(null);
        reconnectHandler.postDelayed(()->{if(reconnectScheduled&&!destroyed)status.announceForAccessibility(getString(R.string.room_reconnecting));},8000);}
    @Override protected void onResume(){super.onResume();if(client!=null)refreshMessages();}
    @Override protected void onDestroy(){destroyed=true;reconnectHandler.removeCallbacksAndMessages(null);if(client!=null)client.disconnect();super.onDestroy();}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
}
