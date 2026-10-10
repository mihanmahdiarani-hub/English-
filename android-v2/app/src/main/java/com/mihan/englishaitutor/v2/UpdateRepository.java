package com.mihan.englishaitutor.v2;

import android.app.DownloadManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.util.Base64;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Shared automatic update check + DownloadManager scheduling for foreground
 * and background work. No Activity or install permissions are needed here.
 *
 * DownloadManager (not a short-lived Worker) owns the 160+ MB APK transfer,
 * so download continues when English AI Tutor is closed or its process dies.
 * The Android installer is opened ONLY by the foreground AutoUpdater after
 * SHA-256 verification and with explicit consent from the user.
 */
final class UpdateRepository {
    private UpdateRepository() {}

    static final String PREFS = "english_tutor_v2_updates";
    static final String ID = "download_id";
    static final String VERSION = "download_version";
    static final String HASH = "download_sha256";
    static final String FILE_NAME = "download_filename";
    static final String LAST_CHECK = "last_check";
    static final String LAST_ERROR = "last_error";
    static final long CHECK_INTERVAL_MS = 15L * 60L * 1000L;

    private static final String MANIFEST_RAW =
            "https://raw.githubusercontent.com/mihanmahdiarani-hub/English-/v2-local-first/android-v2/update.json";
    private static final String MANIFEST_API =
            "https://api.github.com/repos/mihanmahdiarani-hub/English-/contents/android-v2/update.json?ref=v2-local-first";

    static final int LATEST = 0;
    static final int STARTED = 1;
    static final int IN_PROGRESS = 2;
    static final int READY = 3;
    static final int FAILURE = 4;

    static final class Result {
        final int state;
        final int remoteVersion;
        final String detail;
        final long downloadId;

        Result(int state, int version, String detail, long downloadId) {
            this.state = state;
            this.remoteVersion = version;
            this.detail = detail;
            this.downloadId = downloadId;
        }
    }

    static int installedVersion(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(
                    context.getPackageName(), 0);
            return (int) (Build.VERSION.SDK_INT >= 28
                    ? info.getLongVersionCode() : info.versionCode);
        } catch (Exception ignored) {
            return 0;
        }
    }

    static int status(DownloadManager manager, long id) {
        if (manager == null || id <= 0L) return -1;
        try (Cursor cursor = manager.query(new DownloadManager.Query().setFilterById(id))) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
                if (column >= 0) return cursor.getInt(column);
            }
        } catch (Exception e) {
            Diagnostics.error("UPDATE_DM_STATUS", e);
        }
        return -1;
    }

    static int failureReason(DownloadManager manager, long id) {
        if (manager == null || id <= 0) return -1;
        try (Cursor cursor = manager.query(new DownloadManager.Query().setFilterById(id))) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(DownloadManager.COLUMN_REASON);
                if (column >= 0) return cursor.getInt(column);
            }
        } catch (Exception e) {
            Diagnostics.error("UPDATE_DM_REASON", e);
        }
        return -1;
    }

    static void clearOldDownload(Context context, DownloadManager manager, long id) {
        if (manager != null && id > 0L) {
            try { manager.remove(id); } catch (Exception ignored) {}
        }
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (p.getLong(ID, -1L) == id) {
            p.edit().remove(ID).remove(VERSION).remove(HASH).remove(FILE_NAME).commit();
        }
    }

    private static String readSmallJson(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(12000);
            c.setReadTimeout(12000);
            c.setUseCaches(false);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("Cache-Control", "no-cache, no-store, max-age=0");
            c.setRequestProperty("Pragma", "no-cache");
            c.setRequestProperty("Accept", "application/vnd.github+json, application/json");
            c.setRequestProperty("User-Agent", "EnglishAITutor-Android-Updater");
            int http = c.getResponseCode();
            Diagnostics.log("UPDATE_CHECK", "source="
                    + (url.contains("api.github.com") ? "api" : "raw")
                    + " http=" + http);
            if (http != 200) throw new IllegalStateException("HTTP " + http);
            StringBuilder out = new StringBuilder();
            try (InputStream in = c.getInputStream();
                 BufferedReader reader = new BufferedReader(
                         new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    out.append(line);
                    if (out.length() > 131072) {
                        throw new IllegalArgumentException("Update metadata is too large");
                    }
                }
            }
            return out.toString();
        } finally {
            c.disconnect();
        }
    }

    private static JSONObject fetchManifest() throws Exception {
        Exception firstFailure;
        try {
            // Nonce bypasses cached old metadata after a new GitHub release.
            return new JSONObject(readSmallJson(
                    MANIFEST_RAW + "?vcheck=" + System.currentTimeMillis()));
        } catch (Exception error) {
            firstFailure = error;
            Diagnostics.log("UPDATE_CHECK", "raw unavailable, trying GitHub API");
        }
        try {
            // Many networks block raw.githubusercontent.com while allowing
            // api.github.com. Its /contents endpoint returns Base64 text.
            JSONObject wrapper = new JSONObject(readSmallJson(MANIFEST_API));
            String encoded = wrapper.optString("content", "");
            if (encoded.isEmpty()) throw new IllegalArgumentException("GitHub API empty content");
            String decoded = new String(Base64.decode(encoded, Base64.DEFAULT),
                    StandardCharsets.UTF_8);
            return new JSONObject(decoded);
        } catch (Exception fallbackFailure) {
            fallbackFailure.addSuppressed(firstFailure);
            throw fallbackFailure;
        }
    }

    static Result checkAndEnqueue(Context originalContext) {
        Context context = originalContext.getApplicationContext();
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        final int installed = installedVersion(context);
        JSONObject remote;
        try {
            remote = fetchManifest();
        } catch (Exception error) {
            Diagnostics.error("UPDATE_CHECK", error);
            p.edit().putString(LAST_ERROR, "دریافت اطلاعات نسخه جدید ناموفق بود").apply();
            return new Result(FAILURE, -1, "ارتباط با سرور آپدیت برقرار نشد", -1);
        }

        int version = remote.optInt("versionCode", -1);
        String apkUrl = remote.optString("apkUrl", "").trim();
        String expected = remote.optString("sha256", "").trim();
        Diagnostics.log("UPDATE", "installed=" + installed + " latest=" + version
                + " shaProvided=" + UpdatePolicy.validSha256(expected));
        if (!UpdatePolicy.validApkSource(version, apkUrl, expected)) {
            p.edit().putString(LAST_ERROR, "اطلاعات آپدیت معتبر نیست").apply();
            return new Result(FAILURE, version, "لینک یا امضای هش آپدیت معتبر نیست", -1);
        }
        p.edit().putLong(LAST_CHECK, System.currentTimeMillis())
                .remove(LAST_ERROR).apply();
        if (!UpdatePolicy.shouldDownload(installed, version, apkUrl, expected)) {
            return new Result(LATEST, version, "نسخه فعلی جدیدترین است", -1);
        }

        // Multiple Activity/WorkManager calls may discover the same release.
        // Serialize the entire read/status/queue/update transaction.
        synchronized (UpdateRepository.class) {
            DownloadManager manager = (DownloadManager) context.getSystemService(
                    Context.DOWNLOAD_SERVICE);
            if (manager == null) {
                p.edit().putString(LAST_ERROR, "DownloadManager دستگاه فعال نیست").apply();
                return new Result(FAILURE, version, "مدیر دانلود اندروید غیرفعال است", -1);
            }

            long previous = p.getLong(ID, -1L);
            if (previous > 0L) {
                int previousVersion = p.getInt(VERSION, -1);
                int current = status(manager, previous);
                if (previousVersion == version
                        && expected.equalsIgnoreCase(p.getString(HASH, ""))) {
                    if (current == DownloadManager.STATUS_SUCCESSFUL) {
                        return new Result(READY, version, "فایل دانلودشده آماده نصب است", previous);
                    }
                    if (current == DownloadManager.STATUS_PENDING
                            || current == DownloadManager.STATUS_RUNNING
                            || current == DownloadManager.STATUS_PAUSED) {
                        return new Result(IN_PROGRESS, version, "دانلود در حال انجام است", previous);
                    }
                }
                if (current == DownloadManager.STATUS_FAILED) {
                    int reason = failureReason(manager, previous);
                    Diagnostics.log("UPDATE_DOWNLOAD", "retry failed download reason=" + reason);
                } else if (current == -1) {
                    Diagnostics.log("UPDATE_DOWNLOAD", "old download record missing; retrying");
                }
                clearOldDownload(context, manager, previous);
            }

            try {
                // Always use a new destination name, including after a failed
                // attempt of the SAME version. Avoid ERROR_FILE_ALREADY_EXISTS.
                String name = "EnglishAITutor-v2-update-" + version + "-"
                        + System.currentTimeMillis() + ".apk";
                File dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                if (dir == null) throw new IllegalStateException("App download directory unavailable");
                if (!dir.exists() && !dir.mkdirs()) {
                    throw new IllegalStateException("Unable to create app download directory");
                }
                DownloadManager.Request request = new DownloadManager.Request(Uri.parse(apkUrl));
                request.setTitle("English AI Tutor v" + version);
                request.setDescription("دانلود خودکار نسخه جدید؛ فقط نصب با اجازه شما");
                request.setMimeType("application/vnd.android.package-archive");
                request.setAllowedOverMetered(true);
                request.setAllowedOverRoaming(false);
                request.setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                request.setDestinationInExternalFilesDir(
                        context, Environment.DIRECTORY_DOWNLOADS, name);
                long id = manager.enqueue(request);
                if (!p.edit().putLong(ID, id).putInt(VERSION, version)
                        .putString(HASH, expected).putString(FILE_NAME, name)
                        .remove(LAST_ERROR).commit()) {
                    manager.remove(id);
                    throw new IllegalStateException("Cannot persist Android download ID");
                }
                Diagnostics.log("UPDATE_DOWNLOAD", "auto queued version="
                        + version + " id=" + id + " file=" + name);
                return new Result(STARTED, version, "دانلود خودکار شروع شد", id);
            } catch (Exception e) {
                Diagnostics.error("UPDATE_DOWNLOAD", e);
                p.edit().putString(LAST_ERROR, e.getClass().getSimpleName()
                        + ": " + e.getMessage()).apply();
                return new Result(FAILURE, version, "دانلود نسخه جدید آغاز نشد", -1);
            }
        }
    }
}
