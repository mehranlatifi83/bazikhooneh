package ir.codelighthouse.bazikhooneh.feature.room;

import android.os.Bundle;
import android.view.View;
import ir.codelighthouse.bazikhooneh.BuildConfig;
import ir.codelighthouse.bazikhooneh.NavigableActivity;
import ir.codelighthouse.bazikhooneh.R;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import ir.codelighthouse.bazikhooneh.account.SessionStore;
import ir.codelighthouse.bazikhooneh.community.CommunityClient;

public final class RoomEventsActivity extends NavigableActivity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); setContentView(R.layout.activity_room_events);
        String code=getIntent().getStringExtra("room_code");SessionStore store=new SessionStore(this);
        if(code==null||!store.isSignedIn()){finish();return;}
        TextView status=findViewById(R.id.room_events_status);LinearLayout list=findViewById(R.id.room_events_list);
        new CommunityClient(this,BuildConfig.API_BASE_URL,store.token()).events(code,(data,error)->runOnUiThread(()->{
            findViewById(R.id.room_events_progress).setVisibility(View.GONE);
            if(error!=null){status.setText(error);status.announceForAccessibility(error);return;}
            JSONArray values=data.optJSONArray("results");if(values==null||values.length()==0){status.setText(R.string.no_room_history);return;}
            status.setText(getString(R.string.room_history_count,values.length()));
            for(int i=values.length()-1;i>=0;i--){JSONObject value=values.optJSONObject(i);if(value==null)continue;
                JSONObject actor=value.optJSONObject("actor");String name=actor==null?getString(R.string.system_actor):actor.optString("display_name");
                TextView row=new TextView(this);row.setText(describe(value.optString("kind"),name));row.setTextSize(17);row.setPadding(12,16,12,16);row.setFocusable(true);list.addView(row);}
        }));
    }
    private String describe(String kind,String actor){switch(kind){
        case "room_created":return getString(R.string.event_room_created,actor);
        case "member_joined":return getString(R.string.event_member_joined,actor);
        case "member_left":return getString(R.string.event_member_left,actor);
        case "call_started":return getString(R.string.event_call_started,actor);
        case "game_selected":return getString(R.string.event_game_selected,actor);
        case "game_action":return getString(R.string.event_game_action,actor);
        default:return getString(R.string.event_generic,actor,kind.replace('_',' '));}}
}
