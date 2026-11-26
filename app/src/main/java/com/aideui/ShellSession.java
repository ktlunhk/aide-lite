package com.aideui;

import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.util.Map;

/**
 * A persistent /system/bin/sh process behind the Terminal panel. It runs as AideUI itself, so only
 * Android's own tools are available (not Termux packages), and there is no pseudo-terminal: no
 * full-screen programs, no colours, no line editing. After every command a marker line reports the
 * shell's working directory; it is removed from the output.
 */
final class ShellSession {
    interface Listener {
        void onOutput(String text);
        void onDirectory(String directory);
        void onExit(int code);
    }

    private static final String MARK = "__AIDEUI_CWD__";
    private static final long FLUSH_DELAY_MS = 40;

    private final File startDirectory, home;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final StringBuilder pendingOutput = new StringBuilder();
    private boolean flushScheduled;
    private Process process;
    private OutputStream stdin;
    private volatile boolean alive;

    ShellSession(File startDirectory, File home, Listener listener) {
        this.startDirectory = startDirectory;
        this.home = home;
        this.listener = listener;
    }

    boolean isAlive() { return alive; }

    synchronized void start() throws IOException {
        ProcessBuilder builder = new ProcessBuilder("/system/bin/sh");
        if (startDirectory != null && startDirectory.isDirectory()) builder.directory(startDirectory);
        builder.redirectErrorStream(true);
        Map<String, String> env = builder.environment();
        env.put("HOME", home.getAbsolutePath());
        env.put("TERM", "dumb");
        process = builder.start();
        stdin = process.getOutputStream();
        alive = true;
        final Process started = process;
        Thread reader = new Thread(new Runnable() {
            public void run() { pump(started); }
        }, "aideui-shell");
        reader.setDaemon(true);
        reader.start();
        send("");   // report the starting directory
    }

    /** Runs one command line in the persistent shell. */
    synchronized void send(String command) throws IOException {
        if (!alive || stdin == null) throw new IOException("Shell is not running");
        String line = command + "\n" + "printf '%s%s\\n' '" + MARK + "' \"$PWD\"\n";
        stdin.write(line.getBytes("UTF-8"));
        stdin.flush();
    }

    /** Kills the shell and anything it started; used for "Stop" and when the activity closes. */
    synchronized void destroy() {
        alive = false;
        if (process != null) process.destroy();
        process = null;
        stdin = null;
    }

    private void pump(Process source) {
        StringBuilder tail = new StringBuilder();
        try {
            Reader in = new InputStreamReader(source.getInputStream(), "UTF-8");
            char[] chunk = new char[4096];
            int n;
            while ((n = in.read(chunk)) != -1) {
                tail.append(chunk, 0, n);
                handle(tail);
            }
        } catch (IOException ignored) {
        }
        int code = -1;
        try { code = source.waitFor(); } catch (InterruptedException ignored) { }
        final int exitCode = code;
        alive = false;
        main.post(new Runnable() { public void run() { listener.onExit(exitCode); } });
    }

    /** Consumes complete lines from the buffer; holds back a possible partial marker. */
    private void handle(StringBuilder tail) {
        int newline;
        while ((newline = tail.indexOf("\n")) >= 0) {
            String line = tail.substring(0, newline);
            tail.delete(0, newline + 1);
            int at = line.indexOf(MARK);
            if (at < 0) { emit(clean(line) + "\n"); continue; }
            String before = clean(line.substring(0, at));
            if (before.length() > 0) emit(before + "\n");
            publishDirectory(line.substring(at + MARK.length()).trim());
        }
        if (tail.length() == 0) return;
        String rest = tail.toString();
        int at = rest.indexOf(MARK);
        String show;
        if (at >= 0) {
            show = rest.substring(0, at);
            tail.delete(0, at);
        } else {
            int keep = 0;
            for (int k = Math.min(rest.length(), MARK.length() - 1); k >= 1; k--) {
                if (rest.endsWith(MARK.substring(0, k))) { keep = k; break; }
            }
            show = rest.substring(0, rest.length() - keep);
            tail.delete(0, rest.length() - keep);
        }
        if (show.length() > 0) emit(clean(show));
    }

    private static String clean(String text) {
        // Terminal colour/cursor escape sequences and carriage returns mean nothing without a terminal.
        return text.replaceAll("\u001B\\[[0-9;?]*[A-Za-z]", "").replace("\r", "");
    }

    private void emit(String text) {
        if (text.length() == 0) return;
        synchronized (pendingOutput) {
            pendingOutput.append(text);
            if (flushScheduled) return;
            flushScheduled = true;
        }
        main.postDelayed(new Runnable() {
            public void run() {
                String out;
                synchronized (pendingOutput) {
                    out = pendingOutput.toString();
                    pendingOutput.setLength(0);
                    flushScheduled = false;
                }
                if (out.length() > 0) listener.onOutput(out);
            }
        }, FLUSH_DELAY_MS);
    }

    private void publishDirectory(final String directory) {
        if (directory.length() == 0) return;
        main.post(new Runnable() { public void run() { listener.onDirectory(directory); } });
    }
}
