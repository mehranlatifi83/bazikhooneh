package ir.codelighthouse.bazikhooneh;

import android.Manifest;
import android.app.AlertDialog;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import android.view.View;
import android.widget.*;
import ir.codelighthouse.bazikhooneh.account.SessionStore;
import java.util.Locale;

/** A lifecycle-safe view over {@link CallKeepAliveService}; it never owns call resources. */
public final class VoiceCallActivity extends NavigableActivity
    implements CallKeepAliveService.Listener {
  private static final int AUDIO_PERMISSION = 72;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private String code;
  private CallKeepAliveService service;
  private boolean bound;
  private TextView status, timer;
  private LinearLayout participants;
  private CallKeepAliveService.Snapshot snapshot;
  private final ServiceConnection connection =
      new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder value) {
          service = ((CallKeepAliveService.LocalBinder) value).service();
          if (service.roomCode() != null && !service.roomCode().equals(code)) {
            announce(getString(R.string.call_reconnecting));
            finish();
            return;
          }
          bound = true;
          service.addListener(VoiceCallActivity.this);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
          bound = false;
          service = null;
        }
      };

  @Override
  protected void onCreate(Bundle state) {
    super.onCreate(state);
    setContentView(R.layout.activity_voice_call);
    code = getIntent().getStringExtra(CallKeepAliveService.EXTRA_ROOM_CODE);
    SessionStore store = new SessionStore(this);
    if (code == null || !store.isSignedIn()) {
      finish();
      return;
    }
    status = findViewById(R.id.call_status);
    timer = findViewById(R.id.call_timer);
    participants = findViewById(R.id.call_participants);
    findViewById(R.id.call_toggle_mic)
        .setOnClickListener(
            v -> {
              if (service != null) service.toggleMic();
            });
    findViewById(R.id.call_toggle_speaker)
        .setOnClickListener(
            v -> {
              if (service != null) service.toggleSpeaker();
            });
    findViewById(R.id.call_raise_hand)
        .setOnClickListener(
            v -> {
              if (service != null) service.raiseHand();
            });
    findViewById(R.id.call_end).setOnClickListener(v -> confirmEndCall());
    findViewById(R.id.call_leave).setOnClickListener(v -> leave());
    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
      requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, AUDIO_PERMISSION);
    else startAndBind();
  }

  @Override
  public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
    super.onRequestPermissionsResult(requestCode, permissions, results);
    if (requestCode == AUDIO_PERMISSION
        && results.length > 0
        && results[0] == PackageManager.PERMISSION_GRANTED) startAndBind();
    else {
      announce(getString(R.string.call_permission_required));
      finish();
    }
  }

  private void startAndBind() {
    Intent intent =
        new Intent(this, CallKeepAliveService.class)
            .setAction(CallKeepAliveService.ACTION_START)
            .putExtra(CallKeepAliveService.EXTRA_ROOM_CODE, code);
    startForegroundService(intent);
    bindService(intent, connection, BIND_AUTO_CREATE);
    handler.post(timerTask);
  }

  @Override
  public void onSnapshot(CallKeepAliveService.Snapshot value) {
    runOnUiThread(
        () -> {
          snapshot = value;
          render();
        });
  }

  @Override
  public void onAnnouncement(String text) {
    runOnUiThread(() -> announce(text));
  }

  @Override
  public void onCallFinished() {
    runOnUiThread(this::finish);
  }

  private void render() {
    if (snapshot == null) return;
    status.setText(snapshot.status);
    ((Button) findViewById(R.id.call_toggle_mic))
        .setText(snapshot.micEnabled ? R.string.mute_microphone : R.string.unmute_microphone);
    ((Button) findViewById(R.id.call_toggle_speaker))
        .setText(snapshot.speakerEnabled ? R.string.use_earpiece : R.string.use_speaker);
    findViewById(R.id.call_raise_hand).setVisibility(snapshot.canSpeak ? View.GONE : View.VISIBLE);
    findViewById(R.id.call_end).setVisibility(snapshot.moderator ? View.VISIBLE : View.GONE);
    participants.removeAllViews();
    for (CallKeepAliveService.Participant participant : snapshot.participants) {
      TextView row = new TextView(this);
      row.setText(
          getString(
              R.string.call_participant_item,
              participant.name,
              getString(
                  participant.micEnabled ? R.string.microphone_on : R.string.microphone_off)));
      row.setTextSize(18);
      row.setPadding(12, 12, 12, 12);
      row.setFocusable(true);
      if (snapshot.moderator)
        row.setOnLongClickListener(
            v -> {
              showCallMenu(row, participant.id);
              return true;
            });
      participants.addView(row);
    }
  }

  private void showCallMenu(View anchor, String id) {
    PopupMenu menu = new PopupMenu(this, anchor);
    menu.getMenu().add(R.string.force_mute);
    menu.getMenu().add(R.string.allow_to_speak);
    menu.getMenu().add(R.string.remove_from_call);
    menu.setOnMenuItemClickListener(
        item -> {
          String action;
          if (item.getTitle().equals(getString(R.string.force_mute))) action = "force_mute";
          else if (item.getTitle().equals(getString(R.string.allow_to_speak)))
            action = "allow_speak";
          else action = "kick";
          if (service != null)
            service.moderate(
                id,
                action,
                (data, error) ->
                    runOnUiThread(
                        () -> {
                          if (error != null) announce(error);
                        }));
          return true;
        });
    menu.show();
  }

  private void confirmEndCall() {
    new AlertDialog.Builder(this)
        .setMessage(R.string.end_call_confirmation)
        .setNegativeButton(android.R.string.cancel, null)
        .setPositiveButton(
            R.string.end_call,
            (dialog, which) -> {
              if (service != null)
                service.endCall(
                    (data, error) ->
                        runOnUiThread(
                            () -> {
                              if (error != null) announce(error);
                            }));
            })
        .show();
  }

  private void announce(String text) {
    status.setText(text);
    status.announceForAccessibility(text);
  }

  private final Runnable timerTask =
      new Runnable() {
        @Override
        public void run() {
          if (snapshot != null && snapshot.startedAt > 0) {
            long seconds = (System.currentTimeMillis() - snapshot.startedAt) / 1000;
            timer.setText(
                String.format(Locale.getDefault(), "%02d:%02d", seconds / 60, seconds % 60));
          }
          handler.postDelayed(this, 1000);
        }
      };

  private void leave() {
    if (service != null) service.leave();
    else {
      startService(
          new Intent(this, CallKeepAliveService.class)
              .setAction(CallKeepAliveService.ACTION_LEAVE));
      finish();
    }
  }

  @Override
  public void onBackPressed() {
    leave();
  }

  @Override
  protected void onDestroy() {
    handler.removeCallbacksAndMessages(null);
    if (bound) {
      service.removeListener(this);
      unbindService(connection);
      bound = false;
    }
    // Activity destruction (including rotation) deliberately does not stop the call.
    super.onDestroy();
  }
}
