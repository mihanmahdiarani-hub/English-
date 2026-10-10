package com.mihan.englishaitutor.v2;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Auto-update without a download or installation confirmation prompt:
 * - WorkManager checks/queues even with the app closed.
 * - Android DownloadManager downloads large versioned APKs autonomously.
 * - SHA-256 is checked before presenting the Android package installer.
 * - ONLY the Android installation permission/confirmation is requested.
 *
 * Installation is NEVER silent, and no other app or APK is touched.
 */
final class AutoUpdater {
    private static final String PREFS = UpdateRepository.PREFS;
    private static final String ID = UpdateRepository.ID;
    private static final String VERSION = UpdateRepository.VERSION;
    private static final String HASH = UpdateRepository.HASH;
    private static final String FILE_NAME = UpdateRepository.FILE_NAME;
    private static final String LAST_CHECK = UpdateRepository.LAST_CHECK;
    private static final String LAST_PROMPT_ID = "last_prompt_id";
    private static final String LAST_PROMPT_TIME = "last_prompt_time";
    private static final long CHECK_INTERVAL_MS = UpdateRepository.CHECK_INTERVAL_MS;
    private static final long REPROMPT_MS = 60L * 1000L;

    private final Activity activity;
    private final DownloadManager manager;
    private final SharedPreferences prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final AtomicBoolean checking = new AtomicBoolean(false);
    private final AtomicBoolean verifying = new AtomicBoolean(false);
    private final AtomicBoolean manualCheck = new AtomicBoolean(false);
    private final BroadcastReceiver receiver;
    private boolean foreground;
    private boolean sourcePermissionScreenOpened;
    private boolean destroyed;

    private final Runnable periodicCheck = new Runnable() {
        @Override public void run() {
            if (!foreground || destroyed) return;
            checkForUpdates(false);
            ui.postDelayed(this, CHECK_INTERVAL_MS);
        }
    };

    AutoUpdater(Activity activity) {
        this.activity = activity;
        this.manager = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
        this.prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (!DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) return;
                long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L);
                if (id > 0L && id == prefs.getLong(ID, -1L)) {
                    Diagnostics.log("UPDATE", "download completed broadcast id=" + id);
                    presentCompletedDownload(id);
                }
            }
        };
        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= 33) {
            activity.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            activity.registerReceiver(receiver, filter);
        }
        // Durable periodic + initial checks. If the user leaves the app,
        // DownloadManager continues the transfer under Android management.
        UpdateCheckWorker.schedule(activity.getApplicationContext());
    }

    void checkNow() {
        if (destroyed) return;
        manualCheck.set(true);
        checkForUpdates(true);
    }

    private void notifyManual(String message) {
        if (!manualCheck.getAndSet(false) || destroyed) return;
        ui.post(() -> {
            if (!destroyed) Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
        });
    }

    void onResume() {
        if (destroyed) return;
        foreground = true;

        long id = prefs.getLong(ID, -1L);
        if (id > 0L) presentCompletedDownload(id);

        // Do not wait for WorkManager's next periodic interval when the user
        // opens English AI Tutor. This also retries a failed APK immediately.
        ui.removeCallbacks(periodicCheck);
        checkForUpdates(true);
        ui.postDelayed(periodicCheck, CHECK_INTERVAL_MS);
    }

    void onPause() {
        foreground = false;
        ui.removeCallbacks(periodicCheck);
    }

    void onDestroy() {
        destroyed = true;
        onPause();
        try { activity.unregisterReceiver(receiver); } catch (Exception ignored) {}
        // Allow an already started check to finish its DownloadManager queue
        // operation without holding up Activity destruction.
        io.shutdown();
    }

    private void checkForUpdates(boolean force) {
        if (destroyed) return;
        long now = System.currentTimeMillis();
        if (!force && now - prefs.getLong(LAST_CHECK, 0L) < CHECK_INTERVAL_MS) return;
        if (!checking.compareAndSet(false, true)) return;
        io.execute(() -> {
            UpdateRepository.Result result;
            try {
                result = UpdateRepository.checkAndEnqueue(activity.getApplicationContext());
            } catch (Exception unexpected) {
                Diagnostics.error("UPDATE_CHECK", unexpected);
                result = new UpdateRepository.Result(
                        UpdateRepository.FAILURE, -1, "بررسی خودکار نسخه ناموفق بود", -1L);
            }
            final UpdateRepository.Result finished = result;
            ui.post(() -> {
                checking.set(false);
                if (destroyed) return;
                if (finished.state == UpdateRepository.LATEST) {
                    notifyManual("آخرین نسخه نصب است: v" + UpdateRepository.installedVersion(activity));
                } else if (finished.state == UpdateRepository.STARTED) {
                    Diagnostics.log("UPDATE", "automatic download started v"
                            + finished.remoteVersion + " id=" + finished.downloadId);
                    manualCheck.set(false);
                    if (foreground) {
                        Toast.makeText(activity, "آپدیت v" + finished.remoteVersion
                                + " به‌صورت خودکار در حال دانلود است",
                                Toast.LENGTH_LONG).show();
                    }
                } else if (finished.state == UpdateRepository.IN_PROGRESS) {
                    notifyManual("دانلود خودکار نسخه " + finished.remoteVersion
                            + " همچنان در حال انجام است");
                } else if (finished.state == UpdateRepository.READY) {
                    notifyManual("نسخه " + finished.remoteVersion + " آماده نصب است");
                    if (foreground) presentCompletedDownload(finished.downloadId);
                } else {
                    Diagnostics.log("UPDATE", "update check failed: " + finished.detail);
                    notifyManual(finished.detail);
                }
            });
        });
    }

    private int downloadStatus(long id) {
        return UpdateRepository.status(manager, id);
    }

    private void clearDownload(long id) {
        UpdateRepository.clearOldDownload(activity.getApplicationContext(), manager, id);
    }

    private File downloadedApkFile() {
        String name = prefs.getString(FILE_NAME, "");
        if (name.isEmpty() || !name.matches("EnglishAITutor-v2-update-[0-9]+-[0-9]+\\.apk")) {
            return null;
        }
        File dir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (dir == null) return null;
        return new File(dir, name);
    }

    private void presentCompletedDownload(long id) {
        if (!foreground || destroyed || id <= 0L
                || id != prefs.getLong(ID, -1L)) return;
        int installed = UpdateRepository.installedVersion(activity);
        int pendingVersion = prefs.getInt(VERSION, 0);
        if (pendingVersion <= installed) {
            clearDownload(id);
            return;
        }

        int status = downloadStatus(id);
        if (status == DownloadManager.STATUS_FAILED || status == -1) {
            int reason = UpdateRepository.failureReason(manager, id);
            Diagnostics.log("UPDATE_DOWNLOAD", "download failed id=" + id
                    + " status=" + status + " reason=" + reason);
            clearDownload(id);
            // Next check immediately queues a FRESH filename, rather than
            // silently leaving the app stuck with a failed download ID.
            checkForUpdates(true);
            return;
        }
        if (status != DownloadManager.STATUS_SUCCESSFUL
                || !verifying.compareAndSet(false, true)) return;

        io.execute(() -> {
            boolean valid = verifySha256(id, prefs.getString(HASH, ""));
            ui.post(() -> {
                verifying.set(false);
                if (destroyed || id != prefs.getLong(ID, -1L)) return;
                if (!valid) {
                    Diagnostics.log("UPDATE", "APK SHA-256 mismatch, id=" + id);
                    clearDownload(id);
                    Toast.makeText(activity, "فایل آپدیت معتبر نبود؛ دوباره دانلود می‌شود",
                            Toast.LENGTH_LONG).show();
                    checkForUpdates(true);
                } else if (foreground && UpdatePolicy.mayOfferInstallation(
                        UpdateRepository.installedVersion(activity),
                        pendingVersion, true, foreground)) {
                    Diagnostics.log("UPDATE", "APK verified; opening Android installer");
                    openInstallerWithConsent(id);
                }
            });
        });
    }

    private boolean verifySha256(long id, String expected) {
        if (!UpdatePolicy.validSha256(expected)) return false;
        File downloaded = downloadedApkFile();
        try {
            // App-specific external files work without storage permissions and
            // can also be served by our FileProvider to Android's installer.
            try (InputStream stream = downloaded != null && downloaded.isFile()
                    ? new FileInputStream(downloaded) : openDownloadManagerUri(id)) {
                if (stream == null) return false;
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] buf = new byte[65536];
                int read;
                while ((read = stream.read(buf)) != -1) digest.update(buf, 0, read);
                char[] hex = "0123456789abcdef".toCharArray();
                StringBuilder actual = new StringBuilder(64);
                for (byte one : digest.digest()) {
                    actual.append(hex[(one >> 4) & 15]).append(hex[one & 15]);
                }
                boolean valid = expected.equalsIgnoreCase(actual.toString());
                Diagnostics.log("UPDATE_SHA", "download id=" + id + " matches=" + valid);
                return valid;
            }
        } catch (Exception error) {
            Diagnostics.error("UPDATE_SHA", error);
            return false;
        }
    }

    private InputStream openDownloadManagerUri(long id) throws Exception {
        if (manager == null) return null;
        Uri uri = manager.getUriForDownloadedFile(id);
        return uri == null ? null : activity.getContentResolver().openInputStream(uri);
    }

    private void openInstallerWithConsent(long id) {
        if (!foreground || destroyed || id != prefs.getLong(ID, -1L)) return;
        if (Build.VERSION.SDK_INT >= 26 &&
                !activity.getPackageManager().canRequestPackageInstalls()) {
            if (sourcePermissionScreenOpened) return;
            sourcePermissionScreenOpened = true;
            Toast.makeText(activity, "فایل دانلود شده؛ فقط اجازه نصب از این برنامه لازم است",
                    Toast.LENGTH_LONG).show();
            Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.getPackageName()));
            try {
                activity.startActivity(settings);
            } catch (Exception error) {
                Diagnostics.error("UPDATE_PERMISSION", error);
                sourcePermissionScreenOpened = false;
                Toast.makeText(activity, "باز کردن مجوز نصب در تنظیمات ناموفق بود",
                        Toast.LENGTH_LONG).show();
            }
            return;
        }
        sourcePermissionScreenOpened = false;
        long now = System.currentTimeMillis();
        if (prefs.getLong(LAST_PROMPT_ID, -1L) == id
                && now - prefs.getLong(LAST_PROMPT_TIME, 0L) < REPROMPT_MS) {
            Diagnostics.log("UPDATE", "installer already offered id=" + id);
            return;
        }

        Uri uri = null;
        File file = downloadedApkFile();
        if (file != null && file.isFile()) {
            try {
                uri = FileProvider.getUriForFile(activity,
                        activity.getPackageName() + ".updates", file);
            } catch (Exception e) {
                Diagnostics.error("UPDATE_FILE_PROVIDER", e);
            }
        }
        if (uri == null && manager != null) uri = manager.getUriForDownloadedFile(id);
        if (uri == null) {
            Diagnostics.log("UPDATE", "no URI for finished APK id=" + id);
            Toast.makeText(activity, "فایل دانلود شده پیدا نشد", Toast.LENGTH_LONG).show();
            return;
        }

        Intent installer = new Intent(Intent.ACTION_VIEW);
        installer.setDataAndType(uri, "application/vnd.android.package-archive");
        installer.setClipData(ClipData.newUri(
                activity.getContentResolver(), "English AI Tutor update", uri));
        installer.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            activity.startActivity(installer);
            prefs.edit().putLong(LAST_PROMPT_ID, id)
                    .putLong(LAST_PROMPT_TIME, now).apply();
            Diagnostics.log("UPDATE", "Android installer opened for id=" + id
                    + "; only the user can approve installation");
        } catch (Exception error) {
            Diagnostics.error("UPDATE_INSTALLER", error);
            Toast.makeText(activity, "باز کردن نصب‌کننده آپدیت ناموفق بود",
                    Toast.LENGTH_LONG).show();
        }
    }
}
