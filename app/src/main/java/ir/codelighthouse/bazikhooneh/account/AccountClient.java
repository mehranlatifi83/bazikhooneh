package ir.codelighthouse.bazikhooneh.account;

import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONException;
import org.json.JSONObject;

public final class AccountClient {
    public interface Listener {
        void onSession(AccountSession session);
        void onLoggedOut();
        void onError(String error);
    }

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private final OkHttpClient http = new OkHttpClient.Builder().connectTimeout(20,java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(45,java.util.concurrent.TimeUnit.SECONDS).writeTimeout(30,java.util.concurrent.TimeUnit.SECONDS)
            .retryOnConnectionFailure(true).build();
    private final String baseUrl;
    private final Listener listener;

    public AccountClient(String baseUrl, Listener listener) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.listener = listener;
    }

    public void register(String username, String displayName, String password) {
        try {
            JSONObject body = new JSONObject().put("username", username)
                    .put("display_name", displayName).put("password", password);
            sessionRequest("/api/v1/accounts/register/", body);
        } catch (JSONException error) {
            listener.onError("invalid_request");
        }
    }

    public void login(String username, String password) {
        try {
            JSONObject body = new JSONObject().put("username", username).put("password", password);
            sessionRequest("/api/v1/accounts/login/", body);
        } catch (JSONException error) {
            listener.onError("invalid_request");
        }
    }

    public void logout(String token) {
        Request request = new Request.Builder().url(baseUrl + "/api/v1/accounts/logout/")
                .header("Authorization", "Bearer " + token)
                .post(RequestBody.create("{}", JSON)).build();
        http.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException error) { listener.onLoggedOut(); }
            @Override public void onResponse(Call call, Response response) {
                response.close();
                listener.onLoggedOut();
            }
        });
    }

    private void sessionRequest(String path, JSONObject body) {
        Request request = new Request.Builder().url(baseUrl + path)
                .post(RequestBody.create(body.toString(), JSON)).build();
        http.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException error) {
                listener.onError("connection_failed");
            }

            @Override public void onResponse(Call call, Response response) {
                try (Response closeable = response) {
                    String text = response.body() == null ? "" : response.body().string();
                    if (!response.isSuccessful()) {
                        listener.onError(parseError(text));
                        return;
                    }
                    listener.onSession(AccountSession.from(new JSONObject(text)));
                } catch (IOException | JSONException error) {
                    listener.onError("invalid_server_response");
                }
            }
        });
    }

    private static String parseError(String text) {
        try {
            JSONObject json = new JSONObject(text);
            if (json.has("error")) return json.getString("error");
            if (json.has("username")) return json.getJSONArray("username").getString(0);
            return "invalid_account_data";
        } catch (JSONException ignored) {
            return "server_error";
        }
    }
}
