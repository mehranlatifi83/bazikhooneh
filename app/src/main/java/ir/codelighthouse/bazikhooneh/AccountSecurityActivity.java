package ir.codelighthouse.bazikhooneh;

import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import ir.codelighthouse.bazikhooneh.account.AccountManagementClient;
import ir.codelighthouse.bazikhooneh.account.AccountErrorMessages;
import ir.codelighthouse.bazikhooneh.account.SessionStore;
import org.json.JSONException;
import org.json.JSONObject;

public final class AccountSecurityActivity extends NavigableActivity {
    private AccountManagementClient client; private TextView status;
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); setContentView(R.layout.activity_account_security);
        SessionStore store=new SessionStore(this); if(!store.isSignedIn()){finish();return;}
        client=new AccountManagementClient(BuildConfig.API_BASE_URL,store.token(),listener);
        status=findViewById(R.id.security_status);
        findViewById(R.id.change_username).setOnClickListener(v->changeUsername());
        findViewById(R.id.change_password).setOnClickListener(v->changePassword());
        findViewById(R.id.request_email_code).setOnClickListener(v->requestEmail());
        findViewById(R.id.confirm_email_code).setOnClickListener(v->confirmEmail());
        findViewById(R.id.revoke_sessions).setOnClickListener(v->client.delete("sessions","/api/v1/accounts/sessions/"));
        client.get("profile","/api/v1/accounts/me/"); client.get("list_sessions","/api/v1/accounts/sessions/");
    }
    private String text(int id){return ((EditText)findViewById(id)).getText().toString().trim();}
    private void changeUsername(){try{client.post("username","/api/v1/accounts/username/",new JSONObject()
            .put("username",text(R.id.new_username)).put("current_password",text(R.id.username_password)));}catch(JSONException ignored){}}
    private void changePassword(){try{client.post("password","/api/v1/accounts/password/",new JSONObject()
            .put("current_password",text(R.id.current_password)).put("new_password",text(R.id.new_password)));}catch(JSONException ignored){}}
    private void requestEmail(){try{client.post("email_request","/api/v1/accounts/email/request/",new JSONObject().put("email",text(R.id.account_email)));}catch(JSONException ignored){}}
    private void confirmEmail(){try{client.post("email_confirm","/api/v1/accounts/email/confirm/",new JSONObject().put("code",text(R.id.email_code)));}catch(JSONException ignored){}}
    private void show(String value){status.setText(value);status.announceForAccessibility(value);}
    private final AccountManagementClient.Listener listener=new AccountManagementClient.Listener(){
        @Override public void onSuccess(String operation,JSONObject response){runOnUiThread(()->{
            if("profile".equals(operation)){((EditText)findViewById(R.id.new_username)).setHint(response.optString("username"));
                ((EditText)findViewById(R.id.account_email)).setText(response.optString("email"));
                String next=response.optString("next_username_change_at",""); if(!next.isEmpty()&&!"null".equals(next)) show(getString(R.string.username_next_change,next));}
            else if("list_sessions".equals(operation)) show(getString(R.string.active_sessions_count,response.optJSONArray("results").length()));
            else if("email_request".equals(operation)){String code=response.optString("development_code","");if(!code.isEmpty())((EditText)findViewById(R.id.email_code)).setText(code);show(getString(R.string.verification_sent));}
            else {show(getString(R.string.changes_saved));if("username".equals(operation))new SessionStore(AccountSecurityActivity.this).updateUsername(response.optString("username"));}
        });}
        @Override public void onError(String error){runOnUiThread(()->show(AccountErrorMessages.get(AccountSecurityActivity.this,error)));}
    };
}
