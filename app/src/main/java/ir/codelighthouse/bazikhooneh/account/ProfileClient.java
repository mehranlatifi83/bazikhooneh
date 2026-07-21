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

public final class ProfileClient {
    public interface Listener {
        void onProfile(JSONObject profile);
        void onHistory(JSONObject history);
        void onError();
    }

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private final OkHttpClient http = new OkHttpClient.Builder().connectTimeout(20,java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(45,java.util.concurrent.TimeUnit.SECONDS).writeTimeout(30,java.util.concurrent.TimeUnit.SECONDS)
            .retryOnConnectionFailure(true).build();
    private final String baseUrl;
    private final String token;
    private final Listener listener;

    public ProfileClient(String baseUrl, String token, Listener listener) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.token = token;
        this.listener = listener;
    }

    public void load() {
        request("/api/v1/accounts/me/", null, true);
        request("/api/v1/matches/", null, false);
    }

    public void update(String displayName, String avatarColor) {
        try {
            JSONObject body = new JSONObject().put("display_name", displayName)
                    .put("avatar_color", avatarColor);
            request("/api/v1/accounts/me/", body.toString(), true);
        } catch (JSONException error) {
            listener.onError();
        }
    }

    private void request(String path, String patchBody, boolean profile) {
        Request.Builder builder = new Request.Builder().url(baseUrl + path)
                .header("Authorization", "Bearer " + token);
        if (patchBody == null) builder.get();
        else builder.patch(RequestBody.create(patchBody, JSON));
        http.newCall(builder.build()).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException error) { listener.onError(); }
            @Override public void onResponse(Call call, Response response) {
                try (Response closeable = response) {
                    if (!response.isSuccessful() || response.body() == null) {
                        listener.onError();
                        return;
                    }
                    JSONObject json = new JSONObject(response.body().string());
                    if (profile) listener.onProfile(json); else listener.onHistory(json);
                } catch (IOException | JSONException error) {
                    listener.onError();
                }
            }
        });
    }
}
