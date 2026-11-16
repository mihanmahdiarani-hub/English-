package com.mihan.englishaitutor.v2;

import java.util.Arrays;
import java.util.Collections;

/** Regression test: V51 Preview Firebase matching never changes production identity. */
public final class FirebaseClientPackageSelectorTest {
    public static void main(String[] args) {
        String prod = FirebaseClientPackageSelector.PRODUCTION;
        String preview = FirebaseClientPackageSelector.PREVIEW;

        // Use the exact preview Firebase app when the owner registers it.
        assertEquals(preview, FirebaseClientPackageSelector.select(
                preview, Arrays.asList(prod, preview)), "exact preview first");
        assertEquals(preview, FirebaseClientPackageSelector.select(
                preview, Arrays.asList(preview, prod)), "exact preview priority");

        // Bundled V50 config can supply configuration to V51 Preview only.
        assertEquals(prod, FirebaseClientPackageSelector.select(
                preview, Collections.singletonList(prod)), "preview compatibility");
        assertTrue(FirebaseClientPackageSelector.isCompatibilityFallback(
                preview, prod), "compatibility flag");
        assertFalse(FirebaseClientPackageSelector.isCompatibilityFallback(
                preview, preview), "exact-match flag");

        // Production cannot accidentally use preview's Firebase app.
        assertEquals(prod, FirebaseClientPackageSelector.select(
                prod, Arrays.asList(preview, prod)), "production exact");
        assertNull(FirebaseClientPackageSelector.select(
                prod, Collections.singletonList(preview)), "no reverse fallback");

        // Never use an unrelated package or arbitrary suffix as a bypass.
        assertNull(FirebaseClientPackageSelector.select(
                "com.other.app", Collections.singletonList(prod)), "no unrelated app");
        assertNull(FirebaseClientPackageSelector.select(
                prod + ".preview.evil", Collections.singletonList(prod)), "no arbitrary suffix");
        assertNull(FirebaseClientPackageSelector.select(
                preview, Collections.singletonList("com.other.app")), "no unrelated fallback");
        assertNull(FirebaseClientPackageSelector.select(
                preview, Collections.<String>emptyList()), "empty list");
        assertNull(FirebaseClientPackageSelector.select(
                null, Collections.singletonList(prod)), "null installed");
        assertNull(FirebaseClientPackageSelector.select(
                preview, null), "null options");

        System.out.println("PASS: exact preview/production and isolated fallback");
    }

    private static void assertEquals(String expected, String actual, String what) {
        if (!expected.equals(actual))
            throw new AssertionError(what + ": expected " + expected + ", actual " + actual);
    }
    private static void assertNull(String actual, String what) {
        if (actual != null) throw new AssertionError(what + ": expected null, got " + actual);
    }
    private static void assertTrue(boolean value, String what) {
        if (!value) throw new AssertionError(what);
    }
    private static void assertFalse(boolean value, String what) {
        if (value) throw new AssertionError(what);
    }
}
