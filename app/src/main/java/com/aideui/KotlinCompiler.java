package com.aideui;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Kotlin build pipeline, in explicit steps:
 *   1. preflight  - compiler present and loadable, matching stdlib chosen
 *   2. request    - arguments written to a work directory (nothing is passed by memory)
 *   3. run        - compiler runs in the isolated :kotlinc process (in-process only as a fallback)
 *   4. report     - compiler output is parsed into editor problems; failures name the stage that failed
 */
final class KotlinCompiler {
    private KotlinCompiler() { }

    static final long TIMEOUT_MS = 20L * 60L * 1000L;
    private static final Pattern DIAGNOSTIC = Pattern.compile(
        "^(.+\\.kts?):(\\d+):(\\d+):\\s*(error|warning|strong_warning|exception):\\s*(.*)$");

    static final class Request {
        final List<File> sources;
        final File javaRoot, output, platform, stdlib;
        final List<String> dependencies;
        /** Additional Java source roots, such as generated R.java folders, visible to Kotlin. */
        final List<File> extraJavaRoots = new ArrayList<File>();
        /** Compiler plugin bundles (DEX jars) passed as -Xplugin, e.g. the Compose compiler plugin. */
        final List<File> plugins = new ArrayList<File>();
        Request(List<File> sources, File javaRoot, File output, File platform, File stdlib,
                List<String> dependencies) {
            this.sources = sources; this.javaRoot = javaRoot; this.output = output;
            this.platform = platform; this.stdlib = stdlib; this.dependencies = dependencies;
        }
    }

    /** Failure that still carries the parsed problems so the editor can show them. */
    static final class CompileException extends IOException {
        final List<ProjectBuild.Problem> problems;
        CompileException(String message, List<ProjectBuild.Problem> problems) {
            super(message);
            this.problems = problems;
        }
    }

    /** Returns warnings on success; throws CompileException on any failure. */
    static List<ProjectBuild.Problem> compile(Context context, Request request, ProjectBuild.Progress progress)
            throws Exception {
        if (progress.isCancelled()) throw new InterruptedException("Kotlin build cancelled");
        if (request.sources == null || request.sources.isEmpty()) return new ArrayList<ProjectBuild.Problem>();
        if (!request.platform.isFile() || !request.stdlib.isFile())
            throw new IOException("Kotlin build requires android.jar and a Kotlin stdlib");

        // 1. preflight
        KotlinOnDeviceCompiler.ensureCompilerAvailable(context);
        File compiler = KotlinToolchain.compiler(context);
        if (!compiler.isFile()) compiler = KotlinOnDeviceCompiler.extractAssetCompiler(context);
        if (compiler.canWrite())
            throw new IOException("Installed Kotlin compiler DEX must be read-only before loading on Android 14+");

        // 2-3. request + run (one compiler run at a time: builds and editor checks share the service)
        File work = new File(context.getCacheDir(), "kotlinc-work");
        File logFile = new File(work, "log.txt");
        long started = System.currentTimeMillis();
        KotlinCompileRunner.Outcome outcome = runLocked(context, compiler, work, buildArguments(context, request), progress);

        // 4. report
        String log = readText(logFile);
        List<ProjectBuild.Problem> problems = parseProblems(log, request.sources);
        List<ProjectBuild.Problem> warnings = new ArrayList<ProjectBuild.Problem>();
        int errors = 0;
        for (ProjectBuild.Problem p : problems) { if (p.warning) warnings.add(p); else errors++; }
        long seconds = (System.currentTimeMillis() - started) / 1000L;
        if (outcome.code == KotlinCompileRunner.OK) { deleteTree(work); return warnings; }
        String message;
        if (outcome.code == KotlinCompileRunner.COMPILE_ERRORS) {
            message = "Kotlin compilation failed: " + errors + " error(s)." + firstErrors(problems, log);
        } else if (outcome.code == KotlinCompileRunner.PROCESS_DIED) {
            message = "The Kotlin compiler process died during '" + outcome.stage + "' after " + seconds
                + "s (usually out of memory)." + tail(log);
        } else {
            message = "Kotlin compiler failed during '" + outcome.stage + "'.\n" + outcome.detail + tail(log);
        }
        throw new CompileException(message, problems);
    }

    private static final Object RUN_LOCK = new Object();

    private static KotlinCompileRunner.Outcome runLocked(Context context, File compiler, File work,
            List<String> arguments, ProjectBuild.Progress progress) throws Exception {
        synchronized (RUN_LOCK) {
            deleteTree(work);
            if (!work.mkdirs()) throw new IOException("Cannot create " + work);
            File argsFile = new File(work, "args.txt");
            File logFile = new File(work, "log.txt");
            File stageFile = new File(work, "stage.txt");
            writeLines(argsFile, arguments);
            File props = KotlinToolchain.jvmProperties(context);
            KotlinCompileRunner.Outcome outcome = runInService(context, compiler, argsFile, logFile, stageFile,
                props.isFile() ? props : null, progress);
            if (outcome == null)
                outcome = runInProcess(context, compiler, argsFile, logFile, stageFile, props.isFile() ? props : null);
            return outcome;
        }
    }

    static final class Diagnostic {
        final String path, message;
        final int line, column;
        final boolean warning;
        Diagnostic(String path, int line, int column, boolean warning, String message) {
            this.path = path; this.line = line; this.column = column; this.warning = warning; this.message = message;
        }
    }

    /**
     * Compiles the module's Kotlin sources, with the editor buffer standing in for the file on disk, and
     * returns the diagnostics that belong to that file. Returns null when the check cannot run;
     * status[0] says why. Output classes are thrown away.
     */
    static List<Diagnostic> check(Context context, File module, File current, String snapshot, String variant,
                                  String[] status) throws Exception {
        File compiler = KotlinToolchain.compiler(context);
        if (!compiler.isFile() && KotlinOnDeviceCompiler.bundledAvailable(context)) {
            try { KotlinOnDeviceCompiler.installBundled(context); } catch (Throwable ignored) { }
            compiler = KotlinToolchain.compiler(context);
        }
        if (!compiler.isFile()) { status[0] = "Kotlin compiler not installed"; return null; }
        File platform = JavaCompiler.findAndroidJar(context, true);
        if (platform == null) { status[0] = "android.jar not found"; return null; }
        File stdlib = KotlinToolchain.stdlib(context);
        boolean classic = StandaloneApkBuild.isClassicProject(module);
        List<File> sources = new ArrayList<File>(KotlinOnDeviceCompiler.collectSources(module, classic, false));
        File base = new File(context.getCacheDir(), "kotlinc-check-src");
        deleteTree(base);
        File out = new File(base, "out");
        if (!out.mkdirs()) throw new IOException("Cannot create " + out);
        File temp = new File(base, current.getName());
        FileOutputStream w = new FileOutputStream(temp);
        try { w.write(snapshot.getBytes("UTF-8")); } finally { w.close(); }
        boolean replaced = false;
        for (int i = 0; i < sources.size(); i++)
            if (sources.get(i).equals(current)) { sources.set(i, temp); replaced = true; }
        if (!replaced) sources.add(temp);

        List<String> deps = new ArrayList<String>();
        for (File jar : MavenLibraries.classpath(module)) deps.add(jar.getAbsolutePath());
        File[] libs = new File(module, "libs").listFiles();
        if (libs != null) for (File lib : libs) if (lib.isFile() && lib.getName().endsWith(".jar")) deps.add(lib.getAbsolutePath());
        for (java.util.Iterator<String> it = deps.iterator(); it.hasNext(); )
            if (new File(it.next()).getName().toLowerCase(java.util.Locale.US).contains("kotlin-stdlib")) it.remove();

        Request request = new Request(sources, new File(module, "src/main/java"), out, platform, stdlib, deps);
        if (KotlinPlugins.usesCompose(module)) {
            List<File> plugins = KotlinPlugins.installed(context, module);
            if (plugins.isEmpty()) {
                // Without the plugin every @Composable would be reported as an error; wait for the first build.
                status[0] = "Compose compiler plugin not installed yet (it installs on the first build)";
                deleteTree(base);
                return null;
            }
            request.plugins.addAll(plugins);
        }
        String chosen = variant == null || !variant.matches("[A-Za-z0-9_]{1,70}") ? "debug" : variant;
        boolean generatedR = false;
        for (String g : new String[]{"build/generated/source/r/" + chosen, "build/generated/source/buildConfig/" + chosen,
                "build/generated/source/r/debug", "build/gen", "gen"}) {
            File dir = new File(module, g);
            if (dir.isDirectory()) { request.extraJavaRoots.add(dir); if (g.contains("r/") || g.endsWith("gen")) generatedR = true; }
        }
        File work = new File(context.getCacheDir(), "kotlinc-check");
        KotlinCompileRunner.Outcome outcome = runLocked(context, compiler, work, buildArguments(context, request),
            new ProjectBuild.Progress() {
                public void update(String text, int percent) { }
                public boolean isCancelled() { return false; }
            });
        String log = readText(new File(work, "log.txt"));
        if (outcome.code != KotlinCompileRunner.OK && outcome.code != KotlinCompileRunner.COMPILE_ERRORS) {
            status[0] = "Kotlin check failed during '" + outcome.stage + "'";
            deleteTree(base); deleteTree(work);
            return null;
        }
        List<Diagnostic> result = new ArrayList<Diagnostic>();
        for (String line : log.split("\\n")) {
            Matcher m = DIAGNOSTIC.matcher(line.trim());
            if (!m.matches()) continue;
            String path = m.group(1);
            if (!(path.equals(temp.getAbsolutePath()) || path.endsWith("/kotlinc-check-src/" + current.getName()))) continue;
            String message = m.group(5);
            // Before the first build there is no generated R/BuildConfig; those errors are not real.
            if (!generatedR && (message.contains("'R'") || message.contains("'BuildConfig'"))) continue;
            boolean warning = m.group(4).equals("warning") || m.group(4).equals("strong_warning");
            int number = 1, col = 1;
            try { number = Integer.parseInt(m.group(2)); col = Integer.parseInt(m.group(3)); } catch (NumberFormatException ignored) { }
            result.add(new Diagnostic(current.getAbsolutePath(), number, col, warning, message));
        }
        deleteTree(base);
        deleteTree(work);
        return result;
    }

    /** Compiles a tiny Kotlin file and reports every step; used by the self-test menu item. */
    static String selfTest(Context context, KotlinCompilerInstaller.Log log) throws Exception {
        StringBuilder report = new StringBuilder();
        report.append(KotlinToolchain.describe(context)).append('\n');
        File platform = JavaCompiler.findAndroidJar(context, true);
        if (platform == null) throw new IOException("Bundled android.jar unavailable");
        File stdlib = KotlinToolchain.stdlib(context);
        File dir = new File(context.getCacheDir(), "kotlinc-selftest");
        deleteTree(dir);
        File src = new File(dir, "src");
        File out = new File(dir, "out");
        if (!src.mkdirs() || !out.mkdirs()) throw new IOException("Cannot create " + dir);
        File probe = new File(src, "Probe.kt");
        writeLines(probe, java.util.Arrays.asList(
            "package selftest", "class Probe { fun answer(): Int = listOf(1, 2, 3).map { it * 2 }.sum() }"));
        List<File> sources = new ArrayList<File>();
        sources.add(probe);
        log.update("Compiling a test file in the Kotlin compiler process…");
        long t = System.currentTimeMillis();
        try {
            compile(context, new Request(sources, new File(dir, "none"), out, platform, stdlib,
                new ArrayList<String>()), new ProjectBuild.Progress() {
                public void update(String status, int percent) { }
                public boolean isCancelled() { return false; }
            });
        } catch (CompileException e) {
            throw new IOException(report + "\nSelf-test FAILED:\n" + e.getMessage());
        }
        if (!new File(out, "selftest/Probe.class").isFile())
            throw new IOException(report + "\nSelf-test FAILED: compiler reported OK but produced no Probe.class");
        report.append("Self-test: OK in ").append((System.currentTimeMillis() - t) / 1000.0).append(" s");
        deleteTree(dir);
        return report.toString();
    }

    static List<String> buildArguments(Context context, Request r) throws IOException {
        List<String> a = new ArrayList<String>();
        a.add("-no-stdlib"); a.add("-no-reflect"); a.add("-no-jdk");
        a.add("-Xskip-metadata-version-check"); a.add("-Xskip-prerelease-check");
        a.add("-Xsuppress-version-warnings");
        a.add("-Xno-call-assertions"); a.add("-Xno-param-assertions"); a.add("-Xno-receiver-assertions");
        // Libraries (AndroidX, Compose) are built for JVM 11; Kotlin cannot inline their bytecode into a lower target.
        // D8 desugars it for Android. A "-jvm-target" line in the extra-args file overrides this (last one wins).
        a.add("-jvm-target"); a.add("11");
        a.add("-d"); a.add(r.output.getAbsolutePath());
        a.add("-classpath");
        StringBuilder cp = new StringBuilder(r.platform.getAbsolutePath());
        cp.append(File.pathSeparator).append(r.stdlib.getAbsolutePath());
        cp.append(File.pathSeparator).append(r.output.getAbsolutePath());
        for (String item : r.dependencies) cp.append(File.pathSeparator).append(item);
        a.add(cp.toString());
        File extra = KotlinToolchain.extraArgs(context);
        if (extra.isFile()) for (String line : KotlinCompileRunner.readLines(extra)) {
            String trimmed = line.trim();
            if (trimmed.length() > 0 && !trimmed.startsWith("#")) a.add(trimmed);
        }
        for (File plugin : r.plugins) a.add("-Xplugin=" + plugin.getAbsolutePath());
        if (r.javaRoot != null && r.javaRoot.isDirectory()) a.add("-Xjava-source-roots=" + r.javaRoot.getAbsolutePath());
        for (File root : r.extraJavaRoots)
            if (root.isDirectory()) a.add("-Xjava-source-roots=" + root.getAbsolutePath());
        for (File s : r.sources) a.add(s.getAbsolutePath());
        return a;
    }

    static List<ProjectBuild.Problem> parseProblems(String log, List<File> sources) {
        List<ProjectBuild.Problem> list = new ArrayList<ProjectBuild.Problem>();
        for (String line : log.split("\\n")) {
            Matcher m = DIAGNOSTIC.matcher(line.trim());
            if (!m.matches()) continue;
            String kind = m.group(4);
            boolean warning = kind.equals("warning") || kind.equals("strong_warning");
            int number = 1;
            try { number = Integer.parseInt(m.group(2)); } catch (NumberFormatException ignored) { }
            list.add(new ProjectBuild.Problem(resolve(m.group(1), sources), number, m.group(5), warning));
        }
        return list;
    }

    private static String resolve(String reported, List<File> sources) {
        for (File s : sources) if (s.getAbsolutePath().equals(reported)) return reported;
        for (File s : sources) if (s.getAbsolutePath().endsWith("/" + reported)) return s.getAbsolutePath();
        return reported;
    }

    private static String firstErrors(List<ProjectBuild.Problem> problems, String log) {
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (ProjectBuild.Problem p : problems) {
            if (p.warning) continue;
            sb.append('\n').append(new File(p.path).getName()).append(':').append(p.line).append(": ").append(p.message);
            if (++shown == 5) break;
        }
        return shown == 0 ? tail(log) : sb.toString();
    }

    private static String tail(String log) {
        String t = log.trim();
        if (t.length() == 0) return "";
        return "\n--- compiler output ---\n" + (t.length() > 1500 ? t.substring(t.length() - 1500) : t);
    }

    /** Returns null when the service cannot be bound so the caller can fall back to in-process. */
    private static KotlinCompileRunner.Outcome runInService(Context context, File compiler, File args, File log,
            File stageFile, File props, ProjectBuild.Progress progress) throws Exception {
        final Context app = context.getApplicationContext();
        final CountDownLatch done = new CountDownLatch(1);
        final KotlinCompileRunner.Outcome[] box = new KotlinCompileRunner.Outcome[1];
        final Messenger[] service = new Messenger[1];
        final Messenger replyTo = new Messenger(new Handler(Looper.getMainLooper()) {
            @Override public void handleMessage(Message msg) {
                if (msg.what != KotlinCompileService.MSG_RESULT) return;
                Bundle b = msg.getData();
                KotlinCompileRunner.Outcome o = new KotlinCompileRunner.Outcome();
                o.code = b.getInt(KotlinCompileService.K_CODE, KotlinCompileRunner.INTERNAL_ERROR);
                o.stage = b.getString(KotlinCompileService.K_STAGE);
                o.detail = b.getString(KotlinCompileService.K_DETAIL);
                box[0] = o;
                done.countDown();
            }
        });
        final Bundle request = new Bundle();
        request.putString(KotlinCompileService.K_COMPILER, compiler.getAbsolutePath());
        request.putString(KotlinCompileService.K_ARGS, args.getAbsolutePath());
        request.putString(KotlinCompileService.K_LOG, log.getAbsolutePath());
        request.putString(KotlinCompileService.K_STAGE, stageFile.getAbsolutePath());
        request.putString(KotlinCompileService.K_CACHE, app.getCodeCacheDir().getAbsolutePath());
        if (props != null) request.putString(KotlinCompileService.K_PROPS, props.getAbsolutePath());
        final boolean[] sendFailed = new boolean[1];
        ServiceConnection connection = new ServiceConnection() {
            public void onServiceConnected(ComponentName name, IBinder binder) {
                service[0] = new Messenger(binder);
                Message m = Message.obtain(null, KotlinCompileService.MSG_COMPILE);
                m.setData(request);
                m.replyTo = replyTo;
                try { service[0].send(m); }
                catch (RemoteException e) { sendFailed[0] = true; done.countDown(); }
            }
            public void onServiceDisconnected(ComponentName name) {
                if (box[0] == null) done.countDown();   // process died before it replied
            }
        };
        boolean bound;
        try { bound = app.bindService(new Intent(app, KotlinCompileService.class), connection,
            Context.BIND_AUTO_CREATE); }
        catch (SecurityException e) { bound = false; }
        if (!bound) return null;
        long started = System.currentTimeMillis();
        try {
            while (!done.await(250, TimeUnit.MILLISECONDS)) {
                long elapsed = System.currentTimeMillis() - started;
                if (progress.isCancelled() || Thread.currentThread().isInterrupted()) {
                    cancel(service[0]);
                    throw new InterruptedException("Kotlin build cancelled");
                }
                if (elapsed > TIMEOUT_MS) {
                    cancel(service[0]);
                    throw new IOException("Kotlin compiler timed out after " + (TIMEOUT_MS / 60000L) + " minutes");
                }
                if (elapsed % 2000 < 250)
                    progress.update("Compiling Kotlin… " + (elapsed / 1000L) + "s ["
                        + readText(stageFile).trim() + "]", 33);
            }
        } finally {
            try { app.unbindService(connection); } catch (RuntimeException ignored) { }
        }
        if (sendFailed[0]) return null;
        if (box[0] == null) {
            KotlinCompileRunner.Outcome died = new KotlinCompileRunner.Outcome();
            died.code = KotlinCompileRunner.PROCESS_DIED;
            died.stage = readText(stageFile).trim();
            if (died.stage.length() == 0) died.stage = "start";
            return died;
        }
        return box[0];
    }

    private static void cancel(Messenger service) {
        if (service == null) return;
        try { service.send(Message.obtain(null, KotlinCompileService.MSG_CANCEL)); }
        catch (RemoteException ignored) { }
    }

    private static KotlinCompileRunner.Outcome runInProcess(final Context context, final File compiler,
            final File args, final File log, final File stageFile, final File props) throws Exception {
        final KotlinCompileRunner.Outcome[] box = new KotlinCompileRunner.Outcome[1];
        Thread t = new Thread(null, new Runnable() {
            public void run() {
                box[0] = KotlinCompileRunner.run(compiler, args, log, stageFile, context.getCodeCacheDir(), props);
            }
        }, "kotlinc-inprocess", 512L * 1024L * 1024L);
        t.start();
        t.join();
        return box[0];
    }

    static String readText(File f) {
        try { return f.isFile() ? new String(KotlinToolchain.readAll(new FileInputStream(f)), "UTF-8") : ""; }
        catch (IOException e) { return ""; }
    }

    private static void writeLines(File f, List<String> lines) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (String line : lines) sb.append(line).append('\n');
        File parent = f.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create " + parent);
        FileOutputStream out = new FileOutputStream(f);
        try { out.write(sb.toString().getBytes("UTF-8")); } finally { out.close(); }
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }
}
