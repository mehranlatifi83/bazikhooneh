package ir.codelighthouse.bazikhooneh.account;

import android.content.Context;
import com.google.firebase.messaging.FirebaseMessaging;
import ir.codelighthouse.bazikhooneh.BuildConfig;
import java.util.Locale;
import org.json.JSONObject;

/** Keeps the Firebase installation token associated with the currently signed-in account. */
public final class PushDeviceRegistrar {
    private static final String PREFS="push_device";
    private static final String TOKEN="fcm_token";
    private PushDeviceRegistrar(){}

    public static void register(Context context){
        Context app=context.getApplicationContext();SessionStore session=new SessionStore(app);
        if(!session.isSignedIn())return;
        FirebaseMessaging.getInstance().getToken().addOnSuccessListener(token->registerToken(app,token));
    }
    public static void registerToken(Context context,String token){
        if(token==null||token.isEmpty())return;
        SessionStore session=new SessionStore(context);if(!session.isSignedIn())return;
        context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putString(TOKEN,token).apply();
        JSONObject body=new JSONObject();
        try{
            body.put("token",token);body.put("app_version",BuildConfig.VERSION_NAME);
            body.put("locale",Locale.getDefault().toLanguageTag());
        }catch(Exception ignored){}
        new AccountManagementClient(BuildConfig.API_BASE_URL,session.token(),new SilentListener())
                .post("push_register","/api/v1/accounts/push-devices/",body);
    }
    public static void unregister(Context context){
        Context app=context.getApplicationContext();SessionStore session=new SessionStore(app);
        String token=app.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString(TOKEN,"");
        if(session.isSignedIn()&&!token.isEmpty()){
            JSONObject body=new JSONObject();try{body.put("token",token);}catch(Exception ignored){}
            new AccountManagementClient(BuildConfig.API_BASE_URL,session.token(),new SilentListener())
                    .delete("push_unregister","/api/v1/accounts/push-devices/",body);
        }
        app.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().remove(TOKEN).apply();
        FirebaseMessaging.getInstance().deleteToken();
    }
    private static final class SilentListener implements AccountManagementClient.Listener{
        @Override public void onSuccess(String operation,JSONObject response){}
        @Override public void onError(String error){}
    }
}
