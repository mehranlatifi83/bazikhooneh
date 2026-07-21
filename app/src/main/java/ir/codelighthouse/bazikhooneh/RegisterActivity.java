package ir.codelighthouse.bazikhooneh;

import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import ir.codelighthouse.bazikhooneh.account.AccountClient;
import ir.codelighthouse.bazikhooneh.account.AccountErrorMessages;
import ir.codelighthouse.bazikhooneh.account.AccountSession;
import ir.codelighthouse.bazikhooneh.account.SessionStore;

public final class RegisterActivity extends NavigableActivity {
    private AccountClient client;
    @Override protected void onCreate(Bundle state){super.onCreate(state);setContentView(R.layout.activity_register);
        client=new AccountClient(BuildConfig.API_BASE_URL,listener);findViewById(R.id.register_submit).setOnClickListener(v->register());}
    private String text(int id){return ((EditText)findViewById(id)).getText().toString().trim();}
    private void register(){String username=text(R.id.register_username),name=text(R.id.register_display_name),password=text(R.id.register_password);
        if(username.length()<3||password.length()<8||name.isEmpty()){show(getString(R.string.registration_fields_required));return;}
        setLoading(true);client.register(username,name,password);}
    private void setLoading(boolean value){findViewById(R.id.register_progress).setVisibility(value?View.VISIBLE:View.GONE);findViewById(R.id.register_submit).setEnabled(!value);}
    private void show(String value){TextView status=findViewById(R.id.register_error);status.setText(value);status.setVisibility(View.VISIBLE);status.announceForAccessibility(value);setLoading(false);}
    private final AccountClient.Listener listener=new AccountClient.Listener(){public void onSession(AccountSession s){runOnUiThread(()->{new SessionStore(RegisterActivity.this).save(s);setResult(RESULT_OK);finish();});}public void onLoggedOut(){}public void onError(String e){runOnUiThread(()->show(AccountErrorMessages.get(RegisterActivity.this,e)));}};
}
