package com.aideui;

import android.content.Context;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs the Eclipse Java compiler (ECJ) on the opened file, like AIDE does,
 * and turns its problems into error/warning ranges.
 *
 * ECJ is called through reflection so the app still builds and runs without it.
 * Needs: (1) the ECJ library (see app/build.gradle) and (2) an android.jar.
 */
public class JavaCompiler {

    public interface Callback {
        /** errors == null means the compiler could not run; status says why. */
        void onDone(List<JavaAnalyzer.Err> errors, String status);
    }

    static final String BATCH = "org.eclipse.jdt.core.compiler.batch.BatchCompiler";
    static final String PROGRESS = "org.eclipse.jdt.core.compiler.CompilationProgress";

    /** Places searched for android.jar, in order. */
    public static File[] androidJarCandidates(Context ctx) {
        File root = Environment.getExternalStorageDirectory();
        File ext = ctx.getExternalFilesDir(null);
        return new File[] {
            new File(root, "AppProjects/android.jar"),
            new File(root, "AppProjects/libs/android.jar"),
            new File(root, "android.jar"),
            new File(root, "Download/android.jar"),
            ext == null ? null : new File(ext, "android.jar"),
            new File(ctx.getFilesDir(), "android.jar"),
        };
    }

    public static File findAndroidJar(Context ctx, boolean extractBundled) {
        for (File f : androidJarCandidates(ctx))
            if (f != null && f.isFile() && f.length() > 0) return f;
        if (extractBundled && hasBundledJar(ctx)) {
            File dest = new File(ctx.getFilesDir(), "android.jar");
            try {
                java.io.InputStream in = ctx.getAssets().open("android.jar");
                File tmp = new File(ctx.getFilesDir(), "android.jar.tmp");
                java.io.FileOutputStream out = new java.io.FileOutputStream(tmp);
                byte[] buf = new byte[65536]; int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                out.close(); in.close();
                if (tmp.renameTo(dest)) return dest;
            } catch (Exception e) { }
        }
        return null;
    }

    public static boolean hasBundledJar(Context ctx) {
        try { ctx.getAssets().open("android.jar").close(); return true; }
        catch (Exception e) { return false; }
    }

    /**
     * Android SDK android.jar deliberately omits LambdaMetafactory, which ECJ 4.6.1
     * needs to type-check Java 8 lambdas. This tiny JAR supplies only the missing
     * compile-time API signature. It is kept in assets/private storage, never in
     * app/libs or the target project's Gradle dependencies, and is NOT loaded or
     * executed when the user's APK runs (lambda desugaring is done by the builder).
     */
    static final String JAVA8_STUB_ASSET = "ecj-java8-stubs.jar";

    public static File findJava8CompilerStubs(Context ctx) {
        File destination = new File(ctx.getFilesDir(), JAVA8_STUB_ASSET);
        java.io.InputStream input = null;
        java.io.FileOutputStream output = null;
        try {
            input = ctx.getAssets().open(JAVA8_STUB_ASSET);
            int expectedLength = input.available();
            if (destination.isFile() && destination.length() > 0
                    && (expectedLength <= 0 || destination.length() == expectedLength)) {
                input.close();
                return destination;
            }
            File tmp = new File(ctx.getFilesDir(), JAVA8_STUB_ASSET + ".tmp");
            output = new java.io.FileOutputStream(tmp);
            byte[] buffer = new byte[8192];
            int n;
            while ((n = input.read(buffer)) != -1) output.write(buffer, 0, n);
            output.close(); output = null;
            input.close(); input = null;
            if (!tmp.renameTo(destination)) {
                if (destination.exists() && !destination.delete()) return null;
                if (!tmp.renameTo(destination)) return null;
            }
            return destination.isFile() && destination.length() > 0 ? destination : null;
        } catch (Exception ignored) {
            return null;
        } finally {
            if (output != null) try { output.close(); } catch (Exception ignored) { }
            if (input != null) try { input.close(); } catch (Exception ignored) { }
        }
    }

    public static boolean hasEcj() {
        try { Class.forName(BATCH); return true; } catch (Throwable t) { return false; }
    }

    public static String describe(Context ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("ECJ compiler library: ").append(hasEcj() ? "found" : "NOT found").append("\n");
        File jar = findAndroidJar(ctx, false);
        sb.append("android.jar: ").append(jar != null ? jar.getAbsolutePath()
            : hasBundledJar(ctx) ? "bundled in app (extracted on first compile)" : "NOT found").append("\n");
        if (jar == null && !hasBundledJar(ctx)) {
            sb.append("\nPlace android.jar (from an Android SDK platform folder) in one of:\n");
            for (File f : androidJarCandidates(ctx)) if (f != null) sb.append("  ").append(f.getAbsolutePath()).append("\n");
        }
        if (!hasEcj())
            sb.append("\nAdd ECJ to app/build.gradle (see the dependencies block).");
        File java8Stubs = findJava8CompilerStubs(ctx);
        sb.append("\nJava 8 ECJ lambda signatures: ")
            .append(java8Stubs == null ? "NOT found" : "installed (editor-only)");
        return sb.toString();
    }

    public static void compileAsync(final Context ctx, final File file, final Callback cb) {
        compileAsync(ctx, file, "debug", cb);
    }

    public static void compileAsync(final Context ctx, final File file, final String variant, final Callback cb) {
        compileAsync(ctx, file, variant, null, cb);
    }

    /** Compile a snapshot of unsaved editor text, never the outdated disk version. */
    public static void compileAsync(final Context ctx, final File file, final String variant,
                                    final String editorSnapshot, final Callback cb) {
        final Handler main = new Handler(Looper.getMainLooper());
        Thread t = new Thread(null, new Runnable() {
            public void run() {
                final List<JavaAnalyzer.Err> errs;
                final String[] status = {null};
                List<JavaAnalyzer.Err> r = null;
                try { r = compile(ctx, file, variant, editorSnapshot, status); }
                catch (Throwable e) { status[0] = "Compiler failed: " + e; }
                errs = r;
                main.post(new Runnable() { public void run() { cb.onDone(errs, status[0]); } });
            }
        }, "ecj", 32L * 1024 * 1024);   // ECJ needs a big stack
        t.start();
    }

    static List<JavaAnalyzer.Err> compile(Context ctx, File file, String[] status) throws Exception {
        return compile(ctx, file, "debug", status);
    }

    static List<JavaAnalyzer.Err> compile(Context ctx, File file, String variant, String[] status) throws Exception {
        return compile(ctx, file, variant, null, status);
    }

    static List<JavaAnalyzer.Err> compile(Context ctx, File file, String variant,
                                          String editorSnapshot, String[] status) throws Exception {
        if (!hasEcj()) { status[0] = "ECJ library not found"; return null; }
        File androidJar = findAndroidJar(ctx, true);
        if (androidJar == null) { status[0] = "android.jar not found"; return null; }

        // Modern Gradle modules use app/src/main/java; classic AIDE projects use
        // src/com/example/package. The latter must not fall back to the package
        // directory as sourcepath (otherwise referenced sibling classes disappear).
        File srcRoot = locateSourceRoot(file);
        List<String> sourcePath = new ArrayList<String>();
        List<String> classPath = new ArrayList<String>();
        // Use a separate ECJ-only classpath entry, not the target app's libs dir.
        File java8Stubs = findJava8CompilerStubs(ctx);
        if (java8Stubs != null) classPath.add(java8Stubs.getAbsolutePath());
        if (srcRoot != null) {
            sourcePath.add(srcRoot.getAbsolutePath());
            boolean gradleLayout = srcRoot.getName().equals("java")
                && srcRoot.getParentFile() != null && srcRoot.getParentFile().getName().equals("main")
                && srcRoot.getParentFile().getParentFile() != null
                && srcRoot.getParentFile().getParentFile().getName().equals("src");
            File module = gradleLayout ? srcRoot.getParentFile().getParentFile().getParentFile() : srcRoot.getParentFile();
            if (module != null) {
                // Selected build variant affects the editor's available generated/variant sources.
                // ECJ still checks Java only: Gradle/AIDE must create and package the APK.
                String chosen = variant == null || !variant.matches("[A-Za-z0-9_]{1,70}") ? "debug" : variant;
                String[] gen = {"build/generated/source/r/" + chosen,
                                "build/generated/source/buildConfig/" + chosen,
                                "build/generated/source/r/debug", "build/gen", "gen"};
                for (String g : gen) {
                    File f = new File(module, g);
                    if (f.isDirectory() && !sourcePath.contains(f.getAbsolutePath())) sourcePath.add(f.getAbsolutePath());
                }
                try {
                    for (ProjectActions.Variant v : ProjectActions.variants(module)) {
                        if (!chosen.equals(v.name)) continue;
                        String[] sourceSets = {"src/" + v.buildType + "/java", "src/" + v.flavor + "/java",
                            "build/generated/source/r/" + v.name,
                            "build/generated/source/buildConfig/" + v.name};
                        for (String rel : sourceSets) {
                            if (v.flavor.length() == 0 && rel.equals("src//java")) continue;
                            File path = new File(module, rel);
                            if (path.isDirectory() && !sourcePath.contains(path.getAbsolutePath()))
                                sourcePath.add(path.getAbsolutePath());
                        }
                    }
                } catch (Exception ignored) { }
                addJars(new File(module, "libs"), classPath);
                if (module.getParentFile() != null) addJars(new File(module.getParentFile(), "libs"), classPath);
                // AAR classes.jar + JAR dependencies extracted by MavenLibraries.
                for (File lib : MavenLibraries.classpath(module)) classPath.add(lib.getAbsolutePath());
            }
        } else {
            sourcePath.add(file.getParentFile().getAbsolutePath());
        }

        // ECJ needs the real AAPT2-generated R.java, not a fabricated R stub.
        // Generate it for editor checks when no Gradle-generated R source is present.
        File generatedR = null;
        if (srcRoot != null) {
            try {
                generatedR = editorResourceR(ctx, file);
                if (generatedR != null) {
                    File root = generatedR;
                    String resourceNamespace = StandaloneApkBuild.resourcePackage(
                        StandaloneApkBuild.projectManifest(
                            StandaloneApkBuild.findBuildRoot(file.getParentFile())),
                            StandaloneApkBuild.findBuildRoot(file.getParentFile()));
                    for (int i = 0; i <= resourceNamespace.length() - resourceNamespace.replace(".", "").length(); i++) {
                        root = root.getParentFile();
                    }
                    if (root != null) root = root.getParentFile();
                    if (root != null && !sourcePath.contains(root.getAbsolutePath())) sourcePath.add(root.getAbsolutePath());
                }
            } catch (Exception ignored) {
                // ECJ still reports legitimate missing resource errors if AAPT2 is unavailable.
            }
        }

        // Each compile needs its own log: refresh/build-variant changes may overlap.
        File log = new File(ctx.getCacheDir(), "ecj-log-" + Thread.currentThread().getId()
                + "-" + System.nanoTime() + ".xml");

        List<String> a = new ArrayList<String>();
        a.add("-1.8"); a.add("-proc:none");
        a.add("-encoding"); a.add("UTF-8");
        a.add("-d"); a.add("none");
        a.add("-bootclasspath"); a.add(androidJar.getAbsolutePath());
        if (!classPath.isEmpty()) { a.add("-classpath"); a.add(join(classPath)); }
        File stagedRoot = null;
        File stagedFile = file;
        int insertedImportChars = 0;
        int insertedImportAfterLine = 0;
        // Both dirty buffers and cross-namespace R references are compiled in
        // an isolated scratch copy. The real project file is never overwritten.
        String original = editorSnapshot;
        if (original == null) original = MavenLibraries.readText(file);
        String stagedText = original;
        if (generatedR != null && generatedR.isFile()) {
            try {
                File module = StandaloneApkBuild.findBuildRoot(file.getParentFile());
                String namespace = StandaloneApkBuild.resourcePackage(
                    StandaloneApkBuild.projectManifest(module), module);
                String fixed = ResourceNamespaceFix.withRImport(stagedText, namespace);
                if (!fixed.equals(stagedText)) {
                    insertedImportChars = fixed.length() - stagedText.length();
                    insertedImportAfterLine = ResourceNamespaceFix.packageEndLine(stagedText);
                    stagedText = fixed;
                }
            } catch (Exception issue) {
                status[0] = "Resource namespace preparation: " + issue.getMessage();
            }
        }
        if (editorSnapshot != null || !stagedText.equals(original)) {
            stagedRoot = new File(ctx.getCacheDir(), "editor-live-overlay-"
                + Thread.currentThread().getId() + "-" + System.nanoTime());
            String relative = srcRoot != null ? file.getCanonicalPath().substring(
                srcRoot.getCanonicalPath().length() + 1) : file.getName();
            stagedFile = new File(stagedRoot, relative);
            File parent = stagedFile.getParentFile();
            if (!parent.isDirectory() && !parent.mkdirs())
                throw new java.io.IOException("Cannot create editor compilation cache: " + parent);
            java.io.FileOutputStream output = new java.io.FileOutputStream(stagedFile);
            try { output.write(stagedText.getBytes("UTF-8")); } finally { output.close(); }
            sourcePath.add(0, stagedRoot.getAbsolutePath());
        }
        a.add("-sourcepath"); a.add(join(sourcePath));
        a.add("-log"); a.add(log.getAbsolutePath());
        for (File sibling : packageSources(file)) {
            a.add(sibling.equals(file) ? stagedFile.getAbsolutePath() : sibling.getAbsolutePath());
        }
        if (generatedR != null && generatedR.isFile()) a.add(generatedR.getAbsolutePath());

        try {
            StringWriter out = new StringWriter(), err = new StringWriter();
            Class<?> batch = Class.forName(BATCH);
            Class<?> progress = Class.forName(PROGRESS);
            Method m = batch.getMethod("compile", String[].class, PrintWriter.class, PrintWriter.class, progress);
            m.invoke(null, a.toArray(new String[0]), new PrintWriter(out), new PrintWriter(err), null);

            if (!log.isFile() || log.length() == 0) {
                String msg = (err.toString() + out.toString()).trim();
                status[0] = "Compiler produced no log" + (msg.isEmpty() ? "" : ": " + firstLine(msg));
                return null;
            }
            List<JavaAnalyzer.Err> diagnostics = parseLog(log, stagedFile);
            if (insertedImportChars > 0) {
                // Only the optional synthetic R import moves source positions.
                for (JavaAnalyzer.Err problem : diagnostics) {
                    if (problem.line > insertedImportAfterLine) {
                        problem.line--;
                        problem.start = Math.max(0, problem.start - insertedImportChars);
                        problem.end = Math.max(problem.start + 1, problem.end - insertedImportChars);
                    }
                }
            }
            if (editorSnapshot == null) improveMissingResourceErrors(diagnostics, file);
            return diagnostics;
        } finally {
            log.delete();
            if (stagedRoot != null) try { StandaloneApkBuild.remove(stagedRoot); }
                catch (Exception ignored) { }
        }
    }

    /** Explain missing resources instead of treating every R error as a Java classpath bug. */
    static void improveMissingResourceErrors(List<JavaAnalyzer.Err> errors, File file) {
        File module = StandaloneApkBuild.findBuildRoot(file.getParentFile());
        if (module == null || errors == null) return;
        File res = StandaloneApkBuild.projectResources(module);
        String text;
        try { text = MavenLibraries.readText(file); } catch (Exception failure) { return; }
        String[] lines = text.split("\n", -1);
        for (JavaAnalyzer.Err e : errors) {
            if (!e.msg.contains("R cannot be resolved")) continue;
            int index = e.line - 1;
            if (index < 0 || index >= lines.length) continue;
            java.util.regex.Matcher use = java.util.regex.Pattern.compile(
                "\\bR\\.layout\\.([A-Za-z_][A-Za-z_0-9]*)").matcher(lines[index]);
            if (use.find() && !StandaloneApkBuild.layoutFileExists(res, use.group(1))) {
                e.msg = "Missing layout resource: " + use.group(1)
                    + ". Create res/layout/" + use.group(1) + ".xml";
            } else {
                e.msg = "R.java is missing or has the wrong namespace. Check Android resources,"
                    + " manifest package and Gradle namespace";
            }
        }
    }

    /**
     * Generate authoritative resource symbols in a private cache for the live editor.
     * This does not modify the user's sources or create fake layout declarations.
     */
    /** The last successful APK build is authoritative for generated R symbols. */
    static String resourceSignature(File module, File res, File manifest, String namespace) {
        long stamp = resourceStamp(res) ^ manifest.lastModified()
            ^ StandaloneApkBuild.targetSdk(module) ^ StandaloneApkBuild.minSdk(module);
        File gradle = MavenLibraries.gradleFile(module);
        if (gradle.isFile()) stamp ^= gradle.lastModified();
        return namespace + ":" + stamp;
    }

    static File cachedResourceR(Context context, File module, File res,
                                File manifest, String namespace) throws Exception {
        File cache = new File(context.getCacheDir(), "editor-r-" + Integer.toHexString(
            module.getCanonicalPath().hashCode()));
        File r = new File(cache, "generated/" + namespace.replace('.', '/') + "/R.java");
        File marker = new File(cache, ".resource-signature");
        if (r.isFile() && marker.isFile()
                && resourceSignature(module, res, manifest, namespace)
                    .equals(MavenLibraries.readText(marker).trim())) return r;
        return null;
    }

    /** Save the real AAPT2 symbols for subsequent live editor checks after a good build. */
    static void retainBuiltResourceR(Context context, File module, File generated,
                                     String namespace) throws Exception {
        File manifest = StandaloneApkBuild.projectManifest(module);
        File res = StandaloneApkBuild.projectResources(module);
        File original = new File(generated, namespace.replace('.', '/') + "/R.java");
        if (!original.isFile()) return;
        File cache = new File(context.getCacheDir(), "editor-r-" + Integer.toHexString(
            module.getCanonicalPath().hashCode()));
        File copy = new File(cache, "generated/" + namespace.replace('.', '/') + "/R.java");
        if (!copy.getParentFile().isDirectory() && !copy.getParentFile().mkdirs()) return;
        java.io.FileInputStream in = new java.io.FileInputStream(original);
        try {
            java.io.FileOutputStream out = new java.io.FileOutputStream(copy);
            try {
                byte[] buffer = new byte[16384]; int count;
                while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            } finally { out.close(); }
        } finally { in.close(); }
        File marker = new File(cache, ".resource-signature");
        java.io.FileOutputStream out = new java.io.FileOutputStream(marker);
        try { out.write(resourceSignature(module, res, manifest, namespace).getBytes("UTF-8")); }
        finally { out.close(); }
    }

    static File editorResourceR(Context context, File sourceFile) throws Exception {
        File module = StandaloneApkBuild.findBuildRoot(sourceFile.getParentFile());
        if (module == null) return null;
        File res = StandaloneApkBuild.projectResources(module);
        if (!res.isDirectory() || !StandaloneApkBuild.hasFiles(res)) return null;
        File manifest = StandaloneApkBuild.projectManifest(module);
        if (!manifest.isFile()) return null;
        String namespace = StandaloneApkBuild.resourcePackage(manifest, module);
        File previouslyBuilt = cachedResourceR(context, module, res, manifest, namespace);
        if (previouslyBuilt != null) return previouslyBuilt;
        File aapt2 = new File(context.getApplicationInfo().nativeLibraryDir, "libaapt2.so");
        if (!aapt2.isFile()) return null;
        File androidJar = findAndroidJar(context, true);
        if (androidJar == null) return null;
        File cache = new File(context.getCacheDir(), "editor-r-" + Integer.toHexString(
            module.getCanonicalPath().hashCode()));
        File r = new File(cache, "generated/" + namespace.replace('.', '/') + "/R.java");
        File marker = new File(cache, ".resource-signature");
        // Invalidate when resources, manifest or Gradle configuration change.
        String signature = resourceSignature(module, res, manifest, namespace);
        synchronized (JavaCompiler.class) {
            if (r.isFile() && marker.isFile() && signature.equals(MavenLibraries.readText(marker).trim()))
                return r;
            if (!cache.isDirectory() && !cache.mkdirs()) return null;
            File generated = new File(cache, "generated");
            if (!generated.isDirectory() && !generated.mkdirs()) return null;
            File prepared = StandaloneApkBuild.prepareManifest(manifest, module, cache);
            StandaloneApkBuild.buildAapt2(aapt2, prepared, res,
                StandaloneApkBuild.projectAssets(module), generated, cache,
                new File(cache, "resource-table.apk"), androidJar,
                StandaloneApkBuild.minSdk(module), StandaloneApkBuild.targetSdk(module),
                namespace, new ProjectBuild.Progress() {
                    public void update(String message, int percent) { }
                    public boolean isCancelled() { return Thread.currentThread().isInterrupted(); }
                });
            if (!r.isFile()) return null;
            java.io.FileOutputStream out = new java.io.FileOutputStream(marker);
            try { out.write(signature.getBytes("UTF-8")); } finally { out.close(); }
            return r;
        }
    }

    static long resourceStamp(File dir) {
        long stamp = dir.lastModified();
        File[] children = dir.listFiles();
        if (children != null) for (File f : children) {
            stamp = stamp * 31 + f.getName().hashCode();
            stamp = stamp * 31 + f.lastModified();
            if (f.isDirectory()) stamp ^= resourceStamp(f);
        }
        return stamp;
    }

    /** Find the source root using the declared package and actual directory layout. */
    static File locateSourceRoot(File file) {
        if (file == null || file.getParentFile() == null) return null;
        for (File d = file.getParentFile(); d != null; d = d.getParentFile()) {
            if ("java".equals(d.getName()) && d.getParentFile() != null
                    && "main".equals(d.getParentFile().getName())) return d;
            if ("src".equals(d.getName())) return d;
        }
        // Some non-Gradle projects put sources straight under package folders.
        // Climb by the package components only if the declaration matches the path.
        try {
            String contents = RefactorEngine.read(file);
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                    "(?m)^\\s*package\\s+([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*)\\s*;").matcher(contents);
            if (m.find()) {
                File d = file.getParentFile();
                String[] components = m.group(1).split("\\.");
                for (int i = components.length - 1; i >= 0; i--) {
                    if (d == null || !components[i].equals(d.getName())) return file.getParentFile();
                    d = d.getParentFile();
                }
                if (d != null) return d;
            }
        } catch (Exception ignored) { }
        return file.getParentFile();
    }

    /** Class compilation units sharing the opened source directory/package. */
    static List<File> packageSources(File file) {
        List<File> sources = new ArrayList<File>();
        sources.add(file);
        File parent = file.getParentFile();
        File[] siblings = parent == null ? null : parent.listFiles();
        if (siblings == null) return sources;
        java.util.Arrays.sort(siblings);
        for (File other : siblings) {
            if (!other.equals(file) && other.isFile() && other.getName().endsWith(".java")
                    && other.length() < 2L * 1024 * 1024 && sources.size() < 250) {
                sources.add(other);
            }
        }
        return sources;
    }

    static void addJars(File dir, List<String> out) {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) if (f.getName().endsWith(".jar") && !f.getName().equals("android.jar")) out.add(f.getAbsolutePath());
    }

    static String join(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (String s : l) { if (sb.length() > 0) sb.append(File.pathSeparator); sb.append(s); }
        return sb.toString();
    }

    static String firstLine(String s) { int i = s.indexOf('\n'); return i < 0 ? s : s.substring(0, i); }

    static int intAttr(XmlPullParser p, String name, int def) {
        String v = p.getAttributeValue(null, name);
        if (v == null) return def;
        try { return Integer.parseInt(v.trim()); } catch (Exception e) { return def; }
    }

    /** Reads ECJ's XML log: &lt;source path&gt; ... &lt;problem line charStart charEnd severity&gt;&lt;message value/&gt; */
    static List<JavaAnalyzer.Err> parseLog(File log, File target) throws Exception {
        List<JavaAnalyzer.Err> out = new ArrayList<JavaAnalyzer.Err>();
        String targetPath = target.getCanonicalPath();
        XmlPullParser p = Xml.newPullParser();
        p.setInput(new InputStreamReader(new FileInputStream(log), "UTF-8"));
        String curPath = null;
        int start = 0, end = 0, line = 1; boolean warning = false; String msg = null; boolean inProblem = false;
        for (int ev = p.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = p.next()) {
            if (ev == XmlPullParser.START_TAG) {
                String n = p.getName();
                if (n.equals("source")) {
                    curPath = p.getAttributeValue(null, "path");
                } else if (n.equals("problem")) {
                    inProblem = true;
                    line = intAttr(p, "line", 1);
                    start = intAttr(p, "charStart", -1);
                    end = intAttr(p, "charEnd", -1);
                    String sev = p.getAttributeValue(null, "severity");
                    warning = sev != null && !sev.equalsIgnoreCase("error");
                    msg = null;
                } else if (n.equals("message") && inProblem && msg == null) {
                    msg = p.getAttributeValue(null, "value");
                }
            } else if (ev == XmlPullParser.END_TAG) {
                String n = p.getName();
                if (n.equals("source")) curPath = null;
                else if (n.equals("problem")) {
                    inProblem = false;
                    boolean mine = curPath == null || new File(curPath).getCanonicalPath().equals(targetPath);
                    if (mine && msg != null) {
                        JavaAnalyzer.Err e = new JavaAnalyzer.Err(Math.max(start, 0), Math.max(end + 1, start + 1), msg);
                        e.line = line;
                        e.warning = warning;
                        out.add(e);
                    }
                }
            }
        }
        return out;
    }
}
