package ir.codelighthouse.bazikhooneh.online;

import java.io.IOException;
import java.util.UUID;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

import org.json.JSONException;
import org.json.JSONObject;

public final class OnlineGameClient {
    public interface Listener {
        void onSession(OnlineSession session);
        void onState(OnlineGameState state);
        void onConnected();
        void onDisconnected();
        void onPresence(String symbol, boolean connected);
        void onError(String error);
    }

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private final OkHttpClient http = new OkHttpClient();
    private final String baseUrl;
    private final Listener listener;
    private WebSocket socket;

    public OnlineGameClient(String baseUrl, Listener listener) {
        this.baseUrl = trimSlash(baseUrl);
        this.listener = listener;
    }

    public void createRoom() {
        post("/api/v1/rooms/", "{}");
    }

    public void joinRoom(String code) {
        try {
            post("/api/v1/rooms/join/", new JSONObject().put("code", code).toString());
        } catch (JSONException error) {
            listener.onError("invalid_request");
        }
    }

    private void post(String path, String body) {
        Request request = new Request.Builder().url(baseUrl + path)
                .post(RequestBody.create(body, JSON)).build();
        http.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException error) {
                listener.onError("connection_failed");
            }

            @Override public void onResponse(Call call, Response response) {
                try (Response closeable = response) {
                    String text = response.body() == null ? "" : response.body().string();
                    if (!response.isSuccessful()) {
                        listener.onError(parseError(text));
                        return;
                    }
                    OnlineSession session = OnlineSession.from(new JSONObject(text));
                    listener.onSession(session);
                    connect(session);
                } catch (IOException | JSONException error) {
                    listener.onError("invalid_server_response");
                }
            }
        });
    }

    private void connect(OnlineSession session) {
        disconnect();
        String wsBase = baseUrl.replaceFirst("^http", "ws");
        Request request = new Request.Builder().url(wsBase + session.websocketPath)
                .header("Authorization", "Bearer " + session.token).build();
        openSocket(request);
    }

    public void reconnect(String roomCode, String token) {
        disconnect();
        String wsBase = baseUrl.replaceFirst("^http", "ws");
        Request request = new Request.Builder().url(wsBase + "/ws/v1/rooms/" + roomCode + "/")
                .header("Authorization", "Bearer " + token).build();
        openSocket(request);
    }

    private void openSocket(Request request) {
        socket = http.newWebSocket(request, new WebSocketListener() {
            @Override public void onOpen(WebSocket webSocket, Response response) {
                listener.onConnected();
            }

            @Override public void onMessage(WebSocket webSocket, String text) {
                try {
                    JSONObject message = new JSONObject(text);
                    if ("state".equals(message.optString("type"))) {
                        listener.onState(OnlineGameState.from(message.getJSONObject("game")));
                    } else if ("error".equals(message.optString("type"))) {
                        listener.onError(message.optString("error", "server_error"));
                    } else if ("presence".equals(message.optString("type"))) {
                        listener.onPresence(message.optString("symbol"),
                                message.optBoolean("connected"));
                    }
                } catch (JSONException error) {
                    listener.onError("invalid_server_response");
                }
            }

            @Override public void onFailure(WebSocket webSocket, Throwable error, Response response) {
                listener.onDisconnected();
            }

            @Override public void onClosed(WebSocket webSocket, int code, String reason) {
                listener.onDisconnected();
            }
        });
    }

    public void place(int destination) {
        sendAction("place", -1, destination);
    }

    public void move(int source, int destination) {
        sendAction("move", source, destination);
    }

    public void requestRematch() {
        if (socket != null) socket.send("{\"type\":\"rematch\"}");
    }

    private void sendAction(String kind, int source, int destination) {
        if (socket == null) {
            listener.onError("not_connected");
            return;
        }
        try {
            JSONObject action = new JSONObject().put("kind", kind).put("destination", destination);
            if (source >= 0) action.put("source", source);
            JSONObject message = new JSONObject().put("type", "action")
                    .put("action_id", UUID.randomUUID().toString()).put("action", action);
            socket.send(message.toString());
        } catch (JSONException error) {
            listener.onError("invalid_request");
        }
    }

    public void disconnect() {
        if (socket != null) {
            socket.close(1000, "leaving");
            socket = null;
        }
    }

    private static String parseError(String text) {
        try { return new JSONObject(text).optString("error", "server_error"); }
        catch (JSONException ignored) { return "server_error"; }
    }

    private static String trimSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
