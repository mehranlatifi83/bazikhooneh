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

public final class AccountManagementClient {
    public interface Listener { void onSuccess(String operation, JSONObject response); void onError(String error); }
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private final OkHttpClient http = new OkHttpClient.Builder().connectTimeout(20,java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(45,java.util.concurrent.TimeUnit.SECONDS).writeTimeout(30,java.util.concurrent.TimeUnit.SECONDS)
            .retryOnConnectionFailure(true).build();
    private final String baseUrl, token;
    private final Listener listener;

    public AccountManagementClient(String baseUrl, String token, Listener listener) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length()-1) : baseUrl;
        this.token = token; this.listener = listener;
    }
    public void get(String operation, String path) { request(operation, path, "GET", null); }
    public void post(String operation, String path, JSONObject body) { request(operation, path, "POST", body); }
    public void delete(String operation, String path) { request(operation, path, "DELETE", null); }
    public void delete(String operation, String path, JSONObject body) { request(operation, path, "DELETE", body); }
    private void request(String operation, String path, String method, JSONObject body) {
        Request.Builder builder = new Request.Builder().url(baseUrl + path);
        if (!token.isEmpty()) builder.header("Authorization", "Bearer " + token);
        RequestBody payload = RequestBody.create(body == null ? "{}" : body.toString(), JSON);
        if ("GET".equals(method)) builder.get(); else if ("DELETE".equals(method)) builder.delete(payload); else builder.post(payload);
        http.newCall(builder.build()).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException error) { listener.onError("connection_failed"); }
            @Override public void onResponse(Call call, Response response) {
                try (Response closeable = response) {
                    String text = response.body() == null ? "" : response.body().string();
                    JSONObject json = text.isEmpty() ? new JSONObject() : new JSONObject(text);
                    if (!response.isSuccessful()) { listener.onError(json.optString("error", "request_failed")); return; }
                    listener.onSuccess(operation, json);
                } catch (IOException | JSONException error) { listener.onError("invalid_server_response"); }
            }
        });
    }
}
