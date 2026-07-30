package ir.codelighthouse.bazikhooneh.account;

import android.os.Build;
import ir.codelighthouse.bazikhooneh.BaziKhoonehApplication;
import ir.codelighthouse.bazikhooneh.BuildConfig;
import java.util.concurrent.TimeUnit;
import okhttp3.Authenticator;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.Route;
import org.json.JSONObject;

public final class SessionAuthenticator implements Authenticator {
  private static final Object ROTATION_LOCK = new Object();
  private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
  private final String baseUrl;

  public SessionAuthenticator(String baseUrl) {
    this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
  }

  @Override
  public Request authenticate(Route route, Response response) {
    if (responseCount(response) > 1) return null;
    // Game WebSockets use a separate reconnect credential, not the
    // account access token. Never replace that credential here.
    if (response.request().url().encodedPath().startsWith("/ws/")) return null;
    SessionStore store = new SessionStore(BaziKhoonehApplication.context());
    if (store.refreshToken().isEmpty()) return null;
    synchronized (ROTATION_LOCK) {
      String failed = response.request().header("Authorization");
      String current = store.token();
      if (failed != null && !failed.equals("Bearer " + current) && !current.isEmpty()) {
        return response.request().newBuilder().header("Authorization", "Bearer " + current).build();
      }
      try {
        JSONObject body =
            new JSONObject()
                .put("refresh_token", store.refreshToken())
                .put("device_name", (Build.MANUFACTURER + " " + Build.MODEL).trim())
                .put("app_version", BuildConfig.VERSION_NAME);
        OkHttpClient refreshHttp =
            new OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
        Request refresh =
            new Request.Builder()
                .url(baseUrl + "/api/v1/accounts/refresh/")
                .post(RequestBody.create(body.toString(), JSON))
                .build();
        try (Response refreshed = refreshHttp.newCall(refresh).execute()) {
          if (!refreshed.isSuccessful() || refreshed.body() == null) {
            if (refreshed.code() == 401) store.clear();
            return null;
          }
          AccountSession session = AccountSession.from(new JSONObject(refreshed.body().string()));
          store.save(session);
          return response
              .request()
              .newBuilder()
              .header("Authorization", "Bearer " + session.token)
              .build();
        }
      } catch (Exception error) {
        return null;
      }
    }
  }

  private static int responseCount(Response response) {
    int count = 1;
    while ((response = response.priorResponse()) != null) count++;
    return count;
  }
}
