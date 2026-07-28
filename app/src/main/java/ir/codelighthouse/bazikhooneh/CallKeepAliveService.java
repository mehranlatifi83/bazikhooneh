package ir.codelighthouse.bazikhooneh;

import android.app.*;
import android.content.*;
import android.media.*;
import android.net.*;
import android.os.*;
import androidx.annotation.Nullable;
import java.util.*;
import org.json.*;
import org.webrtc.*;
import ir.codelighthouse.bazikhooneh.account.SessionStore;
import ir.codelighthouse.bazikhooneh.community.CommunityClient;

/**
 * Owns the complete call session. Activities are disposable views of this service, so rotation,
 * backgrounding, or opening another screen does not tear down WebRTC or its signalling socket.
 */
public final class CallKeepAliveService extends Service implements CommunityClient.Events {
    public static final String EXTRA_ROOM_CODE = "room_code";
    public static final String ACTION_START = "ir.codelighthouse.bazikhooneh.call.START";
    public static final String ACTION_LEAVE = "ir.codelighthouse.bazikhooneh.call.LEAVE";
    private static final String CHANNEL = "active_calls";
    private static final int NOTIFICATION_ID = 4301;

    public interface Listener {
        void onSnapshot(Snapshot snapshot);
        void onAnnouncement(String text);
        void onCallFinished();
    }
    public static final class Participant {
        public final String id, name, username;
        public final boolean micEnabled;
        Participant(String id,String name,String username,boolean micEnabled){
            this.id=id;this.name=name;this.username=username;this.micEnabled=micEnabled;
        }
    }
    public static final class Snapshot {
        public final String roomCode, status;
        public final boolean connected, micEnabled, speakerEnabled, moderator, canSpeak;
        public final long startedAt;
        public final List<Participant> participants;
        Snapshot(String roomCode,String status,boolean connected,boolean micEnabled,
                 boolean speakerEnabled,boolean moderator,boolean canSpeak,long startedAt,
                 List<Participant> participants){
            this.roomCode=roomCode;this.status=status;this.connected=connected;
            this.micEnabled=micEnabled;this.speakerEnabled=speakerEnabled;
            this.moderator=moderator;this.canSpeak=canSpeak;this.startedAt=startedAt;
            this.participants=Collections.unmodifiableList(participants);
        }
    }
    public final class LocalBinder extends Binder {
        public CallKeepAliveService service(){return CallKeepAliveService.this;}
    }

    private final IBinder binder=new LocalBinder();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Set<Listener> listeners=new HashSet<>();
    private final Map<String,PeerConnection> peers=new HashMap<>();
    private final Map<String,String> names=new LinkedHashMap<>(), usernames=new HashMap<>();
    private final Map<String,Boolean> micStates=new HashMap<>();
    private final Map<String,List<IceCandidate>> pendingCandidates=new HashMap<>();
    private final List<PeerConnection.IceServer> iceServers=new ArrayList<>();
    private CommunityClient client;
    private PeerConnectionFactory factory;
    private AudioSource audioSource;
    private org.webrtc.AudioTrack audioTrack;
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private String code,ownId,ownUsername,statusText;
    private boolean initialized,leaving,connected,micEnabled=true,speakerEnabled,moderator,canSpeak=true;
    private long startedAt;

    @Override public void onCreate(){
        super.onCreate();
        NotificationManager manager=getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL,
                getString(R.string.voice_call_title),NotificationManager.IMPORTANCE_LOW));
        audioManager=(AudioManager)getSystemService(AUDIO_SERVICE);
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent!=null&&ACTION_LEAVE.equals(intent.getAction())){leave();return START_NOT_STICKY;}
        String requested=intent==null?null:intent.getStringExtra(EXTRA_ROOM_CODE);
        if(requested!=null&&!requested.trim().isEmpty()){
            showNotification(requested,getString(R.string.call_connecting));
            if(!initialized)initialize(requested);
        } else if(initialized) showNotification(code,statusText);
        else stopSelf();
        return initialized?START_REDELIVER_INTENT:START_NOT_STICKY;
    }
    @Nullable @Override public IBinder onBind(Intent intent){return binder;}
    @Override public boolean onUnbind(Intent intent){return true;}
    @Override public void onRebind(Intent intent){super.onRebind(intent);}

    public void addListener(Listener listener){
        listeners.add(listener);listener.onSnapshot(snapshot());
    }
    public void removeListener(Listener listener){listeners.remove(listener);}
    public String roomCode(){return code;}

    private void initialize(String roomCode){
        SessionStore store=new SessionStore(this);
        if(!store.isSignedIn()){stopSelf();return;}
        initialized=true;code=roomCode;ownUsername=store.username();
        statusText=getString(R.string.call_connecting);
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(this)
                .setEnableInternalTracer(false).createInitializationOptions());
        factory=PeerConnectionFactory.builder().createPeerConnectionFactory();
        MediaConstraints constraints=new MediaConstraints();
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("googEchoCancellation","true"));
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("googNoiseSuppression","true"));
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("googAutoGainControl","true"));
        audioSource=factory.createAudioSource(constraints);
        audioTrack=factory.createAudioTrack("audio0",audioSource);
        requestAudioFocus();
        speakerEnabled=!hasPrivateAudioRoute();
        applyAudioRoute();
        client=new CommunityClient(this,BuildConfig.API_BASE_URL,store.token());
        registerNetworkCallback();
        client.connect(code,this);
        publish();
    }

    @Override public void onOpen(){main.post(()->{if(!leaving)client.joinCall();});}
    @Override public void onEvent(JSONObject event){main.post(()->handle(event));}
    @Override public void onClosed(){main.post(this::markReconnecting);}
    @Override public void onError(String error){main.post(this::markReconnecting);}

    private void handle(JSONObject event){
        String type=event.optString("event");
        if("call_state".equals(type)){
            readIce(event.optJSONArray("ice_servers"));JSONObject call=event.optJSONObject("call");
            if(call!=null){
                startedAt=parseTime(call.optString("started_at"));readParticipants(call.optJSONArray("participants"));
                readModerator(call.optJSONArray("moderator_ids"));
                for(String id:new ArrayList<>(names.keySet()))
                    if(!id.equals(ownId)&&!peers.containsKey(id))createPeer(id,shouldOffer(id));
            }
            connected=true;statusText=getString(R.string.call_connected);publishAndAnnounce(statusText);
        }else if("call_participant_joined".equals(type)){
            JSONObject call=event.optJSONObject("call");if(call!=null)readParticipants(call.optJSONArray("participants"));
            String id=event.optString("account_id");
            if(!id.equals(ownId)&&!peers.containsKey(id))createPeer(id,shouldOffer(id));
            String name=names.get(id);publish();if(name!=null)announce(getString(R.string.call_participant_joined,name));
        }else if("call_participant_left".equals(type))removePeer(event.optString("account_id"),true);
        else if("call_signal".equals(type))handleSignal(event);
        else if("call_media_state".equals(type)){
            String id=event.optString("account_id");micStates.put(id,event.optBoolean("mic_enabled"));
            if(id.equals(ownId)){canSpeak=event.optBoolean("can_speak",true);if(!canSpeak)setMic(false,true);}
            publish();
        }else if("call_force_muted".equals(type)&&isOwn(event)){
            canSpeak=false;setMic(false,true);announce(getString(R.string.call_force_muted));
        }else if("call_speak_allowed".equals(type)&&isOwn(event)){
            canSpeak=true;publish();announce(getString(R.string.raise_hand));
        }else if("call_kicked".equals(type)&&isOwn(event)){
            announce(getString(R.string.call_kicked));finishCall(false);
        }else if("call_ended".equals(type))finishCall(false);
        else if("error".equals(type)){String error=event.optString("error");if(!error.isEmpty())announce(error);}
    }
    private boolean isOwn(JSONObject event){return ownId!=null&&ownId.equals(event.optString("account_id"));}
    private boolean shouldOffer(String remote){return ownId!=null&&ownId.compareTo(remote)<0;}
    private void readParticipants(JSONArray values){
        if(values==null)return;
        Set<String> present=new HashSet<>();
        for(int i=0;i<values.length();i++){JSONObject item=values.optJSONObject(i);if(item==null)continue;
            String id=item.optString("id");if(id.isEmpty())continue;present.add(id);
            String username=item.optString("username");
            names.put(id,item.optString("display_name",username));usernames.put(id,username);
            micStates.put(id,item.optBoolean("mic_enabled",true));
            if(username.equalsIgnoreCase(ownUsername))ownId=id;
        }
        for(String id:new ArrayList<>(names.keySet()))if(!present.contains(id))removePeer(id,false);
    }
    private void readModerator(JSONArray values){
        moderator=false;if(values==null)return;
        for(int i=0;i<values.length();i++)if(values.optString(i).equals(ownId)){moderator=true;break;}
    }
    private void readIce(JSONArray values){
        iceServers.clear();if(values==null)return;
        for(int i=0;i<values.length();i++){JSONObject item=values.optJSONObject(i);if(item==null)continue;
            JSONArray urls=item.optJSONArray("urls");List<String> list=new ArrayList<>();
            if(urls!=null)for(int j=0;j<urls.length();j++)list.add(urls.optString(j));
            if(list.isEmpty())continue;PeerConnection.IceServer.Builder builder=PeerConnection.IceServer.builder(list);
            if(!item.optString("username").isEmpty())builder.setUsername(item.optString("username"));
            if(!item.optString("credential").isEmpty())builder.setPassword(item.optString("credential"));
            iceServers.add(builder.createIceServer());
        }
    }
    private PeerConnection createPeer(String remote,boolean offer){
        if(factory==null||audioTrack==null)return null;
        PeerConnection.RTCConfiguration config=new PeerConnection.RTCConfiguration(iceServers);
        config.sdpSemantics=PeerConnection.SdpSemantics.UNIFIED_PLAN;
        PeerConnection pc=factory.createPeerConnection(config,new Observer(remote));if(pc==null)return null;
        pc.addTrack(audioTrack,Collections.singletonList("bazikhooneh"));peers.put(remote,pc);
        if(offer)createOffer(remote,pc,false);return pc;
    }
    private void createOffer(String remote,PeerConnection pc,boolean iceRestart){
        MediaConstraints constraints=new MediaConstraints();
        if(iceRestart)constraints.mandatory.add(new MediaConstraints.KeyValuePair("IceRestart","true"));
        pc.createOffer(new Sdp(remote,pc,true),constraints);
    }
    private void handleSignal(JSONObject event){
        if(ownId==null||!ownId.equals(event.optString("to")))return;
        String from=event.optString("from"),kind=event.optString("signal_type");
        JSONObject payload=event.optJSONObject("payload");if(payload==null)return;PeerConnection pc=peers.get(from);
        if("offer".equals(kind)){
            if(pc==null)pc=createPeer(from,false);if(pc==null)return;PeerConnection finalPc=pc;
            pc.setRemoteDescription(new SimpleSdp(){@Override public void onSetSuccess(){
                flushCandidates(from,finalPc);finalPc.createAnswer(new Sdp(from,finalPc,false),new MediaConstraints());
            }},new SessionDescription(SessionDescription.Type.OFFER,payload.optString("sdp")));
        }else if("answer".equals(kind)&&pc!=null){PeerConnection finalPc=pc;
            pc.setRemoteDescription(new SimpleSdp(){@Override public void onSetSuccess(){flushCandidates(from,finalPc);}},
                    new SessionDescription(SessionDescription.Type.ANSWER,payload.optString("sdp")));
        }else if("ice_candidate".equals(kind)){
            IceCandidate candidate=new IceCandidate(payload.optString("sdpMid"),
                    payload.optInt("sdpMLineIndex"),payload.optString("candidate"));
            if(pc!=null&&pc.getRemoteDescription()!=null)pc.addIceCandidate(candidate);
            else pendingCandidates.computeIfAbsent(from,k->new ArrayList<>()).add(candidate);
        }
    }
    private void flushCandidates(String remote,PeerConnection pc){
        List<IceCandidate> values=pendingCandidates.remove(remote);
        if(values!=null)for(IceCandidate candidate:values)pc.addIceCandidate(candidate);
    }
    private final class Observer implements PeerConnection.Observer{
        private final String remote;Observer(String remote){this.remote=remote;}
        @Override public void onIceCandidate(IceCandidate c){JSONObject p=new JSONObject();try{
            p.put("sdpMid",c.sdpMid);p.put("sdpMLineIndex",c.sdpMLineIndex);p.put("candidate",c.sdp);
            client.sendSignal(remote,"ice_candidate",p);}catch(Exception ignored){}}
        @Override public void onConnectionChange(PeerConnection.PeerConnectionState state){
            if(state==PeerConnection.PeerConnectionState.FAILED)main.post(()->restartPeer(remote));
            else if(state==PeerConnection.PeerConnectionState.CLOSED)main.post(()->removePeer(remote,false));
        }
        @Override public void onAddTrack(RtpReceiver receiver,MediaStream[] streams){
            MediaStreamTrack track=receiver.track();if(track!=null)track.setEnabled(true);
        }
        @Override public void onSignalingChange(PeerConnection.SignalingState s){}
        @Override public void onIceConnectionChange(PeerConnection.IceConnectionState s){}
        @Override public void onIceConnectionReceivingChange(boolean b){}
        @Override public void onIceGatheringChange(PeerConnection.IceGatheringState s){}
        @Override public void onIceCandidatesRemoved(IceCandidate[] c){}
        @Override public void onAddStream(MediaStream s){} @Override public void onRemoveStream(MediaStream s){}
        @Override public void onDataChannel(DataChannel d){} @Override public void onRenegotiationNeeded(){}
        @Override public void onSelectedCandidatePairChanged(CandidatePairChangeEvent event){}
    }
    private class Sdp extends SimpleSdp{
        final String remote;final PeerConnection pc;final boolean offer;
        Sdp(String remote,PeerConnection pc,boolean offer){this.remote=remote;this.pc=pc;this.offer=offer;}
        @Override public void onCreateSuccess(SessionDescription value){
            pc.setLocalDescription(new SimpleSdp(){@Override public void onSetSuccess(){JSONObject payload=new JSONObject();try{
                payload.put("type",value.type.canonicalForm());payload.put("sdp",value.description);
                client.sendSignal(remote,offer?"offer":"answer",payload);}catch(Exception ignored){}
            }},value);
        }
        @Override public void onCreateFailure(String error){main.post(()->markReconnecting());}
    }
    private static class SimpleSdp implements SdpObserver{
        public void onCreateSuccess(SessionDescription s){} public void onSetSuccess(){}
        public void onCreateFailure(String s){} public void onSetFailure(String s){}
    }
    private void restartPeer(String remote){
        PeerConnection pc=peers.get(remote);if(pc==null||leaving)return;
        if(shouldOffer(remote)){pc.restartIce();createOffer(remote,pc,true);}
    }
    private void restartAllPeers(){for(String id:new ArrayList<>(peers.keySet()))restartPeer(id);}
    private void removePeer(String id,boolean shouldAnnounce){
        PeerConnection pc=peers.remove(id);if(pc!=null){pc.close();pc.dispose();}
        pendingCandidates.remove(id);String name=names.remove(id);usernames.remove(id);micStates.remove(id);
        publish();if(shouldAnnounce&&name!=null)announce(getString(R.string.call_participant_left,name));
    }

    public void toggleMic(){if(canSpeak)setMic(!micEnabled,false);}
    private void setMic(boolean enabled,boolean forced){
        micEnabled=enabled;if(audioTrack!=null)audioTrack.setEnabled(enabled);
        if(client!=null&&(!forced||connected))client.sendMediaState(enabled);publish();
    }
    public void toggleSpeaker(){speakerEnabled=!speakerEnabled;applyAudioRoute();publish();}
    public void raiseHand(){if(client!=null)client.raiseHand();}
    public void moderate(String id,String action,CommunityClient.Callback callback){
        if(client!=null)client.moderateCall(code,usernames.getOrDefault(id,""),action,callback);
    }
    public void endCall(CommunityClient.Callback callback){
        if(client!=null)client.endCall(code,(data,error)->main.post(()->{
            if(error==null)finishCall(false);callback.complete(data,error);
        }));
    }
    public void leave(){finishCall(true);}
    private void finishCall(boolean notifyServer){
        if(leaving)return;leaving=true;if(notifyServer&&client!=null)client.leaveCall();
        cleanup();for(Listener listener:new ArrayList<>(listeners))listener.onCallFinished();stopSelf();
    }
    private void markReconnecting(){
        if(leaving)return;connected=false;statusText=getString(R.string.call_reconnecting);
        publishAndAnnounce(statusText);
    }

    private void requestAudioFocus(){
        audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
        AudioManager.OnAudioFocusChangeListener listener=change->main.post(()->{
            if(change==AudioManager.AUDIOFOCUS_LOSS||change==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT){
                if(audioTrack!=null)audioTrack.setEnabled(false);
            }else if(change==AudioManager.AUDIOFOCUS_GAIN&&audioTrack!=null)audioTrack.setEnabled(micEnabled&&canSpeak);
        });
        AudioAttributes attributes=new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
        focusRequest=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes).setOnAudioFocusChangeListener(listener).build();
        audioManager.requestAudioFocus(focusRequest);
    }
    private boolean hasPrivateAudioRoute(){
        for(AudioDeviceInfo device:audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)){
            int type=device.getType();
            if(type==AudioDeviceInfo.TYPE_WIRED_HEADPHONES||type==AudioDeviceInfo.TYPE_WIRED_HEADSET||
                    type==AudioDeviceInfo.TYPE_BLUETOOTH_A2DP||type==AudioDeviceInfo.TYPE_BLUETOOTH_SCO||
                    type==AudioDeviceInfo.TYPE_USB_HEADSET)return true;
        }return false;
    }
    @SuppressWarnings("deprecation") private void applyAudioRoute(){
        try {
            if(Build.VERSION.SDK_INT>=31){
                AudioDeviceInfo selected=null;
                for(AudioDeviceInfo device:audioManager.getAvailableCommunicationDevices()){
                    if(speakerEnabled&&device.getType()==AudioDeviceInfo.TYPE_BUILTIN_SPEAKER){selected=device;break;}
                    if(!speakerEnabled&&device.getType()==AudioDeviceInfo.TYPE_BUILTIN_EARPIECE){selected=device;break;}
                }
                if(selected!=null)audioManager.setCommunicationDevice(selected);
            }else audioManager.setSpeakerphoneOn(speakerEnabled);
        } catch(SecurityException ignored) {
            // Bluetooth routing may require a runtime permission on Android 12+; built-in routing
            // remains usable and the call itself must not be terminated because of it.
            if(Build.VERSION.SDK_INT<31)audioManager.setSpeakerphoneOn(speakerEnabled);
        }
    }
    private void registerNetworkCallback(){
        connectivityManager=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
        networkCallback=new ConnectivityManager.NetworkCallback(){
            @Override public void onAvailable(Network network){main.post(()->{if(connected)restartAllPeers();});}
        };
        connectivityManager.registerDefaultNetworkCallback(networkCallback);
    }
    private Snapshot snapshot(){
        List<Participant> values=new ArrayList<>();
        for(String id:names.keySet())values.add(new Participant(id,names.get(id),usernames.get(id),
                Boolean.TRUE.equals(micStates.get(id))));
        return new Snapshot(code,statusText==null?getString(R.string.call_connecting):statusText,
                connected,micEnabled,speakerEnabled,moderator,canSpeak,startedAt,values);
    }
    private void publish(){
        Snapshot snapshot=snapshot();showNotification(code,snapshot.status);
        for(Listener listener:new ArrayList<>(listeners))listener.onSnapshot(snapshot);
    }
    private void announce(String text){for(Listener listener:new ArrayList<>(listeners))listener.onAnnouncement(text);}
    private void publishAndAnnounce(String text){publish();announce(text);}
    private void showNotification(String room,String text){
        Intent open=new Intent(this,VoiceCallActivity.class).putExtra(EXTRA_ROOM_CODE,room);
        PendingIntent pending=PendingIntent.getActivity(this,5,open,
                PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Intent leave=new Intent(this,CallKeepAliveService.class).setAction(ACTION_LEAVE);
        PendingIntent leavePending=PendingIntent.getService(this,6,leave,
                PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification notification=new Notification.Builder(this,CHANNEL).setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(getString(R.string.voice_call_title)).setContentText(text)
                .setContentIntent(pending).addAction(new Notification.Action.Builder(
                        null,getString(R.string.leave_call),leavePending).build()).setOngoing(true).build();
        startForeground(NOTIFICATION_ID,notification);
    }
    private long parseTime(String value){
        try{return java.time.Instant.parse(value).toEpochMilli();}catch(Exception ignored){return System.currentTimeMillis();}
    }
    private void cleanup(){
        if(client!=null){client.disconnect();client=null;}
        if(networkCallback!=null&&connectivityManager!=null)try{connectivityManager.unregisterNetworkCallback(networkCallback);}catch(Exception ignored){}
        for(PeerConnection pc:peers.values()){pc.close();pc.dispose();}peers.clear();
        if(audioTrack!=null){audioTrack.dispose();audioTrack=null;}if(audioSource!=null){audioSource.dispose();audioSource=null;}
        if(factory!=null){factory.dispose();factory=null;}
        if(focusRequest!=null)audioManager.abandonAudioFocusRequest(focusRequest);
        if(Build.VERSION.SDK_INT>=31)try{audioManager.clearCommunicationDevice();}catch(SecurityException ignored){}
        audioManager.setMode(AudioManager.MODE_NORMAL);stopForeground(STOP_FOREGROUND_REMOVE);
    }
    @Override public void onDestroy(){if(!leaving){leaving=true;cleanup();}super.onDestroy();}
}
