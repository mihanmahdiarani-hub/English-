package com.mihan.englishaitutor.v2;

/** Automated tests for auto-download + explicit Android installation consent. */
public final class UpdatePolicyTest {
    private static int tests;

    private static void expect(boolean ok, String reason) {
        tests++;
        if (!ok) throw new AssertionError(reason);
    }

    public static void main(String[] args) {
        String sha = "b3f9872d3b82589bbe70206b689fdc6d4a1492b065a44ea897abb6780490467f";
        String url = "https://github.com/mihanmahdiarani-hub/English-"
                + "/releases/download/android-v2-latest/EnglishAITutor-v2-35.apk";

        expect(UpdatePolicy.validSha256(sha), "accept valid SHA-256");
        expect(!UpdatePolicy.validSha256("abc"), "reject missing SHA-256");
        expect(UpdatePolicy.validApkSource(35, url, sha),
                "accept only signed manifest data for immutable versioned APK");
        expect(UpdatePolicy.shouldDownload(34, 35, url, sha),
                "automatically download when a newer APK appears");
        expect(!UpdatePolicy.shouldDownload(35, 35, url, sha),
                "never redownload the currently installed version");
        expect(!UpdatePolicy.shouldDownload(36, 35, url, sha),
                "never downgrade already installed APK");
        expect(!UpdatePolicy.validApkSource(34, url, sha),
                "reject mismatched filename/version manifest");
        expect(!UpdatePolicy.validApkSource(35, url.replace("https", "http"), sha),
                "never download an insecure HTTP installer");
        expect(!UpdatePolicy.validApkSource(35, url.replace("github.com",
                "github.com.evil.example"), sha), "reject untrusted APK host");
        expect(!UpdatePolicy.validApkSource(35, url + "?redirect=other", sha),
                "reject manifest APK query injection");
        expect(!UpdatePolicy.validApkSource(35, url,
                sha.substring(1)), "reject damaged manifest digest");
        expect(!UpdatePolicy.validApkSource(35,
                "https://github.com/other/repository/releases/download/"
                        + "android-v2-latest/EnglishAITutor-v2-35.apk", sha),
                "never install another application's update");

        expect(UpdatePolicy.trustedRedirect(url),
                "trusted GitHub release");
        expect(UpdatePolicy.trustedRedirect("https://release-assets.githubusercontent.com/"
                        + "github-production-release-asset/123/abc?X-Amz=42"),
                "trusted official GitHub CDN redirect");
        expect(!UpdatePolicy.trustedRedirect("http://release-assets.githubusercontent.com/"
                        + "my-apk.apk"), "reject insecure redirect");
        expect(!UpdatePolicy.trustedRedirect("https://downloads.example.com/evil.apk"),
                "reject untrusted CDN host");
        expect(!UpdatePolicy.trustedRedirect("https://attacker@example.com/a.apk"),
                "reject URLs with userinfo");

        expect(!UpdatePolicy.mayOfferInstallation(34, 35, false, true),
                "never prompt installation of unverified APK");
        expect(!UpdatePolicy.mayOfferInstallation(34, 35, true, false),
                "never launch Android installer while app is backgrounded");
        expect(UpdatePolicy.mayOfferInstallation(34, 35, true, true),
                "only verified latest APK in foreground can request Android install consent");
        expect(!UpdatePolicy.mayOfferInstallation(35, 35, true, true),
                "already installed APK cannot prompt again");
        System.out.println("PASS " + tests + " verified updater safety/consent checks");
    }
}
