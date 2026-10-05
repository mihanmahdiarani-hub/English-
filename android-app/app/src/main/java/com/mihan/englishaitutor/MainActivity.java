package com.mihan.englishaitutor;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.util.Base64;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.Transformer;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MainActivity extends Activity {
    private static final String APP_URL = "https://english-ai-tutor-2vki.onrender.com";
    private static final String UPDATE_URL = APP_URL + "/android/update.json";
    private static final int FILE_CHOOSER_REQUEST = 2001;
    private static final int AUDIO_PERMISSION_REQUEST = 2002;
    private static final String PREFS = "english_ai_tutor_native";
    private static final String PREF_PENDING_DOWNLOAD = "pending_download_id";
    private static final String PREF_PENDING_VERSION = "pending_version_code";
    private static final String PREF_LAST_UPDATE_CHECK = "last_update_check";

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private PermissionRequest pendingWebPermissionRequest;
    private DownloadManager downloadManager;
    private SharedPreferences prefs;
    private BroadcastReceiver downloadReceiver;

    private Uri selectedMediaUri;
    private String selectedMediaName = "media";
    private String selectedMediaMime = "video/mp4";
    private Transformer activeTransformer;
    private volatile String activeTransformRequestId;
    private final Map<String, File> nativeAudioOutputs = new ConcurrentHashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        downloadManager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);

        configureWebView();
        registerDownloadReceiver();

        if (savedInstanceState == null) webView.loadUrl(APP_URL);
        else webView.restoreState(savedInstanceState);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, AUDIO_PERMISSION_REQUEST);
        }

        checkForUpdate(false);
    }

    private void configureWebView() {
        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setLoadsImagesAutomatically(true);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setUserAgentString(settings.getUserAgentString() + " EnglishAITutorAndroid/" + BuildConfig.VERSION_NAME);

        webView.addJavascriptInterface(new AndroidMediaBridge(), "AndroidMedia");
        CookieManager.getInstance().setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        }

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (uri != null && uri.getHost() != null && uri.getHost().endsWith("onrender.com")) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                    return true;
                } catch (Exception ignored) {
                    return false;
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> {
                    boolean wantsAudio = false;
                    for (String resource : request.getResources()) {
                        if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) {
                            wantsAudio = true;
                            break;
                        }
                    }
                    if (!wantsAudio) {
                        request.deny();
                        return;
                    }
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                    } else {
                        pendingWebPermissionRequest = request;
                        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, AUDIO_PERMISSION_REQUEST);
                    }
                });
            }

            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = callback;

                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/pdf", "video/*", "audio/*"});
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                try {
                    startActivityForResult(Intent.createChooser(intent, "انتخاب فایل"), FILE_CHOOSER_REQUEST);
                } catch (Exception ex) {
                    filePathCallback.onReceiveValue(null);
                    filePathCallback = null;
                    Toast.makeText(MainActivity.this, "فایل‌منیجر در دسترس نیست", Toast.LENGTH_SHORT).show();
                }
                return true;
            }
        });

        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            if (url == null || url.startsWith("blob:")) {
                Toast.makeText(this, "این فایل از داخل خود اپ ذخیره می‌شود", Toast.LENGTH_SHORT).show();
                return;
            }
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (Exception ex) {
                Toast.makeText(this, "دانلود باز نشد", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void rememberSelectedMedia(Uri uri, Intent sourceIntent) {
        if (uri == null) return;
        String mime = null;
        try { mime = getContentResolver().getType(uri); } catch (Exception ignored) {}
        String name = queryDisplayName(uri);
        boolean mediaMime = mime != null && (mime.startsWith("video/") || mime.startsWith("audio/"));
        boolean mediaExt = name != null && name.toLowerCase().matches(".*\\.(mp4|mkv|mov|avi|webm|3gp|mp3|m4a|aac|wav|ogg|flac)$");
        if (!mediaMime && !mediaExt) return;

        selectedMediaUri = uri;
        selectedMediaName = (name == null || name.isEmpty()) ? "media" : name;
        selectedMediaMime = (mime == null || mime.isEmpty()) ? "video/mp4" : mime;

        try {
            int flags = sourceIntent == null ? Intent.FLAG_GRANT_READ_URI_PERMISSION :
                    sourceIntent.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            getContentResolver().takePersistableUriPermission(uri, flags | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {}
    }

    private String queryDisplayName(Uri uri) {
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) return cursor.getString(index);
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) cursor.close();
        }
        return null;
    }

    private String safeRequestId(String requestId) {
        String value = requestId == null ? "" : requestId.replaceAll("[^A-Za-z0-9_-]", "");
        if (value.isEmpty()) value = "media" + System.currentTimeMillis();
        return value.length() > 80 ? value.substring(0, 80) : value;
    }

    private boolean requestStillActive(String requestId) {
        return requestId != null && requestId.equals(activeTransformRequestId);
    }

    private String baseOutputName(long startMs, long endMs) {
        String base = selectedMediaName == null ? "media" : selectedMediaName.replaceFirst("\\.[^.]+$", "");
        String range = endMs > startMs
                ? "-" + Math.round(startMs / 1000d) + "-" + Math.round(endMs / 1000d)
                : "-" + Math.round(startMs / 1000d) + "-end";
        return base + range + ".m4a";
    }

    private void publishNativeAudio(String requestId, File outputFile, long startMs, long endMs,
                                    String extractionMode, String sourceAudioMime) {
        if (!requestStillActive(requestId)) {
            try { outputFile.delete(); } catch (Exception ignored) {}
            return;
        }
        if (!outputFile.exists() || outputFile.length() <= 0) {
            notifyNativeAudioError(requestId, "خروجی صوتی خالی بود.");
            return;
        }
        nativeAudioOutputs.put(requestId, outputFile);
        activeTransformRequestId = null;
        try {
            JSONObject meta = new JSONObject();
            meta.put("name", baseOutputName(startMs, endMs));
            meta.put("mimeType", "audio/m4a");
            meta.put("size", outputFile.length());
            meta.put("startSec", startMs / 1000d);
            meta.put("endSec", endMs > 0 ? endMs / 1000d : 0d);
            meta.put("sourceName", selectedMediaName);
            meta.put("sourceMimeType", selectedMediaMime);
            meta.put("sourceAudioMime", sourceAudioMime == null ? "" : sourceAudioMime);
            meta.put("extractionMode", extractionMode);
            meta.put("androidSdk", Build.VERSION.SDK_INT);
            meta.put("appVersion", BuildConfig.VERSION_NAME);
            notifyNativeAudioReady(requestId, meta);
        } catch (Exception ex) {
            notifyNativeAudioError(requestId, "ساخت اطلاعات خروجی صوتی شکست خورد: " + ex.getMessage());
        }
    }

    private void startNativeAudioExtraction(double startSecRaw, double endSecRaw, String requestIdRaw) {
        if (selectedMediaUri == null) {
            notifyNativeAudioError(requestIdRaw, "فایل فیلم در Android پیدا نشد؛ دوباره فیلم را انتخاب کن.");
            return;
        }

        final String requestId = safeRequestId(requestIdRaw);
        final long startMs = Math.max(0L, Math.round(startSecRaw * 1000d));
        final long endMs = endSecRaw > 0 ? Math.max(0L, Math.round(endSecRaw * 1000d)) : 0L;
        if (endMs > 0 && endMs <= startMs) {
            notifyNativeAudioError(requestId, "زمان پایان باید بعد از زمان شروع باشد.");
            return;
        }

        if (activeTransformer != null) {
            try { activeTransformer.cancel(); } catch (Exception ignored) {}
            activeTransformer = null;
        }
        activeTransformRequestId = requestId;

        final Uri mediaUri = selectedMediaUri;
        final File fastOutput = new File(getCacheDir(), "passthrough-" + requestId + ".m4a");
        if (fastOutput.exists()) {
            try { fastOutput.delete(); } catch (Exception ignored) {}
        }

        new Thread(() -> {
            try {
                LocalAudioPassthrough.Result result =
                        LocalAudioPassthrough.extract(MainActivity.this, mediaUri, startMs, endMs, fastOutput);
                if (!requestStillActive(requestId)) {
                    try { fastOutput.delete(); } catch (Exception ignored) {}
                    return;
                }
                publishNativeAudio(requestId, fastOutput, startMs, endMs,
                        "android-fast-passthrough", result.sourceMime);
            } catch (Exception fastError) {
                try { fastOutput.delete(); } catch (Exception ignored) {}
                if (!requestStillActive(requestId)) return;
                final String fastMessage = fastError.getClass().getSimpleName() + ": " +
                        (fastError.getMessage() == null ? "unknown fast-path error" : fastError.getMessage());
                runOnUiThread(() -> startMedia3AudioExtraction(
                        mediaUri, startMs, endMs, requestId, fastMessage));
            }
        }, "LocalAudioFastPath").start();
    }

    private void startMedia3AudioExtraction(Uri mediaUri, long startMs, long endMs,
                                            String requestId, String fastPathError) {
        if (!requestStillActive(requestId)) return;

        File outputFile = new File(getCacheDir(), "media3-" + requestId + ".m4a");
        if (outputFile.exists()) {
            try { outputFile.delete(); } catch (Exception ignored) {}
        }

        MediaItem.ClippingConfiguration.Builder clippingBuilder =
                new MediaItem.ClippingConfiguration.Builder().setStartPositionMs(startMs);
        if (endMs > 0) clippingBuilder.setEndPositionMs(endMs);

        MediaItem mediaItem = new MediaItem.Builder()
                .setUri(mediaUri)
                .setClippingConfiguration(clippingBuilder.build())
                .build();
        EditedMediaItem editedMediaItem = new EditedMediaItem.Builder(mediaItem)
                .setRemoveVideo(true)
                .build();

        Transformer.Listener listener = new Transformer.Listener() {
            @Override
            public void onCompleted(Composition composition, ExportResult result) {
                activeTransformer = null;
                publishNativeAudio(requestId, outputFile, startMs, endMs,
                        "android-media3-aac", "transcoded-to-aac");
            }

            @Override
            public void onError(Composition composition, ExportResult result, ExportException exception) {
                activeTransformer = null;
                try { outputFile.delete(); } catch (Exception ignored) {}
                if (!requestStillActive(requestId)) return;
                activeTransformRequestId = null;

                String media3Message = exception.getClass().getSimpleName();
                if (exception.getMessage() != null && !exception.getMessage().isEmpty()) {
                    media3Message += ": " + exception.getMessage();
                }
                Throwable cause = exception.getCause();
                if (cause != null) {
                    media3Message += " | cause=" + cause.getClass().getSimpleName();
                    if (cause.getMessage() != null) media3Message += ": " + cause.getMessage();
                }
                notifyNativeAudioError(requestId,
                        "استخراج محلی صدا شکست خورد. Media3=" + media3Message +
                                " | fastPath=" + fastPathError +
                                " | source=" + selectedMediaMime +
                                " | Android=" + Build.VERSION.SDK_INT +
                                " | app=" + BuildConfig.VERSION_NAME);
            }
        };

        try {
            activeTransformer = new Transformer.Builder(this)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .addListener(listener)
                    .build();
            activeTransformer.start(editedMediaItem, outputFile.getAbsolutePath());
        } catch (Exception ex) {
            activeTransformer = null;
            try { outputFile.delete(); } catch (Exception ignored) {}
            if (!requestStillActive(requestId)) return;
            activeTransformRequestId = null;
            notifyNativeAudioError(requestId,
                    "Media3 شروع نشد: " + ex.getClass().getSimpleName() + ": " + ex.getMessage() +
                            " | fastPath=" + fastPathError +
                            " | source=" + selectedMediaMime +
                            " | Android=" + Build.VERSION.SDK_INT +
                            " | app=" + BuildConfig.VERSION_NAME);
        }
    }

    private void notifyNativeAudioReady(String requestId, JSONObject meta) {
        if (webView == null) return;
        final String js = "window.__englishTutorNativeMediaReady && window.__englishTutorNativeMediaReady(" +
                JSONObject.quote(requestId) + "," + meta.toString() + ");";
        webView.post(() -> webView.evaluateJavascript(js, null));
    }

    private void notifyNativeAudioError(String requestId, String message) {
        if (webView == null) return;
        String safeMessage = (message == null || message.isEmpty()) ? "خطا در استخراج صدا روی گوشی" : message;
        final String js = "window.__englishTutorNativeMediaError && window.__englishTutorNativeMediaError(" +
                JSONObject.quote(requestId == null ? "" : requestId) + "," + JSONObject.quote(safeMessage) + ");";
        webView.post(() -> webView.evaluateJavascript(js, null));
    }

    private final class AndroidMediaBridge {
        @JavascriptInterface
        public boolean isAvailable() {
            return true;
        }

        @JavascriptInterface
        public boolean hasSelectedMedia() {
            return selectedMediaUri != null;
        }

        @JavascriptInterface
        public String selectedMediaInfo() {
            try {
                JSONObject info = new JSONObject();
                info.put("name", selectedMediaName);
                info.put("mimeType", selectedMediaMime);
                info.put("ready", selectedMediaUri != null);
                info.put("androidSdk", Build.VERSION.SDK_INT);
                info.put("appVersion", BuildConfig.VERSION_NAME);
                return info.toString();
            } catch (Exception ignored) {
                return "{\"ready\":false}";
            }
        }

        @JavascriptInterface
        public void extractAudio(double startSec, double endSec, String requestId) {
            runOnUiThread(() -> startNativeAudioExtraction(startSec, endSec, requestId));
        }

        @JavascriptInterface
        public String readAudioChunk(String requestIdRaw, long offset, int maxBytes) {
            String requestId = safeRequestId(requestIdRaw);
            File file = nativeAudioOutputs.get(requestId);
            if (file == null || !file.exists() || offset < 0 || offset >= file.length()) return "";
            int wanted = Math.max(1, Math.min(maxBytes, 256 * 1024));
            int size = (int) Math.min((long) wanted, file.length() - offset);
            byte[] bytes = new byte[size];
            try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                raf.seek(offset);
                int read = raf.read(bytes);
                if (read <= 0) return "";
                if (read != bytes.length) {
                    byte[] shortBytes = new byte[read];
                    System.arraycopy(bytes, 0, shortBytes, 0, read);
                    bytes = shortBytes;
                }
                return Base64.encodeToString(bytes, Base64.NO_WRAP);
            } catch (Exception ex) {
                return "";
            }
        }

        @JavascriptInterface
        public void releaseAudio(String requestIdRaw) {
            String requestId = safeRequestId(requestIdRaw);
            File file = nativeAudioOutputs.remove(requestId);
            if (file != null) {
                try { file.delete(); } catch (Exception ignored) {}
            }
        }
    }

    private void registerDownloadReceiver() {
        downloadReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L);
                long pending = prefs.getLong(PREF_PENDING_DOWNLOAD, -1L);
                if (id != pending) return;
                if (isDownloadSuccessful(id)) {
                    promptInstall(id);
                } else {
                    prefs.edit().remove(PREF_PENDING_DOWNLOAD).remove(PREF_PENDING_VERSION).apply();
                    Toast.makeText(MainActivity.this, "دانلود آپدیت کامل نشد", Toast.LENGTH_LONG).show();
                }
            }
        };
        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(downloadReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(downloadReceiver, filter);
    }

    private boolean isDownloadSuccessful(long id) {
        Cursor cursor = null;
        try {
            DownloadManager.Query query = new DownloadManager.Query().setFilterById(id);
            cursor = downloadManager.query(query);
            if (cursor != null && cursor.moveToFirst()) {
                int statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
                return statusIndex >= 0 && cursor.getInt(statusIndex) == DownloadManager.STATUS_SUCCESSFUL;
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) cursor.close();
        }
        return false;
    }

    private void checkForUpdate(boolean force) {
        long now = System.currentTimeMillis();
        long last = prefs.getLong(PREF_LAST_UPDATE_CHECK, 0L);
        if (!force && now - last < 5L * 60L * 1000L) return;
        prefs.edit().putLong(PREF_LAST_UPDATE_CHECK, now).apply();

        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(UPDATE_URL).openConnection();
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(10000);
                connection.setUseCaches(false);
                connection.setRequestProperty("Accept", "application/json");
                if (connection.getResponseCode() != 200) return;

                BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                StringBuilder body = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) body.append(line);
                reader.close();

                JSONObject json = new JSONObject(body.toString());
                int remoteVersion = json.optInt("versionCode", 0);
                String apkUrl = json.optString("apkUrl", "");
                String versionName = json.optString("versionName", String.valueOf(remoteVersion));
                if (remoteVersion <= BuildConfig.VERSION_CODE || apkUrl.isEmpty()) return;

                long pendingId = prefs.getLong(PREF_PENDING_DOWNLOAD, -1L);
                int pendingVersion = prefs.getInt(PREF_PENDING_VERSION, 0);
                if (pendingId > 0 && pendingVersion == remoteVersion && isDownloadSuccessful(pendingId)) {
                    runOnUiThread(() -> promptInstall(pendingId));
                    return;
                }
                runOnUiThread(() -> startUpdateDownload(apkUrl, remoteVersion, versionName));
            } catch (Exception ignored) {
            } finally {
                if (connection != null) connection.disconnect();
            }
        }, "UpdateCheck").start();
    }

    private void startUpdateDownload(String apkUrl, int versionCode, String versionName) {
        long oldId = prefs.getLong(PREF_PENDING_DOWNLOAD, -1L);
        if (oldId > 0) {
            try { downloadManager.remove(oldId); } catch (Exception ignored) {}
        }

        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(apkUrl));
        request.setTitle("English AI Tutor " + versionName);
        request.setDescription("در حال دریافت نسخه جدید");
        request.setMimeType("application/vnd.android.package-archive");
        request.setAllowedOverMetered(true);
        request.setAllowedOverRoaming(false);
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        request.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, "EnglishAITutor-update.apk");

        long id = downloadManager.enqueue(request);
        prefs.edit().putLong(PREF_PENDING_DOWNLOAD, id).putInt(PREF_PENDING_VERSION, versionCode).apply();
        Toast.makeText(this, "نسخه جدید پیدا شد؛ دانلود خودکار شروع شد", Toast.LENGTH_LONG).show();
    }

    private void promptInstall(long downloadId) {
        if (!isDownloadSuccessful(downloadId)) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(this, "برای آپدیت، یک‌بار مجوز نصب از این برنامه را فعال کن", Toast.LENGTH_LONG).show();
            Intent settingsIntent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName()));
            startActivity(settingsIntent);
            return;
        }
        launchInstaller(downloadId);
    }

    private void launchInstaller(long downloadId) {
        Uri uri = downloadManager.getUriForDownloadedFile(downloadId);
        if (uri == null) {
            Toast.makeText(this, "فایل آپدیت پیدا نشد", Toast.LENGTH_LONG).show();
            return;
        }
        Intent installIntent = new Intent(Intent.ACTION_VIEW);
        installIntent.setDataAndType(uri, "application/vnd.android.package-archive");
        installIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(installIntent);
        } catch (Exception ex) {
            Toast.makeText(this, "صفحه نصب آپدیت باز نشد", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        long pending = prefs.getLong(PREF_PENDING_DOWNLOAD, -1L);
        if (pending > 0 && isDownloadSuccessful(pending)) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || getPackageManager().canRequestPackageInstalls()) {
                launchInstaller(pending);
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != FILE_CHOOSER_REQUEST || filePathCallback == null) return;
        Uri[] results = null;
        if (resultCode == RESULT_OK && data != null) {
            if (data.getClipData() != null) {
                int count = data.getClipData().getItemCount();
                results = new Uri[count];
                for (int i = 0; i < count; i++) results[i] = data.getClipData().getItemAt(i).getUri();
            } else if (data.getData() != null) {
                results = new Uri[]{data.getData()};
            }
        }
        if (results != null && results.length > 0) rememberSelectedMedia(results[0], data);
        filePathCallback.onReceiveValue(results);
        filePathCallback = null;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == AUDIO_PERMISSION_REQUEST && pendingWebPermissionRequest != null) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                pendingWebPermissionRequest.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
            } else {
                pendingWebPermissionRequest.deny();
            }
            pendingWebPermissionRequest = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        if (webView != null) webView.saveState(outState);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onDestroy() {
        activeTransformRequestId = null;
        if (activeTransformer != null) {
            try { activeTransformer.cancel(); } catch (Exception ignored) {}
            activeTransformer = null;
        }
        for (File file : nativeAudioOutputs.values()) {
            try { file.delete(); } catch (Exception ignored) {}
        }
        nativeAudioOutputs.clear();

        if (downloadReceiver != null) {
            try { unregisterReceiver(downloadReceiver); } catch (Exception ignored) {}
        }
        if (webView != null) {
            webView.removeJavascriptInterface("AndroidMedia");
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
