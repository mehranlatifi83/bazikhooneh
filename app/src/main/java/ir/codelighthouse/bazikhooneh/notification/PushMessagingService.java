package ir.codelighthouse.bazikhooneh.notification;

import android.app.*;
import android.content.Intent;
import androidx.annotation.NonNull;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import ir.codelighthouse.bazikhooneh.R;
import ir.codelighthouse.bazikhooneh.account.PushDeviceRegistrar;
import ir.codelighthouse.bazikhooneh.feature.room.RoomActivity;
import ir.codelighthouse.bazikhooneh.feature.room.RoomListActivity;
import ir.codelighthouse.bazikhooneh.feature.social.FriendsActivity;
import ir.codelighthouse.bazikhooneh.feature.social.NotificationsActivity;
import java.util.Map;

public final class PushMessagingService extends FirebaseMessagingService {
  private static final String CHANNEL = "social";

  @Override
  public void onNewToken(@NonNull String token) {
    PushDeviceRegistrar.registerToken(this, token);
  }

  @Override
  public void onMessageReceived(@NonNull RemoteMessage message) {
    Map<String, String> data = message.getData();
    String kind = value(data, "kind");
    long id = parseLong(value(data, "notification_id"), System.currentTimeMillis());
    Intent target =
        target(kind, data)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    PendingIntent pending =
        PendingIntent.getActivity(
            this,
            (int) (id % Integer.MAX_VALUE),
            target,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    NotificationManager manager = getSystemService(NotificationManager.class);
    manager.createNotificationChannel(
        new NotificationChannel(
            CHANNEL,
            getString(R.string.notifications_title),
            NotificationManager.IMPORTANCE_DEFAULT));
    Notification notification =
        new Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(value(data, "title"))
            .setContentText(value(data, "body"))
            .setStyle(new Notification.BigTextStyle().bigText(value(data, "body")))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setCategory(
                "room_call".equals(kind)
                    ? Notification.CATEGORY_CALL
                    : Notification.CATEGORY_MESSAGE)
            .build();
    manager.notify((int) (id % Integer.MAX_VALUE), notification);
  }

  private Intent target(String kind, Map<String, String> data) {
    String room = value(data, "room_code");
    if ("friend_request".equals(kind)) return new Intent(this, FriendsActivity.class);
    if (!room.isEmpty() && ("room_message".equals(kind) || "room_call".equals(kind)))
      return new Intent(this, RoomActivity.class).putExtra("room_code", room);
    if (!room.isEmpty())
      return new Intent(this, RoomListActivity.class)
          .putExtra(RoomListActivity.EXTRA_ROOM_CODE, room);
    return new Intent(this, NotificationsActivity.class);
  }

  private static String value(Map<String, String> data, String key) {
    String value = data.get(key);
    return value == null ? "" : value;
  }

  private static long parseLong(String value, long fallback) {
    try {
      return Long.parseLong(value);
    } catch (Exception ignored) {
      return fallback;
    }
  }
}
