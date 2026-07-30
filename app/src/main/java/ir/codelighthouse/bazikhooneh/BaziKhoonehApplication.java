package ir.codelighthouse.bazikhooneh;

import android.app.Application;
import android.content.Context;
import ir.codelighthouse.bazikhooneh.account.PushDeviceRegistrar;

public final class BaziKhoonehApplication extends Application {
  private static Context context;

  @Override
  public void onCreate() {
    super.onCreate();
    context = getApplicationContext();
    PushDeviceRegistrar.register(this);
  }

  public static Context context() {
    if (context == null) throw new IllegalStateException("Application is not initialized");
    return context;
  }
}
