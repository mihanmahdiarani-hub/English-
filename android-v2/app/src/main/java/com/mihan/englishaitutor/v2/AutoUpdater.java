package com.mihan.englishaitutor.v2;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.database.Cursor;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * English AI Tutor v2 automatic updater. Checks public GitHub releases, downloads in
 * the background and delegates the only installation decision to Android's installer.
 * Never installs APKs silently or changes the app's signing key.
 */
final class AutoUpdater {
    private static final String MANIFEST_URL =
            "https://raw.githubusercontent.com/mihanmahdiarani-hub/English-/v2-local-first/android-v2/update.json";
    private static final String PREFS = "english_tutor_v2_updates";
    private static final String ID = "download_id";
    private static final String VERSION = "download_version";
    private static final String HASH = "download_sha256";
    private static final String LAST_CHECK = "last_check";
    private static final String LAST_PROMPT_ID = "last_prompt_id";
    private static final String LAST_PROMPT_TIME = "last_prompt_time";
    private static final long CHECK_INTERVAL_MS = 30L * 60L * 1000L;
    private static final long REPROMPT_MS = 60L * 1000L;

    private final Activity activity;
    private final DownloadManager manager;
    private final SharedPreferences prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final AtomicBoolean checking = new AtomicBoolean(false);
    private final AtomicBoolean verifying = new AtomicBoolean(false);
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
                long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (id == prefs.getLong(ID, -1) && id > 0) presentCompletedDownload(id);
            }
        };
        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= 33) {
            // The download-complete event can be sent by Android's download provider UID.
            activity.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            activity.registerReceiver(receiver, filter);
        }
    }

    void onResume() {
        if (destroyed) return;
        foreground = true;
        long id = prefs.getLong(ID, -1);
        if (id > 0) presentCompletedDownload(id);
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
        io.shutdownNow();
    }

    private int installedVersion() {
        try {
            PackageInfo info = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0);
            return (int) (Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode);
        } catch (Exception ignored) {
            return 0;
        }
    }

    private int downloadStatus(long id) {
        if (id <= 0 || manager == null) return -1;
        Cursor cursor = null;
        try {
            cursor = manager.query(new DownloadManager.Query().setFilterById(id));
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
                return idx < 0 ? -1 : cursor.getInt(idx);
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) cursor.close();
        }
        return -1;
    }

    private void clearDownload(long id) {
        if (manager != null && id > 0) {
            try { manager.remove(id); } catch (Exception ignored) {}
        }
        if (prefs.getLong(ID, -1) == id) {
            prefs.edit().remove(ID).remove(VERSION).remove(HASH).apply();
        }
    }

    private void checkForUpdates(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - prefs.getLong(LAST_CHECK, 0) < CHECK_INTERVAL_MS) return;
        if (!checking.compareAndSet(false, true)) return;
        io.execute(() -> {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(MANIFEST_URL).openConnection();
                c.setConnectTimeout(10000);
                c.setReadTimeout(10000);
                c.setUseCaches(false);
                c.setRequestProperty("Accept", "application/json");
                int responseCode = c.getResponseCode();
                Diagnostics.log("UPDATE", "manifest http=" + responseCode + " force=" + force);
                if (responseCode != 200) return;
                StringBuilder jsonText = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(c.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) jsonText.append(line);
                }
                JSONObject data = new JSONObject(jsonText.toString());
                int version = data.optInt("versionCode", 0);
                String versionName = data.optString("versionName", "");
                String apkUrl = data.optString("apkUrl", "").trim();
                String sha256 = data.optString("sha256", "").trim();
                if (version <= 0) return;
                URL target = new URL(apkUrl);
                if (!"https".equalsIgnoreCase(target.getProtocol()) ||
                        !"github.com".equalsIgnoreCase(target.getHost()) ||
                        !sha256.matches("(?i)[a-f0-9]{64}")) return;
                prefs.edit().putLong(LAST_CHECK, System.currentTimeMillis()).apply();
                int installed = installedVersion();
                Diagnostics.log("UPDATE", "installed=" + installed + " remote=" + version
                        + " name=" + versionName);
                if (version <= installed) return;

                long previousId = prefs.getLong(ID, -1);
                if (previousId > 0 && version == prefs.getInt(VERSION, -1)) {
                    int status = downloadStatus(previousId);
                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        ui.post(() -> presentCompletedDownload(previousId));
                        return;
                    }
                    if (status == DownloadManager.STATUS_PENDING || status == DownloadManager.STATUS_RUNNING ||
                            status == DownloadManager.STATUS_PAUSED) return;
                }
                ui.post(() -> beginDownload(version, versionName, apkUrl, sha256));
            } catch (Exception e) {
                Diagnostics.error("UPDATE_CHECK", e);
                // Stay usable when offline; the next foreground check will retry.
            } finally {
                if (c != null) c.disconnect();
                checking.set(false);
            }
        });
    }

    private void beginDownload(int version, String name, String url, String sha256) {
        if (destroyed || manager == null || version <= installedVersion()) return;
        long old = prefs.getLong(ID, -1);
        if (old > 0 && prefs.getInt(VERSION, -1) == version) {
            int status = downloadStatus(old);
            if (status == DownloadManager.STATUS_PENDING || status == DownloadManager.STATUS_RUNNING ||
                    status == DownloadManager.STATUS_PAUSED || status == DownloadManager.STATUS_SUCCESSFUL) return;
        }
        if (old > 0) clearDownload(old);
        try {
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            req.setTitle("English AI Tutor v2 " + name);
            req.setDescription("دریافت خودکار نسخه جدید");
            req.setMimeType("application/vnd.android.package-archive");
            req.setAllowedOverMetered(true);
            req.setAllowedOverRoaming(false);
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS,
                    "EnglishAITutor-v2-update.apk");
            long id = manager.enqueue(req);
            prefs.edit().putLong(ID, id).putInt(VERSION, version).putString(HASH, sha256).apply();
            Diagnostics.log("UPDATE", "download queued id=" + id + " version=" + version);
            Toast.makeText(activity, "نسخه جدید پیدا شد؛ دانلود آپدیت شروع شد", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Diagnostics.error("UPDATE_DOWNLOAD", e);
            // A failed enqueue will be retried on the next version check.
        }
    }

    private void presentCompletedDownload(long id) {
        if (!foreground || destroyed || id != prefs.getLong(ID, -1)) return;
        if (prefs.getInt(VERSION, 0) <= installedVersion()) {
            clearDownload(id);
            return;
        }
        int status = downloadStatus(id);
        if (status == DownloadManager.STATUS_FAILED) {
            Diagnostics.log("UPDATE", "download failed id=" + id);
            clearDownload(id);
            return;
        }
        if (status != DownloadManager.STATUS_SUCCESSFUL || !verifying.compareAndSet(false, true)) return;
        io.execute(() -> {
            boolean valid = verifySha256(id, prefs.getString(HASH, ""));
            ui.post(() -> {
                verifying.set(false);
                if (destroyed || id != prefs.getLong(ID, -1)) return;
                if (!valid) {
                    clearDownload(id);
                    Toast.makeText(activity, "فایل آپدیت معتبر نبود؛ در بررسی بعدی دوباره دریافت می‌شود", Toast.LENGTH_LONG).show();
                } else if (foreground) {
                    Diagnostics.log("UPDATE", "download verified id=" + id + "; opening installer");
                    openInstallerWithConsent(id);
                }
            });
        });
    }

    private boolean verifySha256(long id, String expected) {
        if (expected == null || !expected.matches("(?i)[a-f0-9]{64}")) return false;
        Uri uri = manager.getUriForDownloadedFile(id);
        if (uri == null) return false;
        try (InputStream in = activity.getContentResolver().openInputStream(uri)) {
            if (in == null) return false;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) digest.update(buf, 0, n);
            char[] alphabet = "0123456789abcdef".toCharArray();
            StringBuilder actual = new StringBuilder(64);
            for (byte b : digest.digest()) {
                actual.append(alphabet[(b >> 4) & 15]).append(alphabet[b & 15]);
            }
            return expected.equalsIgnoreCase(actual.toString());
        } catch (Exception ignored) {
            return false;
        }
    }

    private void openInstallerWithConsent(long id) {
        if (!foreground || destroyed) return;
        if (Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager().canRequestPackageInstalls()) {
            if (sourcePermissionScreenOpened) return;
            sourcePermissionScreenOpened = true;
            Toast.makeText(activity, "برای آپدیت فقط یک‌بار اجازه نصب از این برنامه لازم است", Toast.LENGTH_LONG).show();
            Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.getPackageName()));
            try { activity.startActivity(settings); } catch (Exception ignored) {}
            return;
        }
        sourcePermissionScreenOpened = false;
        long now = System.currentTimeMillis();
        if (prefs.getLong(LAST_PROMPT_ID, -1) == id &&
                now - prefs.getLong(LAST_PROMPT_TIME, 0) < REPROMPT_MS) {
            Diagnostics.log("UPDATE", "installer prompt throttled id=" + id);
            return;
        }
        Uri uri = manager.getUriForDownloadedFile(id);
        if (uri == null) return;
        Intent installer = new Intent(Intent.ACTION_VIEW);
        installer.setDataAndType(uri, "application/vnd.android.package-archive");
        installer.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            activity.startActivity(installer);
            prefs.edit().putLong(LAST_PROMPT_ID, id).putLong(LAST_PROMPT_TIME, now).apply();
            Diagnostics.log("UPDATE", "installer opened id=" + id);
        } catch (Exception e) {
            Diagnostics.error("UPDATE_INSTALLER", e);
            Toast.makeText(activity, "صفحه نصب آپدیت باز نشد", Toast.LENGTH_LONG).show();
        }
    }
}
