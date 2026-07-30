package ir.codelighthouse.bazikhooneh.feature.social;

import android.os.Bundle;
import android.view.View;
import android.widget.*;
import ir.codelighthouse.bazikhooneh.BuildConfig;
import ir.codelighthouse.bazikhooneh.R;
import ir.codelighthouse.bazikhooneh.account.*;
import ir.codelighthouse.bazikhooneh.core.ui.NavigableActivity;
import ir.codelighthouse.bazikhooneh.feature.room.RoomActivity;
import ir.codelighthouse.bazikhooneh.feature.room.RoomListActivity;
import org.json.*;

public final class NotificationsActivity extends NavigableActivity {
  private AccountManagementClient client;
  private LinearLayout list;
  private TextView status;

  @Override
  protected void onCreate(Bundle state) {
    super.onCreate(state);
    setContentView(R.layout.activity_notifications);
    SessionStore store = new SessionStore(this);
    client = new AccountManagementClient(BuildConfig.API_BASE_URL, store.token(), listener);
    list = findViewById(R.id.notifications_list);
    status = findViewById(R.id.notifications_status);
    findViewById(R.id.mark_notifications_read)
        .setOnClickListener(
            v -> client.post("read", "/api/v1/accounts/notifications/", new JSONObject()));
    load();
  }

  private void load() {
    findViewById(R.id.notifications_loading).setVisibility(View.VISIBLE);
    client.get("list", "/api/v1/accounts/notifications/");
  }

  private final AccountManagementClient.Listener listener =
      new AccountManagementClient.Listener() {
        public void onSuccess(String op, JSONObject json) {
          runOnUiThread(
              () -> {
                if ("read".equals(op)) {
                  load();
                  return;
                }
                findViewById(R.id.notifications_loading).setVisibility(View.GONE);
                list.removeAllViews();
                JSONArray values = json.optJSONArray("results");
                if (values == null || values.length() == 0) {
                  status.setText(R.string.no_notifications);
                  return;
                }
                status.setText(getString(R.string.unread_notifications, json.optInt("unread")));
                for (int i = 0; i < values.length(); i++) {
                  JSONObject n = values.optJSONObject(i);
                  TextView item = new TextView(NotificationsActivity.this);
                  item.setText(
                      getString(
                          R.string.notification_item, n.optString("title"), n.optString("body")));
                  item.setTextSize(18);
                  item.setPadding(0, 20, 0, 20);
                  item.setFocusable(true);
                  JSONObject data = n.optJSONObject("data");
                  if (data != null
                      && data.has("room_code")
                      && n.optString("kind").startsWith("room_")) {
                    String code = data.optString("room_code");
                    item.setOnClickListener(
                        v -> {
                          Class<?> target =
                              "room_invite".equals(n.optString("kind"))
                                  ? RoomListActivity.class
                                  : RoomActivity.class;
                          android.content.Intent intent =
                              new android.content.Intent(NotificationsActivity.this, target);
                          intent.putExtra(
                              "room_invite".equals(n.optString("kind"))
                                  ? RoomListActivity.EXTRA_ROOM_CODE
                                  : "room_code",
                              code);
                          startActivity(intent);
                        });
                  }
                  list.addView(item);
                }
              });
        }

        public void onError(String error) {
          runOnUiThread(
              () -> {
                findViewById(R.id.notifications_loading).setVisibility(View.GONE);
                status.setText(AccountErrorMessages.get(NotificationsActivity.this, error));
              });
        }
      };
}
