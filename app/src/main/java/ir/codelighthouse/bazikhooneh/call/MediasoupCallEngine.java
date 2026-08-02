package ir.codelighthouse.bazikhooneh.call;

import android.content.Context;
import android.util.Log;
import io.github.crow_misia.mediasoup.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.*;
import org.webrtc.*;

public final class MediasoupCallEngine {
  private static final String TAG = "BaziKhoonehCall";
  public interface Listener {
    void onConnected();

    void onDisconnected();

    void onRemoteAudio(String accountId);
  }

  private final Context context;
  private final String baseUrl, token, room;
  private final PeerConnectionFactory factory;
  private final org.webrtc.AudioTrack audioTrack;
  private final Listener listener;
  private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
  private final AtomicBoolean reconnectPending = new AtomicBoolean();
  private final Map<String, Consumer> consumers = new ConcurrentHashMap<>();
  private SfuSignalingClient signaling;
  private Device device;
  private SendTransport send;
  private RecvTransport receive;
  private Producer producer;
  private volatile boolean closed;

  public MediasoupCallEngine(
      Context context,
      String baseUrl,
      String token,
      String room,
      PeerConnectionFactory factory,
      org.webrtc.AudioTrack audioTrack,
      Listener listener) {
    this.context = context.getApplicationContext();
    this.baseUrl = baseUrl;
    this.token = token;
    this.room = room;
    this.factory = factory;
    this.audioTrack = audioTrack;
    this.listener = listener;
  }

  public void connect() {
    if (closed) return;
    reconnectPending.set(false);
    signaling =
        new SfuSignalingClient(
            context,
            baseUrl,
            token,
            room,
            new SfuSignalingClient.Listener() {
              @Override
              public void onOpen() {
                executor.execute(
                    () -> {
                      try {
                        setup();
                        listener.onConnected();
                      } catch (Exception e) {
                        Log.e(TAG, "SFU media setup failed", e);
                        listener.onDisconnected();
                        scheduleReconnect();
                      }
                    });
              }

              @Override
              public void onEvent(String event, JSONObject data) {
                if ("newProducer".equals(event) && data != null)
                  executeIfOpen(() -> consumeSafe(data));
                else if ("producerClosed".equals(event) && data != null) {
                  executeIfOpen(() -> closeConsumer(data.optString("consumerId")));
                }
              }

              @Override
              public void onDisconnected() {
                if (!closed) {
                  listener.onDisconnected();
                  scheduleReconnect();
                }
              }
            });
  }

  private void setup() throws Exception {
    closeMedia();
    device = new Device(factory);
    JSONObject caps = signaling.request("routerCapabilities", null);
    device.load(caps.toString());
    JSONObject sendInfo =
        signaling.request("createTransport", new JSONObject().put("direction", "send"));
    send =
        device.createSendTransport(
            new SendListener(),
            sendInfo.getString("id"),
            sendInfo.getJSONObject("iceParameters").toString(),
            sendInfo.getJSONArray("iceCandidates").toString(),
            sendInfo.getJSONObject("dtlsParameters").toString());
    JSONObject recvInfo =
        signaling.request("createTransport", new JSONObject().put("direction", "recv"));
    receive =
        device.createRecvTransport(
            new ReceiveListener(),
            recvInfo.getString("id"),
            recvInfo.getJSONObject("iceParameters").toString(),
            recvInfo.getJSONArray("iceCandidates").toString(),
            recvInfo.getJSONObject("dtlsParameters").toString());
    producer = send.produce(value -> {}, audioTrack);
    JSONArray existing = signaling.request("listProducers", null).optJSONArray("values");
    if (existing != null)
      for (int i = 0; i < existing.length(); i++) consumeSafe(existing.optJSONObject(i));
  }

  private void consumeSafe(JSONObject info) {
    try {
      consume(info);
    } catch (Exception ignored) {
    }
  }

  private void consume(JSONObject info) throws Exception {
    if (receive == null || info == null) return;
    String producerId = info.optString("producerId");
    for (Consumer value : consumers.values()) if (value.getProducerId().equals(producerId)) return;
    JSONObject request =
        new JSONObject()
            .put("transportId", receive.getId())
            .put("producerId", producerId)
            .put("rtpCapabilities", new JSONObject(device.getRtpCapabilities()));
    JSONObject data = signaling.request("consume", request);
    Consumer consumer =
        receive.consume(
            value -> {},
            data.getString("id"),
            data.getString("producerId"),
            data.getString("kind"),
            data.getJSONObject("rtpParameters").toString());
    consumer.getTrack().setEnabled(true);
    consumers.put(consumer.getId(), consumer);
    signaling.request("resumeConsumer", new JSONObject().put("consumerId", consumer.getId()));
    listener.onRemoteAudio(info.optString("accountId", data.optString("accountId")));
  }

  public void setMicEnabled(boolean enabled) {
    if (closed) return;
    audioTrack.setEnabled(enabled);
    if (producer != null) {
      if (enabled) producer.resume();
      else producer.pause();
    }
  }

  public void reconnect() {
    scheduleReconnect(0);
  }

  private void scheduleReconnect() {
    scheduleReconnect(2);
  }

  private void scheduleReconnect(long delaySeconds) {
    if (closed || !reconnectPending.compareAndSet(false, true)) return;
    SfuSignalingClient previous = signaling;
    signaling = null;
    if (previous != null) previous.close();
    executor.schedule(
        () -> {
          if (!closed) connect();
        },
        delaySeconds,
        TimeUnit.SECONDS);
  }

  /**
   * Stops the native media graph before its Java-owned track and factory are disposed.
   *
   * <p>libmediasoupclient keeps encoder threads alive until the producer/transports are closed. The
   * caller must therefore wait for this method before disposing AudioTrack, AudioSource or
   * PeerConnectionFactory.
   */
  public boolean closeAndAwait(long timeout, TimeUnit unit) {
    if (closed) return executor.isTerminated();
    closed = true;
    reconnectPending.set(false);
    SfuSignalingClient activeSignaling = signaling;
    signaling = null;
    if (activeSignaling != null) activeSignaling.close();
    Future<?> release;
    try {
      release = executor.submit(this::closeMedia);
    } catch (RejectedExecutionException ignored) {
      return executor.isTerminated();
    }
    executor.shutdown();
    try {
      release.get(timeout, unit);
      return executor.awaitTermination(timeout, unit);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    } catch (ExecutionException | TimeoutException e) {
      Log.e(TAG, "Timed out while closing native call media", e);
      return false;
    }
  }

  private void closeMedia() {
    for (Consumer value : consumers.values()) {
      closeSafely(value);
    }
    consumers.clear();
    if (producer != null) {
      try {
        producer.pause();
      } catch (RuntimeException ignored) {
      }
      try {
        producer.close();
      } finally {
        producer.dispose();
      }
      producer = null;
    }
    if (send != null) {
      send.close();
      send.dispose();
      send = null;
    }
    if (receive != null) {
      receive.close();
      receive.dispose();
      receive = null;
    }
    if (device != null) {
      device.dispose();
      device = null;
    }
  }

  private void executeIfOpen(Runnable action) {
    if (closed || executor.isShutdown()) return;
    try {
      executor.execute(action);
    } catch (RejectedExecutionException ignored) {
      // A socket callback raced with orderly call shutdown.
    }
  }

  private void closeConsumer(String id) {
    Consumer value = consumers.remove(id);
    if (value != null) closeSafely(value);
  }

  private void closeSafely(Consumer value) {
    try {
      value.close();
    } finally {
      value.dispose();
    }
  }

  private final class SendListener implements SendTransport.Listener {
    @Override
    public void onConnect(Transport transport, String dtls) {
      try {
        signaling.request(
            "connectTransport",
            new JSONObject()
                .put("transportId", transport.getId())
                .put("dtlsParameters", new JSONObject(dtls)));
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    }

    @Override
    public String onProduce(Transport transport, String kind, String rtp, String appData) {
      try {
        return signaling
            .request(
                "produce",
                new JSONObject()
                    .put("transportId", transport.getId())
                    .put("kind", kind)
                    .put("rtpParameters", new JSONObject(rtp)))
            .getString("id");
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    }

    @Override
    public String onProduceData(Transport t, String a, String b, String c, String d) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void onConnectionStateChange(Transport t, String state) {
      if ("failed".equals(state) || "disconnected".equals(state)) {
        listener.onDisconnected();
        scheduleReconnect();
      }
    }
  }

  private final class ReceiveListener implements RecvTransport.Listener {
    @Override
    public void onConnect(Transport transport, String dtls) {
      try {
        signaling.request(
            "connectTransport",
            new JSONObject()
                .put("transportId", transport.getId())
                .put("dtlsParameters", new JSONObject(dtls)));
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    }

    @Override
    public void onConnectionStateChange(Transport t, String state) {
      if ("failed".equals(state) || "disconnected".equals(state)) {
        listener.onDisconnected();
        scheduleReconnect();
      }
    }
  }
}
