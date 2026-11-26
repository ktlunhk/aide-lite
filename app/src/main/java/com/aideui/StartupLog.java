package com.aideui;

import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Diagnostic trace stored in the application's own tmp directory; never uses /tmp. */
public final class StartupLog {
    private static final String TAG = "AideUI-Startup";
    private static Context app;
    private static final Object LOCK = new Object();
    private StartupLog() { }

    public static void init(Context context) {
        synchronized (LOCK) {
            app = context.getApplicationContext();
            write("----- Launch ----- Android " + Build.VERSION.SDK_INT
                + " (" + Build.VERSION.RELEASE + ") pid=" + android.os.Process.myPid());
            write("Internal: " + new File(app.getFilesDir(), "tmp/aideui-startup.log").getAbsolutePath());
            File external = app.getExternalFilesDir("tmp");
            write("External: " + (external == null ? "unavailable" : new File(external, "aideui-startup.log").getAbsolutePath()));
        }
    }

    public static void mark(String message) { write(message); }

    public static void caught(String where, Throwable error) {
        StringWriter sw = new StringWriter();
        error.printStackTrace(new PrintWriter(sw));
        write("EXCEPTION at " + where + "\n" + sw.toString());
    }

    public static void fatal(Thread thread, Throwable error) {
        caught("FATAL thread=" + thread.getName(), error);
    }

    private static void write(String message) {
        synchronized (LOCK) {
            String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
            String line = stamp + "  " + message + "\n";
            Log.i(TAG, line);
            if (app == null) return;
            append(new File(app.getFilesDir(), "tmp/aideui-startup.log"), line);
            // App-specific external storage doesn't need broad-storage permission on Android 4.4+.
            try {
                File external = app.getExternalFilesDir("tmp");
                if (external != null) append(new File(external, "aideui-startup.log"), line);
            } catch (Throwable ignored) { }
        }
    }

    private static void append(File file, String text) {
        try {
            File dir = file.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) return;
            // Keep startup trace from growing unbounded across many runs.
            if (file.length() > 500000) {
                File old = new File(dir, "aideui-startup.previous.log");
                if (old.exists()) old.delete();
                file.renameTo(old);
            }
            FileOutputStream out = new FileOutputStream(file, true);
            try { out.write(text.getBytes("UTF-8")); }
            finally { out.close(); }
        } catch (Throwable ignored) { }
    }
}
