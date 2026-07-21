package ir.codelighthouse.bazikhooneh.community;

import java.util.concurrent.TimeUnit;
import okhttp3.*;
import org.json.JSONObject;

public final class CommunityClient {
    public interface Callback { void complete(JSONObject data, String error); }
    public interface Events {
        void onOpen();
        void onEvent(JSONObject event);
        void onClosed();
        void onError(String error);
    }

    private final String baseUrl;
    private final String token;
    private final OkHttpClient http = new OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS).retryOnConnectionFailure(true).build();
    private WebSocket socket;

    public CommunityClient(String baseUrl, String token) {
        this.baseUrl = baseUrl.replaceAll("/$", "");
        this.token = token;
    }

    public void list(Callback callback) { request("GET", "/api/v1/community/rooms/", null, callback); }
    public void create(String title, Callback callback) {
        JSONObject body = new JSONObject();
        try { body.put("title", title); body.put("privacy", "private"); body.put("join_policy", "open"); }
        catch (Exception ignored) { }
        request("POST", "/api/v1/community/rooms/", body, callback);
    }
    public void join(String code, Callback callback) {
        JSONObject body = new JSONObject(); try { body.put("code", code); } catch (Exception ignored) { }
        request("POST", "/api/v1/community/rooms/join/", body, callback);
    }
    public void details(String code, Callback callback) {
        request("GET", "/api/v1/community/rooms/" + code + "/", null, callback);
    }
    public void leave(String code, Callback callback) {
        request("POST", "/api/v1/community/rooms/" + code + "/leave/", new JSONObject(), callback);
    }
    public void updateRoom(String code, String title, String privacy, String joinPolicy, Callback callback) {
        JSONObject body=new JSONObject();try{body.put("title",title);body.put("privacy",privacy);body.put("join_policy",joinPolicy);}catch(Exception ignored){}
        request("PATCH", "/api/v1/community/rooms/" + code + "/", body, callback);
    }
    public void resolveJoinRequest(String code,long requestId,boolean approve,Callback callback){
        JSONObject body=new JSONObject();try{body.put("action",approve?"approve":"reject");}catch(Exception ignored){}
        request("POST","/api/v1/community/rooms/"+code+"/join-requests/"+requestId+"/",body,callback);
    }
    public void messages(String code, Callback callback) {
        request("GET", "/api/v1/community/rooms/" + code + "/messages/", null, callback);
    }
    public void events(String code, Callback callback) {
        request("GET", "/api/v1/community/rooms/" + code + "/events/", null, callback);
    }
    public void editMessage(String code, long id, String text, Callback callback) {
        JSONObject body = new JSONObject(); try { body.put("text", text); } catch (Exception ignored) { }
        request("PATCH", "/api/v1/community/rooms/" + code + "/messages/" + id + "/", body, callback);
    }
    public void deleteMessage(String code, long id, Callback callback) {
        request("DELETE", "/api/v1/community/rooms/" + code + "/messages/" + id + "/", null, callback);
    }
    public void stats(Callback callback) { request("GET", "/api/v1/stats/me/", null, callback); }
    public void leaderboard(Callback callback) { request("GET", "/api/v1/leaderboard/", null, callback); }
    public void startCall(String code, boolean requestToSpeak, Callback callback) {
        JSONObject body = new JSONObject();
        try { body.put("mic_policy", requestToSpeak ? "request" : "open"); body.put("max_participants", 4); }
        catch (Exception ignored) { }
        request("POST", "/api/v1/community/rooms/" + code + "/call/", body, callback);
    }
    public void endCall(String code, Callback callback) {
        request("DELETE", "/api/v1/community/rooms/" + code + "/call/", null, callback);
    }
    public void startGame(String code, String gameKey, Callback callback) {
        JSONObject body = new JSONObject(); try { body.put("game_key", gameKey); } catch (Exception ignored) { }
        request("POST", "/api/v1/community/rooms/" + code + "/game/", body, callback);
    }
    public void moderateRoom(String code, String username, String action, Callback callback) {
        JSONObject body = new JSONObject(); try { body.put("username", username); body.put("action", action); }
        catch (Exception ignored) { }
        request("POST", "/api/v1/community/rooms/" + code + "/moderate/", body, callback);
    }
    public void moderateCall(String code, String username, String action, Callback callback) {
        JSONObject body = new JSONObject(); try { body.put("username", username); body.put("action", action); }
        catch (Exception ignored) { }
        request("POST", "/api/v1/community/rooms/" + code + "/call/moderate/", body, callback);
    }
    public void joinTicTacToe(String legacyCode, Callback callback) {
        JSONObject body = new JSONObject(); try { body.put("code", legacyCode); } catch (Exception ignored) { }
        request("POST", "/api/v1/rooms/join/", body, callback);
    }
    public void joinLudo(String legacyCode, Callback callback) {
        JSONObject body = new JSONObject(); try { body.put("code", legacyCode); } catch (Exception ignored) { }
        request("POST", "/api/v1/ludo/rooms/join/", body, callback);
    }
    public void quickMatch(String gameKey, Callback callback) {
        JSONObject body = new JSONObject(); try { body.put("game_key", gameKey); } catch (Exception ignored) { }
        request("POST", "/api/v1/matchmaking/", body, callback);
    }
    public void matchmakingStatus(Callback callback) { request("GET", "/api/v1/matchmaking/", null, callback); }
    public void cancelMatchmaking(Callback callback) { request("DELETE", "/api/v1/matchmaking/", null, callback); }

    public void connect(String code, Events events) {
        disconnect();
        String wsBase = baseUrl.replaceFirst("^https", "wss").replaceFirst("^http", "ws");
        Request request = new Request.Builder().url(wsBase + "/ws/v1/community/" + code
                + "/?token=" + token).build();
        socket = http.newWebSocket(request, new WebSocketListener() {
            @Override public void onOpen(WebSocket webSocket, Response response) { events.onOpen(); }
            @Override public void onMessage(WebSocket webSocket, String text) {
                try { events.onEvent(new JSONObject(text)); }
                catch (Exception error) { events.onError("invalid_server_message"); }
            }
            @Override public void onClosed(WebSocket webSocket, int code, String reason) { events.onClosed(); }
            @Override public void onFailure(WebSocket webSocket, Throwable error, Response response) {
                events.onError(error.getMessage() == null ? "connection_failed" : error.getMessage());
            }
        });
    }

    public boolean send(JSONObject message) { return socket != null && socket.send(message.toString()); }
    public void sendChat(String text) {
        sendChat(text, 0);
    }
    public void sendChat(String text, long replyTo) {
        JSONObject value = new JSONObject();
        try { value.put("type", "chat.send"); value.put("text", text); if(replyTo>0)value.put("reply_to",replyTo); send(value); }
        catch (Exception ignored) { }
    }
    public void joinCall() { sendType("call.join"); }
    public void leaveCall() { sendType("call.leave"); }
    public void raiseHand() { sendType("call.raise_hand"); }
    public void sendMediaState(boolean mic) {
        JSONObject value = new JSONObject();
        try { value.put("type", "call.media_state"); value.put("mic_enabled", mic); send(value); }
        catch (Exception ignored) { }
    }
    public void sendSignal(String to, String type, JSONObject payload) {
        JSONObject value = new JSONObject();
        try { value.put("type", "call.signal"); value.put("to", to);
            value.put("signal_type", type); value.put("payload", payload); send(value); }
        catch (Exception ignored) { }
    }
    private void sendType(String type) {
        JSONObject value = new JSONObject(); try { value.put("type", type); send(value); }
        catch (Exception ignored) { }
    }
    public void disconnect() { if (socket != null) { socket.close(1000, "leaving"); socket = null; } }

    private void request(String method, String path, JSONObject body, Callback callback) {
        RequestBody requestBody = body == null ? null : RequestBody.create(
                body.toString(), MediaType.get("application/json; charset=utf-8"));
        Request.Builder builder = new Request.Builder().url(baseUrl + path)
                .header("Authorization", "Bearer " + token);
        if ("POST".equals(method)) builder.post(requestBody == null ? RequestBody.create(new byte[0]) : requestBody);
        else if ("DELETE".equals(method)) builder.delete(requestBody);
        else if ("PATCH".equals(method)) builder.patch(requestBody);
        else builder.get();
        http.newCall(builder.build()).enqueue(new CallbackAdapter(callback));
    }

    private static final class CallbackAdapter implements okhttp3.Callback {
        private final Callback callback;
        CallbackAdapter(Callback callback) { this.callback = callback; }
        @Override public void onFailure(Call call, java.io.IOException error) {
            callback.complete(null, error.getMessage() == null ? "network_error" : error.getMessage());
        }
        @Override public void onResponse(Call call, Response response) {
            try (ResponseBody body = response.body()) {
                String text = body == null ? "{}" : body.string();
                JSONObject json = text.isEmpty() ? new JSONObject() : new JSONObject(text);
                callback.complete(response.isSuccessful() ? json : null,
                        response.isSuccessful() ? null : json.optString("error", "http_" + response.code()));
            } catch (Exception error) { callback.complete(null, "invalid_server_response"); }
        }
    }
}
