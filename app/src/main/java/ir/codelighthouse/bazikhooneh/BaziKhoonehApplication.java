package ir.codelighthouse.bazikhooneh;

import android.app.Application;
import android.content.Context;
import ir.codelighthouse.bazikhooneh.account.PushDeviceRegistrar;
import java.lang.ref.WeakReference;

public final class BaziKhoonehApplication extends Application {
  private static WeakReference<Context> context = new WeakReference<>(null);

  @Override
  public void onCreate() {
    super.onCreate();
    context = new WeakReference<>(getApplicationContext());
    PushDeviceRegistrar.register(this);
  }

  public static Context context() {
    Context value = context.get();
    if (value == null) throw new IllegalStateException("Application is not initialized");
    return value;
  }
}
