package ir.codelighthouse.bazikhooneh.navigation;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

import ir.codelighthouse.bazikhooneh.feature.room.RoomListActivity;
import ir.codelighthouse.bazikhooneh.LoginActivity;
import ir.codelighthouse.bazikhooneh.account.SessionStore;

public final class AppNavigator {
    private static final String PREFERENCES = "pending_navigation";
    private static final String PENDING_ROOM = "room_code";

    private AppNavigator() {
    }

    public static void openRooms(Context context, String gameKey) {
        Intent intent = authenticatedDestination(context);
        if (gameKey != null && !gameKey.isEmpty()) {
            intent.putExtra(RoomListActivity.EXTRA_GAME_KEY, gameKey);
        }
        context.startActivity(intent);
    }

    public static void openRoom(Context context, String roomCode) {
        if (roomCode == null || roomCode.isEmpty()) return;
        if (!new SessionStore(context).isSignedIn()) {
            rememberRoom(context, roomCode);
        }
        Intent intent = authenticatedDestination(context)
                .putExtra(RoomListActivity.EXTRA_ROOM_CODE, roomCode);
        context.startActivity(intent);
    }

    public static boolean consumePendingRoom(Activity activity) {
        String code = activity.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getString(PENDING_ROOM, "");
        if (code == null || code.isEmpty()) return false;
        activity.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit().remove(PENDING_ROOM).apply();
        activity.startActivity(new Intent(activity, RoomListActivity.class)
                .putExtra(RoomListActivity.EXTRA_ROOM_CODE, code));
        return true;
    }

    private static Intent authenticatedDestination(Context context) {
        Class<?> destination = new SessionStore(context).isSignedIn()
                ? RoomListActivity.class : LoginActivity.class;
        return new Intent(context, destination);
    }

    private static void rememberRoom(Context context, String roomCode) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit().putString(PENDING_ROOM, roomCode).apply();
    }
}
