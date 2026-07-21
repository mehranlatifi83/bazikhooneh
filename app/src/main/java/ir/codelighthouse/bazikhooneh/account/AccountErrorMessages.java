package ir.codelighthouse.bazikhooneh.account;
import android.content.Context;
import ir.codelighthouse.bazikhooneh.R;
public final class AccountErrorMessages {
 private AccountErrorMessages(){}
 public static String get(Context c,String code){
  switch(code){
   case "connection_failed": return c.getString(R.string.error_connection_failed);
   case "invalid_credentials": return c.getString(R.string.error_invalid_credentials);
   case "username_taken": return c.getString(R.string.error_username_taken);
   case "username_cooldown": return c.getString(R.string.error_username_cooldown);
   case "invalid_password": return c.getString(R.string.error_invalid_password);
   case "invalid_email": return c.getString(R.string.error_invalid_email);
   case "email_taken": return c.getString(R.string.error_email_taken);
   case "invalid_or_expired_code": return c.getString(R.string.error_invalid_code);
   case "user_not_found": return c.getString(R.string.error_user_not_found);
   case "not_friends": return c.getString(R.string.error_not_friends);
   default: return c.getString(R.string.error_generic);
  }
 }
}
