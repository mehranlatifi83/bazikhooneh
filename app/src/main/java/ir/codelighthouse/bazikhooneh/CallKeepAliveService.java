package ir.codelighthouse.bazikhooneh;

import android.app.*;
import android.content.Intent;
import android.os.IBinder;

public final class CallKeepAliveService extends Service {
    private static final String CHANNEL = "active_calls";
    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(
                CHANNEL, getString(R.string.voice_call_title), NotificationManager.IMPORTANCE_LOW));
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        Intent open = new Intent(this, VoiceCallActivity.class)
                .putExtra("room_code", intent == null ? "" : intent.getStringExtra("room_code"));
        PendingIntent pending = PendingIntent.getActivity(this, 5, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.mipmap.ic_launcher).setContentTitle(getString(R.string.voice_call_title))
                .setContentText(getString(R.string.call_connected)).setContentIntent(pending)
                .setOngoing(true).build();
        startForeground(4301, notification);
        return START_NOT_STICKY;
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
