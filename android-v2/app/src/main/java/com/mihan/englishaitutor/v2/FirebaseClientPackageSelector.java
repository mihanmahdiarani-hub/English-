package com.mihan.englishaitutor.v2;

import java.util.List;

/**
 * Select a real client entry from a downloaded google-services.json.
 *
 * Prefer the exact installed Android package. For the isolated V51 preview
 * only, a legacy production Firebase client may be used as a temporary
 * configuration compatibility fallback. This does NOT create/register a
 * preview Firebase Android app or bypass App Check/API key restrictions.
 */
final class FirebaseClientPackageSelector {
    static final String PRODUCTION = "com.mihan.englishaitutor.v2";
    static final String PREVIEW = PRODUCTION + ".preview";

    private FirebaseClientPackageSelector() {}

    static String select(String installed, List<String> availablePackages) {
        if (installed == null || availablePackages == null) return null;
        for (String pkg : availablePackages) {
            if (installed.equals(pkg)) return installed;
        }
        if (PREVIEW.equals(installed)) {
            for (String pkg : availablePackages) {
                if (PRODUCTION.equals(pkg)) return PRODUCTION;
            }
        }
        return null;
    }

    static boolean isCompatibilityFallback(String installed, String selected) {
        return PREVIEW.equals(installed) && PRODUCTION.equals(selected);
    }
}
