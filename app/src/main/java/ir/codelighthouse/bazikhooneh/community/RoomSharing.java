package ir.codelighthouse.bazikhooneh.community;

import android.app.Activity;
import android.content.Intent;
import ir.codelighthouse.bazikhooneh.R;
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
}
