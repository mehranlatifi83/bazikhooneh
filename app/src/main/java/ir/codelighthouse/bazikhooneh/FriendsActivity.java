package ir.codelighthouse.bazikhooneh;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import ir.codelighthouse.bazikhooneh.account.AccountManagementClient;
import ir.codelighthouse.bazikhooneh.account.AccountErrorMessages;
import ir.codelighthouse.bazikhooneh.account.SessionStore;
import ir.codelighthouse.bazikhooneh.online.OnlineSession;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public final class FriendsActivity extends NavigableActivity {
    private AccountManagementClient client; private LinearLayout list; private TextView status;
    @Override protected void onCreate(Bundle state){super.onCreate(state);setContentView(R.layout.activity_friends);SessionStore store=new SessionStore(this);
        if(!store.isSignedIn()){startActivity(new Intent(this,LoginActivity.class));finish();return;}
        client=new AccountManagementClient(BuildConfig.API_BASE_URL,store.token(),listener);list=findViewById(R.id.friends_list);status=findViewById(R.id.friends_status);
        findViewById(R.id.add_friend).setOnClickListener(v->send("friend","/api/v1/accounts/friends/"));
        findViewById(R.id.invite_friend).setOnClickListener(v->send("invite","/api/v1/accounts/invites/"));load();}
    private void load(){client.get("friends","/api/v1/accounts/friends/");client.get("invites","/api/v1/accounts/invites/");}
    private void send(String op,String path){try{client.post(op,path,new JSONObject().put("username",((EditText)findViewById(R.id.friend_username)).getText().toString().trim()));}catch(JSONException ignored){}}
    private void renderFriends(JSONObject json){list.removeAllViews();JSONArray friends=json.optJSONArray("friends"),requests=json.optJSONArray("requests");
        for(int i=0;friends!=null&&i<friends.length();i++){JSONObject f=friends.optJSONObject(i);addText(getString(R.string.friend_item,f.optString("display_name"),f.optString("username"),f.optBoolean("online")?getString(R.string.online_label):getString(R.string.offline_label)));}
        for(int i=0;requests!=null&&i<requests.length();i++){JSONObject f=requests.optJSONObject(i);Button b=new Button(this);b.setText(getString(R.string.accept_friend,f.optString("display_name")));int id=f.optInt("request_id");b.setOnClickListener(v->client.post("accept","/api/v1/accounts/friends/requests/"+id+"/accept/",new JSONObject()));list.addView(b);}}
    private void renderInvites(JSONObject json){JSONArray values=json.optJSONArray("results");for(int i=0;values!=null&&i<values.length();i++){JSONObject invite=values.optJSONObject(i),sender=invite.optJSONObject("sender");Button b=new Button(this);b.setText(getString(R.string.join_friend_invite,sender.optString("display_name"),invite.optString("room_code")));String code=invite.optString("room_code");b.setOnClickListener(v->startActivity(new Intent(this,OnlineLobbyActivity.class).putExtra("room_code",code)));list.addView(b);}}
    private void addText(String value){TextView text=new TextView(this);text.setText(value);text.setTextSize(18);text.setPadding(0,16,0,16);list.addView(text);}
    private final AccountManagementClient.Listener listener=new AccountManagementClient.Listener(){public void onSuccess(String op,JSONObject json){runOnUiThread(()->{if("friends".equals(op))renderFriends(json);else if("invites".equals(op))renderInvites(json);else if("invite".equals(op)){try{OnlineSession s=OnlineSession.from(json);getSharedPreferences("online_session",MODE_PRIVATE).edit().putString("room",s.game.roomCode).putString("symbol",s.symbol).putString("token",s.token).apply();startActivity(new Intent(FriendsActivity.this,MainActivity.class).putExtra(MainActivity.EXTRA_MODE,"online"));}catch(JSONException e){status.setText(R.string.error_generic);}}else{status.setText(R.string.changes_saved);load();}});}public void onError(String e){runOnUiThread(()->status.setText(AccountErrorMessages.get(FriendsActivity.this,e)));}};
}
