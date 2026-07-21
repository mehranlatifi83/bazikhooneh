package ir.codelighthouse.bazikhooneh;

import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import ir.codelighthouse.bazikhooneh.account.AccountManagementClient;
import ir.codelighthouse.bazikhooneh.account.AccountErrorMessages;
import org.json.JSONException;
import org.json.JSONObject;

public final class PasswordResetActivity extends NavigableActivity {
    private AccountManagementClient client; private TextView status;
    @Override protected void onCreate(Bundle state){super.onCreate(state);setContentView(R.layout.activity_password_reset);
        status=findViewById(R.id.reset_status);client=new AccountManagementClient(BuildConfig.API_BASE_URL,"",listener);
        findViewById(R.id.request_reset).setOnClickListener(v->request());findViewById(R.id.confirm_reset).setOnClickListener(v->confirm());findViewById(R.id.change_reset_email).setOnClickListener(v->showRequestStep());}
    private String text(int id){return ((EditText)findViewById(id)).getText().toString().trim();}
    private void request(){try{client.post("request","/api/v1/accounts/password-reset/request/",new JSONObject().put("email",text(R.id.reset_email)));}catch(JSONException ignored){}}
    private void confirm(){try{client.post("confirm","/api/v1/accounts/password-reset/confirm/",new JSONObject().put("code",text(R.id.reset_code)).put("new_password",text(R.id.reset_password)));}catch(JSONException ignored){}}
    private void showRequestStep(){findViewById(R.id.reset_request_step).setVisibility(View.VISIBLE);findViewById(R.id.reset_confirm_step).setVisibility(View.GONE);status.setText("");}
    private void showConfirmStep(JSONObject json){findViewById(R.id.reset_request_step).setVisibility(View.GONE);findViewById(R.id.reset_confirm_step).setVisibility(View.VISIBLE);((TextView)findViewById(R.id.reset_code_destination)).setText(getString(R.string.code_sent_to,text(R.id.reset_email)));String code=json.optString("development_code","");if(!code.isEmpty())((EditText)findViewById(R.id.reset_code)).setText(code);findViewById(R.id.reset_code).requestFocus();}
    private final AccountManagementClient.Listener listener=new AccountManagementClient.Listener(){public void onSuccess(String op,JSONObject json){runOnUiThread(()->{if("request".equals(op)){showConfirmStep(json);status.setText(R.string.reset_code_sent);}else{status.setText(R.string.password_reset_done);}});}public void onError(String e){runOnUiThread(()->status.setText(AccountErrorMessages.get(PasswordResetActivity.this,e)));}};
}
