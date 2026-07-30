package ir.codelighthouse.bazikhooneh.community;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import ir.codelighthouse.bazikhooneh.R;
import ir.codelighthouse.bazikhooneh.feature.room.RoomActivity;
import ir.codelighthouse.bazikhooneh.navigation.RoomLink;

public final class RoomSharing {
  private RoomSharing() {}

  public static void share(Activity activity, String title, String roomCode) {
    Intent share =
        new Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, title)
            .putExtra(
                Intent.EXTRA_TEXT,
                activity.getString(
                    R.string.share_room_text, title, roomCode, RoomLink.canonicalUrl(roomCode)));
    activity.startActivity(Intent.createChooser(share, activity.getString(R.string.share_room)));
  }

  public static void copyCode(Activity activity, String roomCode) {
    ClipboardManager clipboard =
        (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
    clipboard.setPrimaryClip(
        ClipData.newPlainText(activity.getString(R.string.room_code_hint), roomCode));
  }

  public static void openRoom(Activity activity, String roomCode) {
    activity.startActivity(
        new Intent(activity, RoomActivity.class)
            .putExtra(RoomActivity.EXTRA_ROOM_CODE, roomCode)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
    activity.finish();
  }
}
