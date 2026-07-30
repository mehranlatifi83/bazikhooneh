package ir.codelighthouse.bazikhooneh.online;

import android.content.Context;
import ir.codelighthouse.bazikhooneh.BuildConfig;
import ir.codelighthouse.bazikhooneh.account.SessionAuthenticator;
import ir.codelighthouse.bazikhooneh.network.ReliableWebSocket;
import java.io.IOException;
import java.util.UUID;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
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
  private final OkHttpClient http =
      new OkHttpClient.Builder()
          .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
          .readTimeout(45, java.util.concurrent.TimeUnit.SECONDS)
          .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
          .retryOnConnectionFailure(true)
          .authenticator(new SessionAuthenticator(BuildConfig.API_BASE_URL))
          .build();
  private final String baseUrl;
  private final Listener listener;
  private final ReliableWebSocket socket;
  private String accountToken = "";
  private volatile JSONObject pendingAction;
  private volatile int pendingBaseVersion = -1;
  private volatile int latestVersion = -1;

  public OnlineGameClient(Context context, String baseUrl, Listener listener) {
    this.baseUrl = trimSlash(baseUrl);
    this.listener = listener;
    socket =
        new ReliableWebSocket(
            context,
            http,
            new ReliableWebSocket.Listener() {
              @Override
              public void onOpen() {
                listener.onConnected();
              }

              @Override
              public void onMessage(JSONObject message) {
                try {
                  if ("state".equals(message.optString("type"))) {
                    OnlineGameState state = OnlineGameState.from(message.getJSONObject("game"));
                    latestVersion = state.version;
                    if (pendingAction != null && state.version > pendingBaseVersion) {
                      pendingAction = null;
                      pendingBaseVersion = -1;
                    } else if (pendingAction != null) {
                      socket.send(pendingAction);
                      return;
                    }
                    listener.onState(state);
                  } else if ("error".equals(message.optString("type"))) {
                    pendingAction = null;
                    pendingBaseVersion = -1;
                    listener.onError(message.optString("error", "server_error"));
                  } else if ("presence".equals(message.optString("type"))) {
                    listener.onPresence(
                        message.optString("symbol"), message.optBoolean("connected"));
                  } else if (!"pong".equals(message.optString("type"))) {
                    listener.onError("invalid_server_message");
                  }
                } catch (JSONException error) {
                  listener.onError("invalid_server_response");
                }
              }

              @Override
              public void onReconnecting(long delayMillis) {}

              @Override
              public void onClosed() {
                listener.onDisconnected();
              }

              @Override
              public void onError(String error) {
                listener.onError(error);
              }
            });
  }

  public void createRoom() {
    post("/api/v1/rooms/", "{}");
  }

  public void setAccountToken(String token) {
    accountToken = token == null ? "" : token;
  }

  public void joinRoom(String code) {
    try {
      post("/api/v1/rooms/join/", new JSONObject().put("code", code).toString());
    } catch (JSONException error) {
      listener.onError("invalid_request");
    }
  }

  private void post(String path, String body) {
    Request.Builder builder =
        new Request.Builder().url(baseUrl + path).post(RequestBody.create(body, JSON));
    if (!accountToken.isEmpty()) builder.header("Authorization", "Bearer " + accountToken);
    Request request = builder.build();
    http.newCall(request)
        .enqueue(
            new Callback() {
              @Override
              public void onFailure(Call call, IOException error) {
                listener.onError("connection_failed");
              }

              @Override
              public void onResponse(Call call, Response response) {
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
    String wsBase = baseUrl.replaceFirst("^http", "ws");
    Request request =
        new Request.Builder()
            .url(wsBase + session.websocketPath)
            .header("Authorization", "Bearer " + session.token)
            .build();
    openSocket(request);
  }

  public void reconnect(String roomCode, String token) {
    String wsBase = baseUrl.replaceFirst("^http", "ws");
    Request request =
        new Request.Builder()
            .url(wsBase + "/ws/v1/rooms/" + roomCode + "/")
            .header("Authorization", "Bearer " + token)
            .build();
    openSocket(request);
  }

  private void openSocket(Request request) {
    socket.start(request);
  }

  public void place(int destination) {
    sendAction("place", -1, destination);
  }

  public void move(int source, int destination) {
    sendAction("move", source, destination);
  }

  public void requestRematch() {
    send(new JSONObject(), "rematch");
  }

  public void leaveRoom() {
    send(new JSONObject(), "leave");
  }

  private void sendAction(String kind, int source, int destination) {
    try {
      JSONObject action = new JSONObject().put("kind", kind).put("destination", destination);
      if (source >= 0) action.put("source", source);
      JSONObject message =
          new JSONObject()
              .put("type", "action")
              .put("action_id", UUID.randomUUID().toString())
              .put("action", action);
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

  public boolean hasPendingAction() {
    return pendingAction != null;
  }

  private void send(JSONObject message, String type) {
    try {
      message.put("type", type);
      if (!socket.send(message)) listener.onError("not_connected");
    } catch (JSONException error) {
      listener.onError("invalid_request");
    }
  }

  private static String parseError(String text) {
    try {
      return new JSONObject(text).optString("error", "server_error");
    } catch (JSONException ignored) {
      return "server_error";
    }
  }

  private static String trimSlash(String value) {
    return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
  }
}
