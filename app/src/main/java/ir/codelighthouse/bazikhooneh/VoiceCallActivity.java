package ir.codelighthouse.bazikhooneh;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.*;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.webrtc.*;
import ir.codelighthouse.bazikhooneh.account.SessionStore;
import ir.codelighthouse.bazikhooneh.community.CommunityClient;

public final class VoiceCallActivity extends NavigableActivity implements CommunityClient.Events {
    private static final int AUDIO_PERMISSION = 72;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, PeerConnection> peers = new HashMap<>();
    private final Map<String, String> names = new HashMap<>();
    private final Map<String, String> usernames = new HashMap<>();
    private final Map<String, Boolean> micStates = new HashMap<>();
    private CommunityClient client;
    private PeerConnectionFactory factory;
    private AudioSource audioSource;
    private AudioTrack audioTrack;
    private String code, ownId, ownUsername;
    private boolean micEnabled = true, speakerEnabled = true, leaving;
    private boolean moderator;
    private long startedAt;
    private List<PeerConnection.IceServer> iceServers = new ArrayList<>();
    private TextView status, timer;
    private LinearLayout participants;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); setContentView(R.layout.activity_voice_call);
        code = getIntent().getStringExtra("room_code");
        SessionStore store = new SessionStore(this); ownUsername = store.username();
        if (code == null || !store.isSignedIn()) { finish(); return; }
        status=findViewById(R.id.call_status);timer=findViewById(R.id.call_timer);
        participants=findViewById(R.id.call_participants);
        client=new CommunityClient(BuildConfig.API_BASE_URL,store.token());
        findViewById(R.id.call_toggle_mic).setOnClickListener(v->toggleMic());
        findViewById(R.id.call_toggle_speaker).setOnClickListener(v->toggleSpeaker());
        findViewById(R.id.call_raise_hand).setOnClickListener(v->client.raiseHand());
        findViewById(R.id.call_leave).setOnClickListener(v->leave());
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},AUDIO_PERMISSION);
        else initializeCall();
    }

    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] results){
        super.onRequestPermissionsResult(requestCode,permissions,results);
        if(requestCode==AUDIO_PERMISSION&&results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED)initializeCall();
        else{announce(getString(R.string.call_permission_required));finish();}}

    private void initializeCall(){
        startForegroundService(new android.content.Intent(this, CallKeepAliveService.class)
                .putExtra("room_code", code));
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(this)
                .setEnableInternalTracer(false).createInitializationOptions());
        factory=PeerConnectionFactory.builder().createPeerConnectionFactory();
        MediaConstraints constraints=new MediaConstraints();
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("googEchoCancellation","true"));
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("googNoiseSuppression","true"));
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("googAutoGainControl","true"));
        audioSource=factory.createAudioSource(constraints);audioTrack=factory.createAudioTrack("audio0",audioSource);
        AudioManager manager=(AudioManager)getSystemService(Context.AUDIO_SERVICE);
        manager.setMode(AudioManager.MODE_IN_COMMUNICATION);manager.setSpeakerphoneOn(true);
        announce(getString(R.string.call_connecting));connect();handler.post(timerTask);
    }

    private void connect(){if(leaving)return;client.connect(code,this);}
    @Override public void onOpen(){client.joinCall();}
    @Override public void onEvent(JSONObject event){runOnUiThread(()->handle(event));}
    private void handle(JSONObject event){String type=event.optString("event");
        if("call_state".equals(type)){readIce(event.optJSONArray("ice_servers"));JSONObject call=event.optJSONObject("call");
            if(call!=null){startedAt=parseTime(call.optString("started_at"));JSONArray values=call.optJSONArray("participants");
                JSONArray moderators=call.optJSONArray("moderator_ids");if(moderators!=null)for(int i=0;i<moderators.length();i++)if(moderators.optString(i).equals(ownId))moderator=true;
                if(values!=null)for(int i=0;i<values.length();i++)upsert(values.optJSONObject(i));}
            if(call!=null){JSONArray moderators=call.optJSONArray("moderator_ids");if(moderators!=null)for(int i=0;i<moderators.length();i++)if(moderators.optString(i).equals(ownId))moderator=true;}
            announce(getString(R.string.call_connected));renderParticipants();}
        else if("call_participant_joined".equals(type)){JSONObject call=event.optJSONObject("call");
            if(call!=null){JSONArray values=call.optJSONArray("participants");if(values!=null)for(int i=0;i<values.length();i++)upsert(values.optJSONObject(i));}
            String id=event.optString("account_id");if(!id.equals(ownId)&&!peers.containsKey(id)){createPeer(id,true);
                announce(getString(R.string.call_participant_joined,names.getOrDefault(id,"")));}renderParticipants();}
        else if("call_participant_left".equals(type)){removePeer(event.optString("account_id"),true);}
        else if("call_signal".equals(type)){handleSignal(event);}
        else if("call_media_state".equals(type)){String id=event.optString("account_id");micStates.put(id,event.optBoolean("mic_enabled"));
            if(id.equals(ownId)&&!event.optBoolean("can_speak",true)){micEnabled=false;audioTrack.setEnabled(false);
                findViewById(R.id.call_raise_hand).setVisibility(View.VISIBLE);}renderParticipants();}
        else if("call_force_muted".equals(type)&&ownId!=null&&ownId.equals(event.optString("account_id"))){
            micEnabled=false;audioTrack.setEnabled(false);updateMicButton();findViewById(R.id.call_raise_hand).setVisibility(View.VISIBLE);
            announce(getString(R.string.call_force_muted));}
        else if("call_speak_allowed".equals(type)&&ownId!=null&&ownId.equals(event.optString("account_id"))){
            findViewById(R.id.call_raise_hand).setVisibility(View.GONE);announce(getString(R.string.raise_hand));}
        else if("call_kicked".equals(type)&&ownId!=null&&ownId.equals(event.optString("account_id"))){announce(getString(R.string.call_kicked));leave();}
        else if("call_ended".equals(type)){leave();}
        else if("error".equals(type)){announce(event.optString("error"));}}

    private void readIce(JSONArray values){iceServers.clear();if(values==null)return;
        for(int i=0;i<values.length();i++){JSONObject item=values.optJSONObject(i);if(item==null)continue;JSONArray urls=item.optJSONArray("urls");
            List<String> list=new ArrayList<>();if(urls!=null)for(int j=0;j<urls.length();j++)list.add(urls.optString(j));
            if(list.isEmpty())continue;PeerConnection.IceServer.Builder builder=PeerConnection.IceServer.builder(list);
            if(!item.optString("username").isEmpty())builder.setUsername(item.optString("username"));
            if(!item.optString("credential").isEmpty())builder.setPassword(item.optString("credential"));iceServers.add(builder.createIceServer());}}
    private void upsert(JSONObject item){if(item==null)return;String id=item.optString("id");
        names.put(id,item.optString("display_name",item.optString("username")));usernames.put(id,item.optString("username"));micStates.put(id,item.optBoolean("mic_enabled",true));
        if(item.optString("username").equalsIgnoreCase(ownUsername))ownId=id;}

    private PeerConnection createPeer(String remote,boolean offer){
        PeerConnection.RTCConfiguration config=new PeerConnection.RTCConfiguration(iceServers);
        config.sdpSemantics=PeerConnection.SdpSemantics.UNIFIED_PLAN;
        PeerConnection pc=factory.createPeerConnection(config,new Observer(remote));if(pc==null)return null;
        pc.addTrack(audioTrack,Collections.singletonList("bazikhooneh"));peers.put(remote,pc);
        if(offer)pc.createOffer(new Sdp(remote,pc,true),new MediaConstraints());return pc;}
    private void handleSignal(JSONObject event){if(ownId==null||!ownId.equals(event.optString("to")))return;
        String from=event.optString("from"),kind=event.optString("signal_type");JSONObject payload=event.optJSONObject("payload");if(payload==null)return;
        PeerConnection pc=peers.get(from);
        if("offer".equals(kind)){if(pc==null)pc=createPeer(from,false);if(pc==null)return;
            SessionDescription remote=new SessionDescription(SessionDescription.Type.OFFER,payload.optString("sdp"));
            PeerConnection finalPc=pc;pc.setRemoteDescription(new SimpleSdp(){@Override public void onSetSuccess(){
                finalPc.createAnswer(new Sdp(from,finalPc,false),new MediaConstraints());}},remote);
        }else if("answer".equals(kind)&&pc!=null)pc.setRemoteDescription(new SimpleSdp(),new SessionDescription(
                SessionDescription.Type.ANSWER,payload.optString("sdp")));
        else if("ice_candidate".equals(kind)&&pc!=null)pc.addIceCandidate(new IceCandidate(
                payload.optString("sdpMid"),payload.optInt("sdpMLineIndex"),payload.optString("candidate")));}

    private final class Observer implements PeerConnection.Observer{private final String remote;Observer(String remote){this.remote=remote;}
        @Override public void onIceCandidate(IceCandidate c){JSONObject p=new JSONObject();try{p.put("sdpMid",c.sdpMid);p.put("sdpMLineIndex",c.sdpMLineIndex);p.put("candidate",c.sdp);client.sendSignal(remote,"ice_candidate",p);}catch(Exception ignored){}}
        @Override public void onConnectionChange(PeerConnection.PeerConnectionState s){if(s==PeerConnection.PeerConnectionState.FAILED||s==PeerConnection.PeerConnectionState.CLOSED)runOnUiThread(()->removePeer(remote,false));}
        @Override public void onAddTrack(RtpReceiver receiver,MediaStream[] streams){MediaStreamTrack track=receiver.track();if(track!=null)track.setEnabled(true);}
        @Override public void onSignalingChange(PeerConnection.SignalingState s){}@Override public void onIceConnectionChange(PeerConnection.IceConnectionState s){}
        @Override public void onIceConnectionReceivingChange(boolean b){}@Override public void onIceGatheringChange(PeerConnection.IceGatheringState s){}
        @Override public void onIceCandidatesRemoved(IceCandidate[] c){}@Override public void onAddStream(MediaStream s){}@Override public void onRemoveStream(MediaStream s){}
        @Override public void onDataChannel(DataChannel d){}@Override public void onRenegotiationNeeded(){}
        @Override public void onSelectedCandidatePairChanged(CandidatePairChangeEvent event){}
    }
    private class Sdp extends SimpleSdp{final String remote;final PeerConnection pc;final boolean offer;Sdp(String r,PeerConnection p,boolean o){remote=r;pc=p;offer=o;}
        @Override public void onCreateSuccess(SessionDescription value){pc.setLocalDescription(new SimpleSdp(){@Override public void onSetSuccess(){
            JSONObject payload=new JSONObject();try{payload.put("type",value.type.canonicalForm());payload.put("sdp",value.description);
                client.sendSignal(remote,offer?"offer":"answer",payload);}catch(Exception ignored){}}},value);}}
    private static class SimpleSdp implements SdpObserver{public void onCreateSuccess(SessionDescription s){}public void onSetSuccess(){}
        public void onCreateFailure(String s){}public void onSetFailure(String s){}}
    private void removePeer(String id,boolean announce){PeerConnection pc=peers.remove(id);if(pc!=null){pc.close();pc.dispose();}
        String name=names.remove(id);usernames.remove(id);micStates.remove(id);renderParticipants();if(announce&&name!=null)announce(getString(R.string.call_participant_left,name));}
    private void renderParticipants(){participants.removeAllViews();for(String id:names.keySet()){TextView row=new TextView(this);
        row.setText(getString(R.string.call_participant_item,names.get(id),getString(Boolean.TRUE.equals(micStates.get(id))?R.string.microphone_on:R.string.microphone_off)));
        row.setTextSize(18);row.setPadding(12,12,12,12);row.setFocusable(true);
        if(moderator&&!id.equals(ownId))row.setOnLongClickListener(v->{showCallMenu(row,id);return true;});participants.addView(row);}}
    private void showCallMenu(View anchor,String id){PopupMenu menu=new PopupMenu(this,anchor);menu.getMenu().add(R.string.force_mute);
        menu.getMenu().add(R.string.allow_to_speak);menu.getMenu().add(R.string.remove_from_call);menu.setOnMenuItemClickListener(item->{String action;
            if(item.getTitle().equals(getString(R.string.force_mute)))action="force_mute";else if(item.getTitle().equals(getString(R.string.allow_to_speak)))action="allow_speak";else action="kick";
            client.moderateCall(code,usernames.getOrDefault(id,""),action,(d,e)->runOnUiThread(()->{if(e!=null)announce(e);}));return true;});menu.show();}
    private void toggleMic(){micEnabled=!micEnabled;audioTrack.setEnabled(micEnabled);client.sendMediaState(micEnabled);updateMicButton();}
    private void updateMicButton(){((Button)findViewById(R.id.call_toggle_mic)).setText(micEnabled?R.string.mute_microphone:R.string.unmute_microphone);}
    private void toggleSpeaker(){speakerEnabled=!speakerEnabled;AudioManager manager=(AudioManager)getSystemService(Context.AUDIO_SERVICE);
        manager.setSpeakerphoneOn(speakerEnabled);((Button)findViewById(R.id.call_toggle_speaker)).setText(speakerEnabled?R.string.use_earpiece:R.string.use_speaker);}
    private void announce(String text){status.setText(text);status.announceForAccessibility(text);}
    private long parseTime(String value){try{return java.time.Instant.parse(value).toEpochMilli();}catch(Exception ignored){return System.currentTimeMillis();}}
    private final Runnable timerTask=new Runnable(){public void run(){if(startedAt>0){long s=(System.currentTimeMillis()-startedAt)/1000;timer.setText(String.format(Locale.getDefault(),"%02d:%02d",s/60,s%60));}handler.postDelayed(this,1000);}};
    @Override public void onClosed(){if(!leaving){runOnUiThread(()->announce(getString(R.string.call_reconnecting)));handler.postDelayed(this::connect,1500);}}
    @Override public void onError(String error){if(!leaving){runOnUiThread(()->announce(getString(R.string.call_reconnecting)));handler.postDelayed(this::connect,2000);}}
    private void leave(){if(leaving)return;leaving=true;client.leaveCall();handler.postDelayed(this::finish,150);}
    @Override public void onBackPressed(){leave();}
    @Override protected void onDestroy(){leaving=true;handler.removeCallbacksAndMessages(null);if(client!=null)client.disconnect();
        for(PeerConnection pc:peers.values()){pc.close();pc.dispose();}peers.clear();if(audioTrack!=null)audioTrack.dispose();if(audioSource!=null)audioSource.dispose();if(factory!=null)factory.dispose();
        AudioManager manager=(AudioManager)getSystemService(Context.AUDIO_SERVICE);manager.setMode(AudioManager.MODE_NORMAL);
        stopService(new android.content.Intent(this,CallKeepAliveService.class));super.onDestroy();}
}
