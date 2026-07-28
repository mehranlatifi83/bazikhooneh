package ir.codelighthouse.bazikhooneh.network;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * Single owner for a logical WebSocket connection.
 *
 * It rejects callbacks from superseded sockets, uses exponential backoff with
 * jitter, waits for a usable network, and never reconnects after an explicit
 * stop or an authentication failure.
 */
public final class ReliableWebSocket {
    public enum State { IDLE, CONNECTING, OPEN, WAITING, STOPPED }

    public interface Listener {
        void onOpen();
        void onMessage(JSONObject message);
        void onReconnecting(long delayMillis);
        void onClosed();
        void onError(String error);
    }

    private final Context context;
    private final OkHttpClient client;
    private final Listener listener;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "bazikhooneh-websocket");
                thread.setDaemon(true);
                return thread;
            });
    private final Object lock = new Object();

    private Request request;
    private WebSocket socket;
    private ScheduledFuture<?> reconnectFuture;
    private ScheduledFuture<?> heartbeatFuture;
    private State state = State.IDLE;
    private long generation;
    private int reconnectAttempt;
    private boolean stopped = true;
    private boolean disconnectNotified;

    public ReliableWebSocket(
            Context context, OkHttpClient client, Listener listener) {
        this.context = context.getApplicationContext();
        this.client = client;
        this.listener = listener;
    }

    public void start(Request request) {
        synchronized (lock) {
            stopLocked(false);
            this.request = request;
            stopped = false;
            reconnectAttempt = 0;
            disconnectNotified = false;
            generation++;
            openLocked(generation);
        }
    }

    public boolean send(JSONObject message) {
        synchronized (lock) {
            return state == State.OPEN && socket != null
                    && socket.send(message.toString());
        }
    }

    public State state() {
        synchronized (lock) {
            return state;
        }
    }

    public void stop() {
        synchronized (lock) {
            stopLocked(true);
        }
    }

    public void shutdown() {
        stop();
        scheduler.shutdownNow();
    }

    private void stopLocked(boolean notify) {
        stopped = true;
        generation++;
        cancelReconnectLocked();
        cancelHeartbeatLocked();
        WebSocket previous = socket;
        socket = null;
        State previousState = state;
        state = State.STOPPED;
        if (previous != null) previous.close(1000, "client_stopped");
        if (notify && previousState != State.STOPPED && previousState != State.IDLE) {
            listener.onClosed();
        }
    }

    private void openLocked(long expectedGeneration) {
        if (stopped || request == null || expectedGeneration != generation) return;
        if (!networkAvailable()) {
            scheduleReconnectLocked(expectedGeneration);
            return;
        }
        state = State.CONNECTING;
        final WebSocket[] created = new WebSocket[1];
        created[0] = client.newWebSocket(request, new WebSocketListener() {
            @Override public void onOpen(WebSocket webSocket, Response response) {
                synchronized (lock) {
                    if (!isCurrent(expectedGeneration, webSocket)) {
                        webSocket.close(1000, "superseded");
                        return;
                    }
                    state = State.OPEN;
                    reconnectAttempt = 0;
                    disconnectNotified = false;
                    scheduleHeartbeatLocked(expectedGeneration);
                }
                listener.onOpen();
            }

            @Override public void onMessage(WebSocket webSocket, String text) {
                synchronized (lock) {
                    if (!isCurrent(expectedGeneration, webSocket)) return;
                }
                try {
                    listener.onMessage(new JSONObject(text));
                } catch (JSONException ignored) {
                    listener.onError("invalid_server_message");
                }
            }

            @Override public void onClosed(
                    WebSocket webSocket, int code, String reason) {
                handleDisconnect(expectedGeneration, webSocket, code, null);
            }

            @Override public void onFailure(
                    WebSocket webSocket, Throwable error, Response response) {
                int code = response == null ? 0 : response.code();
                handleDisconnect(expectedGeneration, webSocket, code, error);
            }
        });
        socket = created[0];
    }

    private void handleDisconnect(
            long expectedGeneration, WebSocket disconnected, int code, Throwable error) {
        boolean terminalAuthentication = code == 401 || code == 403 || code == 4401;
        boolean notifyClosed = false;
        synchronized (lock) {
            if (!isCurrent(expectedGeneration, disconnected)) return;
            socket = null;
            cancelHeartbeatLocked();
            if (stopped) return;
            if (terminalAuthentication) {
                stopped = true;
                state = State.STOPPED;
            } else {
                scheduleReconnectLocked(expectedGeneration);
                if (!disconnectNotified) {
                    disconnectNotified = true;
                    notifyClosed = true;
                }
            }
        }
        if (terminalAuthentication) listener.onError("authentication_failed");
        else if (notifyClosed) listener.onClosed();
    }

    private void scheduleReconnectLocked(long expectedGeneration) {
        if (stopped || expectedGeneration != generation) return;
        cancelReconnectLocked();
        state = State.WAITING;
        long delay = reconnectDelayMillis(
                reconnectAttempt++, ThreadLocalRandom.current().nextDouble());
        listener.onReconnecting(delay);
        reconnectFuture = scheduler.schedule(() -> {
            synchronized (lock) {
                reconnectFuture = null;
                openLocked(expectedGeneration);
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    private boolean isCurrent(long expectedGeneration, WebSocket candidate) {
        return !stopped && expectedGeneration == generation && candidate == socket;
    }

    private void cancelReconnectLocked() {
        if (reconnectFuture != null) {
            reconnectFuture.cancel(false);
            reconnectFuture = null;
        }
    }

    private void scheduleHeartbeatLocked(long expectedGeneration) {
        cancelHeartbeatLocked();
        heartbeatFuture = scheduler.scheduleAtFixedRate(() -> {
            synchronized (lock) {
                if (stopped || expectedGeneration != generation
                        || state != State.OPEN || socket == null) return;
                socket.send("{\"type\":\"ping\"}");
            }
        }, 15, 15, TimeUnit.SECONDS);
    }

    private void cancelHeartbeatLocked() {
        if (heartbeatFuture != null) {
            heartbeatFuture.cancel(false);
            heartbeatFuture = null;
        }
    }

    private boolean networkAvailable() {
        ConnectivityManager manager =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) return true;
        Network active = manager.getActiveNetwork();
        if (active == null) return false;
        NetworkCapabilities capabilities = manager.getNetworkCapabilities(active);
        return capabilities != null
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    public static long reconnectDelayMillis(int attempt, double randomUnit) {
        int boundedAttempt = Math.max(0, Math.min(attempt, 5));
        long base = Math.min(30_000L, 1_000L << boundedAttempt);
        double boundedRandom = Math.max(0.0, Math.min(1.0, randomUnit));
        double jitter = 0.75 + boundedRandom * 0.5;
        return Math.min(30_000L, Math.max(500L, Math.round(base * jitter)));
    }
}
