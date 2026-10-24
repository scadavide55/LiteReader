package com.litereader;

final class BuildFlags {
    /** true only in the private test build; the delivered APK has false (build.sh patches a copy). */
    static final boolean SIM = false;
}
