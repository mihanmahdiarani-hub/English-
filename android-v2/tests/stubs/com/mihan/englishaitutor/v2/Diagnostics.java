package com.mihan.englishaitutor.v2;

final class Diagnostics {
    static void log(String area, String message) {}
    static void error(String area, Throwable error) {
        throw new AssertionError(area, error);
    }
}
