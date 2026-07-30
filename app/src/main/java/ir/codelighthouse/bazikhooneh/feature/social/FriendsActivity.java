package ir.codelighthouse.bazikhooneh.feature.social;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import ir.codelighthouse.bazikhooneh.BuildConfig;
import ir.codelighthouse.bazikhooneh.R;
import ir.codelighthouse.bazikhooneh.account.AccountErrorMessages;
import ir.codelighthouse.bazikhooneh.account.AccountManagementClient;
import ir.codelighthouse.bazikhooneh.account.SessionStore;
import ir.codelighthouse.bazikhooneh.core.ui.NavigableActivity;
import ir.codelighthouse.bazikhooneh.feature.account.LoginActivity;
import ir.codelighthouse.bazikhooneh.feature.room.RoomActivity;
import ir.codelighthouse.bazikhooneh.feature.room.RoomListActivity;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public final class FriendsActivity extends NavigableActivity {
  private AccountManagementClient client;
  private LinearLayout list, requestsList, invitesList;
  private TextView status;
  private String action;
  private JSONArray latestFriends;

  @Override
  protected void onCreate(Bundle state) {
    super.onCreate(state);
    setContentView(R.layout.activity_friends);
    SessionStore store = new SessionStore(this);
    if (!store.isSignedIn()) {
      startActivity(new Intent(this, LoginActivity.class));
      finish();
      return;
    }
    client = new AccountManagementClient(BuildConfig.API_BASE_URL, store.token(), listener);
    list = findViewById(R.id.friends_list);
    requestsList = findViewById(R.id.requests_list);
    invitesList = findViewById(R.id.invites_list);
    status = findViewById(R.id.friends_status);
    findViewById(R.id.add_friend)
        .setOnClickListener(v -> showAction("friend", R.string.send_friend_request));
    findViewById(R.id.invite_friend)
        .setOnClickListener(v -> showAction("invite", R.string.invite_to_tic_tac_toe));
    findViewById(R.id.friend_action_submit)
        .setOnClickListener(
            v -> {
              if ("friend".equals(action)) search();
              else send(action, "/api/v1/accounts/invites/");
            });
    findViewById(R.id.cancel_friend_action).setOnClickListener(v -> hideAction());
    findViewById(R.id.tab_friends).setOnClickListener(v -> showTab(0));
    findViewById(R.id.tab_requests).setOnClickListener(v -> showTab(1));
    findViewById(R.id.tab_invites).setOnClickListener(v -> showTab(2));
    findViewById(R.id.friends_retry).setOnClickListener(v -> load());
    showTab(0);
    load();
  }

  private void showTab(int tab) {
    int friends = tab == 0 ? View.VISIBLE : View.GONE,
        requests = tab == 1 ? View.VISIBLE : View.GONE,
        invites = tab == 2 ? View.VISIBLE : View.GONE;
    findViewById(R.id.friends_heading).setVisibility(friends);
    list.setVisibility(friends);
    findViewById(R.id.requests_heading_view).setVisibility(requests);
    requestsList.setVisibility(requests);
    findViewById(R.id.invites_heading_view).setVisibility(invites);
    invitesList.setVisibility(invites);
  }

  private void showAction(String value, int title) {
    action = value;
    findViewById(R.id.friend_action_choices).setVisibility(View.GONE);
    findViewById(R.id.friend_action_form).setVisibility(View.VISIBLE);
    ((TextView) findViewById(R.id.friend_action_title)).setText(title);
    ((Button) findViewById(R.id.friend_action_submit)).setText(title);
    LinearLayout results = findViewById(R.id.search_results);
    results.removeAllViews();
    if ("invite".equals(value)) {
      for (int i = 0; latestFriends != null && i < latestFriends.length(); i++) {
        JSONObject friend = latestFriends.optJSONObject(i);
        Button button = new Button(this);
        String username = friend.optString("username");
        button.setText(
            getString(R.string.user_search_result, friend.optString("display_name"), username));
        button.setOnClickListener(
            v -> ((EditText) findViewById(R.id.friend_username)).setText(username));
        results.addView(button);
      }
    }
    findViewById(R.id.friend_username).requestFocus();
  }

  private void hideAction() {
    action = null;
    findViewById(R.id.friend_action_form).setVisibility(View.GONE);
    findViewById(R.id.friend_action_choices).setVisibility(View.VISIBLE);
  }

  private void load() {
    findViewById(R.id.friends_loading).setVisibility(View.VISIBLE);
    findViewById(R.id.friends_retry).setVisibility(View.GONE);
    client.get("friends", "/api/v1/accounts/friends/");
    client.get("invites", "/api/v1/accounts/invites/");
  }

  private void send(String op, String path) {
    if (op == null) return;
    try {
      client.post(
          op,
          path,
          new JSONObject()
              .put(
                  "username",
                  ((EditText) findViewById(R.id.friend_username)).getText().toString().trim())
              .put("game_key", "three_piece_tic_tac_toe"));
    } catch (JSONException ignored) {
    }
  }

  private void search() {
    String query = ((EditText) findViewById(R.id.friend_username)).getText().toString().trim();
    if (query.length() < 3) {
      status.setText(R.string.username_search_help);
      return;
    }
    client.get("search", "/api/v1/accounts/users/search/?q=" + android.net.Uri.encode(query));
  }

  private void renderSearch(JSONObject json) {
    LinearLayout results = findViewById(R.id.search_results);
    results.removeAllViews();
    JSONArray values = json.optJSONArray("results");
    if (values == null || values.length() == 0) {
      status.setText(R.string.no_users_found);
      return;
    }
    status.setText(R.string.select_search_result);
    for (int i = 0; i < values.length(); i++) {
      JSONObject user = values.optJSONObject(i);
      Button b = new Button(this);
      String username = user.optString("username");
      b.setText(getString(R.string.user_search_result, user.optString("display_name"), username));
      b.setOnClickListener(
          v -> {
            ((EditText) findViewById(R.id.friend_username)).setText(username);
            send("friend", "/api/v1/accounts/friends/");
          });
      results.addView(b);
    }
  }

  private void renderFriends(JSONObject json) {
    list.removeAllViews();
    requestsList.removeAllViews();
    JSONArray friends = json.optJSONArray("friends"),
        requests = json.optJSONArray("requests"),
        outgoing = json.optJSONArray("outgoing");
    latestFriends = friends;
    for (int i = 0; friends != null && i < friends.length(); i++) {
      JSONObject f = friends.optJSONObject(i);
      Button b = new Button(this);
      String user = f.optString("username");
      b.setText(
          getString(
              R.string.friend_item_remove,
              getString(
                  R.string.friend_item,
                  f.optString("display_name"),
                  user,
                  f.optBoolean("online")
                      ? getString(R.string.online_label)
                      : getString(R.string.offline_label)),
              getString(R.string.remove_friend)));
      b.setAllCaps(false);
      b.setOnClickListener(
          v -> {
            try {
              client.delete(
                  "remove", "/api/v1/accounts/friends/", new JSONObject().put("username", user));
            } catch (JSONException ignored) {
            }
          });
      list.addView(b);
    }
    for (int i = 0; requests != null && i < requests.length(); i++) {
      JSONObject f = requests.optJSONObject(i);
      LinearLayout row = new LinearLayout(this);
      row.setOrientation(LinearLayout.VERTICAL);
      Button accept = new Button(this);
      accept.setText(getString(R.string.accept_friend, f.optString("display_name")));
      int id = f.optInt("request_id");
      accept.setOnClickListener(
          v ->
              client.post(
                  "accept",
                  "/api/v1/accounts/friends/requests/" + id + "/accept/",
                  new JSONObject()));
      Button reject = new Button(this);
      reject.setText(R.string.reject_request);
      reject.setOnClickListener(
          v -> client.delete("reject", "/api/v1/accounts/friends/requests/" + id + "/"));
      row.addView(accept);
      row.addView(reject);
      requestsList.addView(row);
    }
    for (int i = 0; outgoing != null && i < outgoing.length(); i++) {
      JSONObject f = outgoing.optJSONObject(i);
      Button cancel = new Button(this);
      cancel.setText(getString(R.string.cancel_friend_request, f.optString("display_name")));
      int id = f.optInt("request_id");
      cancel.setOnClickListener(
          v -> client.delete("reject", "/api/v1/accounts/friends/requests/" + id + "/"));
      requestsList.addView(cancel);
    }
    if (friends == null || friends.length() == 0) addEmpty(list, R.string.no_friends);
    if ((requests == null || requests.length() == 0)
        && (outgoing == null || outgoing.length() == 0))
      addEmpty(requestsList, R.string.no_friend_requests);
  }

  private void renderInvites(JSONObject json) {
    findViewById(R.id.friends_loading).setVisibility(View.GONE);
    invitesList.removeAllViews();
    JSONArray values = json.optJSONArray("results");
    for (int i = 0; values != null && i < values.length(); i++) {
      JSONObject invite = values.optJSONObject(i), sender = invite.optJSONObject("sender");
      Button b = new Button(this);
      long minutes = remainingMinutes(invite.optString("expires_at"));
      b.setText(
          getString(
              R.string.join_friend_invite_timed,
              sender.optString("display_name"),
              invite.optString("room_code"),
              minutes));
      String code = invite.optString("room_code");
      b.setOnClickListener(
          v ->
              startActivity(
                  new Intent(this, RoomListActivity.class)
                      .putExtra(RoomListActivity.EXTRA_ROOM_CODE, code)
                      .putExtra(RoomListActivity.EXTRA_GAME_KEY, invite.optString("game_key"))));
      invitesList.addView(b);
    }
    if (values == null || values.length() == 0) addEmpty(invitesList, R.string.no_game_invites);
  }

  private void addEmpty(LinearLayout target, int message) {
    TextView empty = new TextView(this);
    empty.setText(message);
    empty.setTextSize(17);
    empty.setPadding(0, 20, 0, 20);
    target.addView(empty);
  }

  private long remainingMinutes(String value) {
    try {
      return Math.max(
          1,
          java.time.Duration.between(
                  java.time.Instant.now(), java.time.OffsetDateTime.parse(value).toInstant())
              .toMinutes());
    } catch (Exception e) {
      return 0;
    }
  }

  private final AccountManagementClient.Listener listener =
      new AccountManagementClient.Listener() {
        public void onSuccess(String op, JSONObject json) {
          runOnUiThread(
              () -> {
                if ("friends".equals(op)) renderFriends(json);
                else if ("invites".equals(op)) renderInvites(json);
                else if ("search".equals(op)) renderSearch(json);
                else if ("invite".equals(op)) {
                  JSONObject room = json.optJSONObject("room");
                  if (room != null)
                    startActivity(
                        new Intent(FriendsActivity.this, RoomActivity.class)
                            .putExtra("room_code", room.optString("code")));
                  else status.setText(R.string.error_generic);
                } else {
                  status.setText(R.string.changes_saved);
                  hideAction();
                  load();
                }
              });
        }

        public void onError(String e) {
          runOnUiThread(
              () -> {
                findViewById(R.id.friends_loading).setVisibility(View.GONE);
                findViewById(R.id.friends_retry).setVisibility(View.VISIBLE);
                status.setText(AccountErrorMessages.get(FriendsActivity.this, e));
              });
        }
      };
}
