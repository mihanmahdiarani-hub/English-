package com.mihan.englishaitutor.v2;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

/**
 * Find/queue a newer APK even when English AI Tutor isn't running.
 * DownloadManager owns the actual transfer so Android can finish the 160MB+
 * download after this Worker has returned. This worker NEVER installs APKs.
 */
public final class UpdateCheckWorker extends Worker {
    private static final String PERIODIC_NAME = "english-ai-tutor-v2-periodic-download";
    private static final String STARTUP_NAME = "english-ai-tutor-v2-on-launch-download";

    public UpdateCheckWorker(@NonNull Context appContext,
                             @NonNull WorkerParameters parameters) {
        super(appContext, parameters);
    }

    @NonNull @Override
    public Result doWork() {
        try {
            // WorkManager may start the app process without MainActivity.
            // Initialize diagnostics with the application context first.
            Diagnostics.init(getApplicationContext());
            UpdateRepository.Result check =
                    UpdateRepository.checkAndEnqueue(getApplicationContext());
            Diagnostics.log("UPDATE_WORKER", "state=" + check.state
                    + " remoteVersion=" + check.remoteVersion
                    + " message=" + check.detail);
            return check.state == UpdateRepository.FAILURE
                    ? Result.retry() : Result.success();
        } catch (Exception unexpected) {
            Diagnostics.error("UPDATE_WORKER", unexpected);
            return Result.retry();
        }
    }

    static void schedule(Context context) {
        Context app = context.getApplicationContext();
        Constraints net = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        try {
            WorkManager work = WorkManager.getInstance(app);
            // WorkManager respects Android's battery/network restrictions.
            // Fifteen minutes is the platform's minimum periodic interval;
            // it is not a promise of real-time checks with the app closed.
            PeriodicWorkRequest periodic = new PeriodicWorkRequest.Builder(
                    UpdateCheckWorker.class, 15, TimeUnit.MINUTES)
                    .setConstraints(net)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,
                            30, TimeUnit.SECONDS)
                    .build();
            work.enqueueUniquePeriodicWork(PERIODIC_NAME,
                    ExistingPeriodicWorkPolicy.KEEP, periodic);

            // A network-constrained durable initial check survives an Activity
            // closing immediately; the foreground updater also checks onResume.
            OneTimeWorkRequest startup = new OneTimeWorkRequest.Builder(
                    UpdateCheckWorker.class).setConstraints(net)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,
                            30, TimeUnit.SECONDS).build();
            work.enqueueUniqueWork(STARTUP_NAME, ExistingWorkPolicy.KEEP, startup);
        } catch (Exception e) {
            Diagnostics.error("UPDATE_WORK_SCHEDULE", e);
            // Foreground AutoUpdater remains usable if WorkManager unavailable.
        }
    }
}
