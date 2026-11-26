package com.aideui;

import android.app.Application;

/** Install diagnostics before MainActivity's view inflation and initialization. */
public class AideUIApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        // The Kotlin compiler runs in its own process; it must not touch the IDE's logs and crash files.
        if (isCompilerProcess()) return;
        StartupLog.init(this);
        StartupLog.mark("Application.onCreate");
        CrashReporter.install(this);
    }

    private boolean isCompilerProcess() {
        try {
            java.io.FileInputStream in = new java.io.FileInputStream("/proc/self/cmdline");
            try {
                byte[] b = new byte[256];
                int n = in.read(b);
                String name = n > 0 ? new String(b, 0, n, "UTF-8") : "";
                int nul = name.indexOf(0);
                if (nul >= 0) name = name.substring(0, nul);
                return name.endsWith(":kotlinc");
            } finally { in.close(); }
        } catch (java.io.IOException e) { return false; }
    }
}
