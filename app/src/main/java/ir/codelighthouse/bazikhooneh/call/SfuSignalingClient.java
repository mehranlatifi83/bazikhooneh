package ir.codelighthouse.bazikhooneh.call;

import android.content.Context;
import ir.codelighthouse.bazikhooneh.network.ReliableWebSocket;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import okhttp3.*;
import org.json.JSONObject;

final class SfuSignalingClient {
    interface Listener { void onOpen(); void onEvent(String event,JSONObject data); void onDisconnected(); }
    private final ReliableWebSocket socket;
    private final Map<String,CompletableFuture<JSONObject>> pending=new ConcurrentHashMap<>();
    private final Listener listener;
    SfuSignalingClient(Context context,String baseUrl,String token,String room,Listener listener){
        this.listener=listener;
        OkHttpClient http=new OkHttpClient.Builder().pingInterval(15,TimeUnit.SECONDS).build();
        socket=new ReliableWebSocket(context,http,new ReliableWebSocket.Listener(){
            @Override public void onOpen(){listener.onOpen();}
            @Override public void onMessage(JSONObject message){
                String id=message.optString("id");
                if(!id.isEmpty()){CompletableFuture<JSONObject> future=pending.remove(id);
                    if(future!=null){String error=message.optString("error");
                        if(error.isEmpty()){Object data=message.opt("data");
                            if(data instanceof JSONObject)future.complete((JSONObject)data);
                            else if(data instanceof org.json.JSONArray){JSONObject wrapper=new JSONObject();
                                try{wrapper.put("values",data);}catch(Exception ignored){}
                                future.complete(wrapper);}
                            else future.complete(new JSONObject());}
                        else future.completeExceptionally(new IllegalStateException(error));}return;}
                listener.onEvent(message.optString("event"),message.optJSONObject("data"));
            }
            @Override public void onReconnecting(long delayMillis){}
            @Override public void onClosed(){failPending();listener.onDisconnected();}
            @Override public void onError(String error){failPending();listener.onDisconnected();}
        });
        String ws=baseUrl.replaceFirst("^https","wss").replaceFirst("^http","ws").replaceAll("/$","");
        Request request=new Request.Builder().url(ws+"/sfu/?room="+room)
                .header("Authorization","Bearer "+token).build();
        socket.start(request);
    }
    JSONObject request(String action,JSONObject data)throws Exception{
        String id=UUID.randomUUID().toString();CompletableFuture<JSONObject> future=new CompletableFuture<>();
        pending.put(id,future);JSONObject message=new JSONObject();
        message.put("id",id);message.put("action",action);message.put("data",data==null?new JSONObject():data);
        if(!socket.send(message)){pending.remove(id);throw new IllegalStateException("sfu_disconnected");}
        JSONObject result=future.get(15,TimeUnit.SECONDS);return result==null?new JSONObject():result;
    }
    void close(){socket.shutdown();failPending();}
    private void failPending(){for(CompletableFuture<JSONObject> value:pending.values())
        value.completeExceptionally(new IllegalStateException("sfu_disconnected"));pending.clear();}
}
