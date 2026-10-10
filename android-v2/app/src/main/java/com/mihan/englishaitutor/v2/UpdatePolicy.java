package com.mihan.englishaitutor.v2;

import java.net.URI;

/**
 * Safety-critical, pure Java rules shared by the foreground updater and
 * periodic WorkManager checks. Executable CI tests cover these invariants.
 */
final class UpdatePolicy {
    private UpdatePolicy() {}

    static boolean validSha256(String sha) {
        return sha != null && sha.matches("(?i)[a-f0-9]{64}");
    }

    static boolean validApkSource(int version, String url, String sha256) {
        if (version <= 0 || !validSha256(sha256) || url == null) return false;
        try {
            URI uri = new URI(url);
            return "https".equalsIgnoreCase(uri.getScheme())
                    && "github.com".equalsIgnoreCase(uri.getHost())
                    && uri.getUserInfo() == null
                    && (uri.getPort() == -1 || uri.getPort() == 443)
                    && uri.getQuery() == null
                    && uri.getFragment() == null
                    && ("/mihanmahdiarani-hub/English-/releases/download/"
                    + "android-v2-latest/EnglishAITutor-v2-" + version + ".apk")
                    .equals(uri.getPath());
        } catch (Exception invalid) {
            return false;
        }
    }

    static boolean shouldDownload(int installedVersion, int latestVersion,
                                  String url, String sha256) {
        return latestVersion > installedVersion
                && validApkSource(latestVersion, url, sha256);
    }

    static boolean mayOfferInstallation(int installedVersion, int readyVersion,
                                        boolean verifiedFile, boolean foreground) {
        return foreground && verifiedFile && readyVersion > installedVersion;
    }

    static boolean trustedRedirect(String url) {
        if (url == null) return false;
        try {
            URI uri = new URI(url);
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getUserInfo() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443)) return false;
            String host = uri.getHost();
            return host != null && ("github.com".equalsIgnoreCase(host)
                    || host.equalsIgnoreCase("release-assets.githubusercontent.com")
                    || host.equalsIgnoreCase("objects.githubusercontent.com"));
        } catch (Exception invalid) {
            return false;
        }
    }
}
