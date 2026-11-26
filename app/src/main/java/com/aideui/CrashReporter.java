package com.aideui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;

/** Local-only Java crash report: no network permission or external storage needed. */
public final class CrashReporter {
    private CrashReporter() { }
    private static boolean installed;
    private static final String NAME = "previous-java-crash.txt";

    public static synchronized void install(final Context context) {
        if (installed) return;
        installed = true;
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            public void uncaughtException(Thread thread, Throwable error) {
                StartupLog.fatal(thread, error);
                try {
                    StringWriter stack = new StringWriter();
                    PrintWriter p = new PrintWriter(stack);
                    p.println("AideUI uncaught Java exception on thread: " + thread.getName());
                    p.println("Time: " + new java.util.Date());
                    error.printStackTrace(p);
                    p.flush();
                    byte[] data = stack.toString().getBytes("UTF-8");
                    File file = new File(context.getFilesDir(), NAME);
                    FileOutputStream out = new FileOutputStream(file);
                    try { out.write(data, 0, Math.min(data.length, 60000)); }
                    finally { out.close(); }
                } catch (Throwable ignored) { }
                // Preserve Android's normal crash handling; never silently swallow a fatal error.
                if (previous != null) previous.uncaughtException(thread, error);
            }
        });
    }

    public static void showPreviousCrash(final Activity activity) {
        final File file = new File(activity.getFilesDir(), NAME);
        if (!file.isFile()) return;
        final String report;
        try {
            FileInputStream in = new FileInputStream(file);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try {
                byte[] buf = new byte[4096];
                int count, len = 0;
                while ((count = in.read(buf)) > 0 && len < 60000) {
                    out.write(buf, 0, Math.min(count, 60000 - len));
                    len += count;
                }
            } finally { in.close(); }
            report = out.toString("UTF-8");
        } catch (Exception ex) { return; }
        // Mark it as acknowledged so subsequent launches are not interrupted.
        file.delete();
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            public void run() {
                if (activity.isFinishing()) return;
                try {
                    new AlertDialog.Builder(activity, android.R.style.Theme_Material_Light_Dialog_Alert)
                        .setTitle("AideUI: previous crash detected")
                        .setMessage(report.length() > 3000 ? report.substring(0, 3000) + "\n…" : report)
                        .setPositiveButton("Copy report", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int which) {
                                ClipboardManager clip = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                                if (clip != null) clip.setPrimaryClip(ClipData.newPlainText("AideUI crash", report));
                                Toast.makeText(activity, "Crash report copied", Toast.LENGTH_SHORT).show();
                            }
                        })
                        .setNeutralButton("Disable auto sync", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int which) {
                                activity.getPreferences(Context.MODE_PRIVATE).edit()
                                    .putBoolean("auto_gradle_dependencies", false).apply();
                                Toast.makeText(activity, "Automatic Gradle sync disabled", Toast.LENGTH_LONG).show();
                            }
                        })
                        .setNegativeButton("Close", null).show();
                } catch (Exception ignored) { }
            }
        }, 550);
    }
}
