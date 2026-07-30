package ir.codelighthouse.bazikhooneh;

import android.app.job.*;
import android.content.*;
import ir.codelighthouse.bazikhooneh.account.NotificationSync;

public final class NotificationJobService extends JobService {
  public static void schedule(Context context) {
    JobScheduler scheduler = (JobScheduler) context.getSystemService(JOB_SCHEDULER_SERVICE);
    JobInfo info =
        new JobInfo.Builder(8301, new ComponentName(context, NotificationJobService.class))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .setPeriodic(15 * 60 * 1000L)
            .build();
    scheduler.schedule(info);
  }

  @Override
  public boolean onStartJob(JobParameters params) {
    new Thread(
            () -> {
              NotificationSync.refreshBackground(this);
              jobFinished(params, false);
            })
        .start();
    return true;
  }

  @Override
  public boolean onStopJob(JobParameters params) {
    return true;
  }
}
