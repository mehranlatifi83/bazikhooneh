package ir.codelighthouse.bazikhooneh.online;

import android.content.Context;

import ir.codelighthouse.bazikhooneh.BuildConfig;
import ir.codelighthouse.bazikhooneh.account.SessionAuthenticator;
import ir.codelighthouse.bazikhooneh.network.ReliableWebSocket;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import org.json.JSONException;
import org.json.JSONObject;

public final class LudoOnlineClient {
    public interface Listener {
        void onSession(JSONObject session);
        void onState(JSONObject state);
        void onConnected();
        void onError(String error);
        void onDisconnected();
    }

    private static final MediaType JSON =
            MediaType.get("application/json; charset=utf-8");
    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .authenticator(new SessionAuthenticator(BuildConfig.API_BASE_URL))
            .build();
    private final String base;
    private final String accountToken;
    private final Listener listener;
    private final ReliableWebSocket socket;
    private volatile JSONObject pendingAction;
    private volatile int pendingBaseVersion = -1;
    private volatile int latestVersion = -1;

    public LudoOnlineClient(
            Context context, String base, String accountToken, Listener listener) {
        this.base = base.endsWith("/")
                ? base.substring(0, base.length() - 1) : base;
        this.accountToken = accountToken;
        this.listener = listener;
        socket = new ReliableWebSocket(context, http, new ReliableWebSocket.Listener() {
            @Override public void onOpen() {
                listener.onConnected();
            }
            @Override public void onMessage(JSONObject message) {
                if ("state".equals(message.optString("type"))) {
                    JSONObject payload = message.optJSONObject("payload");
                    if (payload == null) listener.onError("invalid_server_response");
                    else {
                        latestVersion = payload.optInt("version", latestVersion);
                        if (pendingAction != null && latestVersion > pendingBaseVersion) {
                            pendingAction = null;
                            pendingBaseVersion = -1;
                        } else if (pendingAction != null) {
                            socket.send(pendingAction);
                            return;
                        }
                        listener.onState(payload);
                    }
                } else if ("error".equals(message.optString("type"))) {
                    pendingAction = null;
                    pendingBaseVersion = -1;
                    listener.onError(message.optString("error", "server_error"));
                } else if (!"pong".equals(message.optString("type"))) {
                    listener.onError("invalid_server_message");
                }
            }
            @Override public void onReconnecting(long delayMillis) { }
            @Override public void onClosed() { listener.onDisconnected(); }
            @Override public void onError(String error) { listener.onError(error); }
        });
    }

    public void create(boolean thirdSixPenalty) {
        try {
            post("/api/v1/ludo/rooms/",
                    new JSONObject().put("third_six_penalty", thirdSixPenalty));
        } catch (JSONException error) {
            listener.onError("invalid_request");
        }
    }

    public void join(String code) {
        try {
            post("/api/v1/ludo/rooms/join/", new JSONObject().put("code", code));
        } catch (JSONException error) {
            listener.onError("invalid_request");
        }
    }

    public void start(String code) {
        post("/api/v1/ludo/rooms/" + code + "/start/", new JSONObject());
    }

    private void post(String path, JSONObject body) {
        Request request = new Request.Builder().url(base + path)
                .header("Authorization", "Bearer " + accountToken)
                .post(RequestBody.create(body.toString(), JSON)).build();
        http.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException error) {
                listener.onError("connection_failed");
            }

            @Override public void onResponse(Call call, Response response) {
                try (Response ignored = response) {
                    String text = response.body() == null
                            ? "" : response.body().string();
                    JSONObject json = text.isEmpty()
                            ? new JSONObject() : new JSONObject(text);
                    if (!response.isSuccessful()) {
                        listener.onError(json.optString("error", "request_failed"));
                        return;
                    }
                    if (json.has("reconnect_token")) {
                        listener.onSession(json);
                        connect(json.optString("room_code"),
                                json.optString("reconnect_token"));
                    } else {
                        listener.onState(json);
                    }
                } catch (Exception error) {
                    listener.onError("invalid_server_response");
                }
            }
        });
    }

    public void reconnect(String code, String token) {
        connect(code, token);
    }

    private void connect(String code, String token) {
        Request request = new Request.Builder()
                .url(base.replaceFirst("^http", "ws")
                        + "/ws/v1/ludo/" + code + "/")
                .header("Authorization", "Bearer " + token)
                .build();
        socket.start(request);
    }

    public void roll() {
        send("roll", -1);
    }

    public void move(int piece) {
        send("move", piece);
    }

    private void send(String type, int piece) {
        try {
            JSONObject message = new JSONObject()
                    .put("type", type)
                    .put("action_id", UUID.randomUUID().toString());
            if (piece >= 0) message.put("piece", piece);
            pendingBaseVersion = latestVersion;
            pendingAction = message;
            if (!socket.send(message)) listener.onDisconnected();
        } catch (JSONException error) {
            listener.onError("invalid_request");
        }
    }

    public void disconnect() {
        pendingAction = null;
        socket.shutdown();
    }
}
