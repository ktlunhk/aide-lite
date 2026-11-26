package com.aideui;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * One-tap, on-device installer for the Android-port Kotlin compiler.
 * Downloads the patched compiler + dependencies from Maven repositories, converts them to DEX with
 * the app's bundled D8, merges resources (META-INF/services, builtins), and installs the bundle via
 * KotlinOnDeviceCompiler.importCompiler(). No PC or script required.
 */
final class KotlinCompilerInstaller {
    private KotlinCompilerInstaller() { }

    static final String KOTLIN_VERSION = "2.3.21";
    /** Patched Android-port compiler (JitPack) plus explicit runtime deps in case its POM omits them. */
    static final String[] COORDINATES = {
        "com.github.PranavPurwar:kotlinc-android:" + KOTLIN_VERSION,
        "com.github.PranavPurwar:javac-android:26.0.0",
        "org.jetbrains.kotlin:kotlin-stdlib:" + KOTLIN_VERSION,
        "org.jetbrains.kotlin:kotlin-reflect:" + KOTLIN_VERSION,
        "org.jetbrains.kotlin:kotlin-script-runtime:" + KOTLIN_VERSION,
        "org.jetbrains.kotlin:kotlin-daemon-embeddable:" + KOTLIN_VERSION,
        "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.8.0",
        "org.jetbrains.kotlinx:kotlinx-collections-immutable-jvm:0.4.0",
        "com.google.guava:guava:33.6.0-android",
        "org.codehaus.woodstox:stax2-api:4.3.0",
        "org.jetbrains.intellij.deps:trove4j:1.0.20200330",
        "org.jdom:jdom2:2.0.6.1",
        "org.antlr:antlr4-runtime:4.13.2",
        "io.vavr:vavr:1.0.1",
        "org.lsposed.hiddenapibypass:hiddenapibypass:6.1",
        "com.github.Cosmic-IDE.kotlinc-android:jaxp:fce2462f00"
    };

    interface Log { void update(String message); }

    static void install(final Context context, final Log log) throws Exception {
        File root = new File(context.getFilesDir(), "kotlin-toolchain/build");
        File module = new File(root, "module");
        if (!module.isDirectory() && !module.mkdirs()) throw new IOException("Cannot create " + module);

        // 1. Generate a Gradle file the existing Maven resolver understands.
        StringBuilder gradle = new StringBuilder();
        gradle.append("repositories {\n    maven { url 'https://jitpack.io' }\n}\ndependencies {\n");
        for (String c : COORDINATES) gradle.append("    implementation '").append(c).append("'\n");
        gradle.append("}\n");
        FileOutputStream g = new FileOutputStream(new File(module, "build.gradle"));
        try { g.write(gradle.toString().getBytes("UTF-8")); } finally { g.close(); }

        // 2. Download (cached; safe to re-run after an interruption).
        log.update("Downloading Kotlin compiler libraries…");
        MavenLibraries.Summary summary = MavenLibraries.sync(module, new MavenLibraries.Progress() {
            public void update(String m) { log.update(m); }
        }, true);
        List<File> jars = summary.jars;
        if (jars.isEmpty()) throw new IOException("No compiler libraries were downloaded");
        boolean hasCompiler = false;
        for (File jar : jars) if (hasEntry(jar, "org/jetbrains/kotlin/cli/jvm/K2JVMCompiler.class")) { hasCompiler = true; break; }
        if (!hasCompiler) throw new IOException("Downloaded libraries do not contain K2JVMCompiler. "
            + "Check the JitPack coordinate com.github.PranavPurwar:kotlinc-android:" + KOTLIN_VERSION
            + "\n" + summary.message(module));

        File marker = new File(root, "dex2/source.txt");
        String previous = marker.isFile() ? new String(read(new FileInputStream(marker)), "UTF-8") : "";
        if (!previous.equals("download-v2\n")) {
            deleteTree(new File(root, "dex2"));   // output of an older, non-desugared conversion
        }
        File d2 = new File(root, "dex2");
        if (!d2.isDirectory() && !d2.mkdirs()) throw new IOException("Cannot create " + d2);
        FileOutputStream m = new FileOutputStream(marker);
        try { m.write("download-v2\n".getBytes("UTF-8")); } finally { m.close(); }
        convertAndInstall(context, root, jars, log);
    }

    /** Folder the user fills with compiler jars for the offline install. */
    static File jarFolder() {
        return new File(android.os.Environment.getExternalStorageDirectory(), "KotlinCompilerJars");
    }

    /** Installs from jars/aars the user placed in jarFolder(): no download needed. */
    static void installFromFolder(final Context context, final Log log) throws Exception {
        File folder = jarFolder();
        File[] found = folder.listFiles();
        if (found == null) throw new IOException("Folder not found: " + folder);
        File root = new File(context.getFilesDir(), "kotlin-toolchain/build");
        File unpacked = new File(root, "local-jars");
        deleteTree(unpacked);
        if (!unpacked.mkdirs()) throw new IOException("Cannot create " + unpacked);
        java.util.Arrays.sort(found);
        List<File> jars = new ArrayList<File>();
        for (File f : found) {
            String n = f.getName().toLowerCase(java.util.Locale.US);
            if (!f.isFile() || f.length() == 0) continue;
            if (n.endsWith(".jar")) {
                // The compiler jar goes first so progress and errors name it early.
                File copy = new File(unpacked, f.getName());
                copyFile(f, copy);
                if (hasEntry(copy, "org/jetbrains/kotlin/cli/jvm/K2JVMCompiler.class")) jars.add(0, copy);
                else jars.add(copy);
            } else if (n.endsWith(".aar")) {
                ZipFile z = new ZipFile(f);
                try {
                    ZipEntry e = z.getEntry("classes.jar");
                    if (e == null) continue;
                    File copy = new File(unpacked, f.getName() + "-classes.jar");
                    FileOutputStream o = new FileOutputStream(copy);
                    try { o.write(read(z.getInputStream(e))); } finally { o.close(); }
                    jars.add(copy);
                } finally { z.close(); }
            }
        }
        if (jars.isEmpty()) throw new IOException("No .jar or .aar files found in " + folder);
        boolean hasCompiler = false;
        for (File jar : jars) if (hasEntry(jar, "org/jetbrains/kotlin/cli/jvm/K2JVMCompiler.class")) { hasCompiler = true; break; }
        if (!hasCompiler) throw new IOException("None of the " + jars.size() + " jars in " + folder
            + " contains org.jetbrains.kotlin.cli.jvm.K2JVMCompiler. Add the Kotlin compiler jar.");
        // Different jar set than any earlier attempt: do not reuse old partial work.
        File marker = new File(root, "dex2/source.txt");
        StringBuilder sig = new StringBuilder("folder-v2\n");
        for (File j : jars) sig.append(j.getName()).append(':').append(j.length()).append('\n');
        String previous = marker.isFile() ? new String(read(new FileInputStream(marker)), "UTF-8") : "";
        if (!previous.equals(sig.toString())) {
            deleteTree(new File(root, "dex2"));
            File d = new File(root, "dex2");
            if (!d.mkdirs()) throw new IOException("Cannot create " + d);
            FileOutputStream m = new FileOutputStream(marker);
            try { m.write(sig.toString().getBytes("UTF-8")); } finally { m.close(); }
        }
        log.update("Found " + jars.size() + " jars in " + folder);
        convertAndInstall(context, root, jars, log);
        deleteTree(unpacked);
    }

    private static void convertAndInstall(Context context, File root, List<File> jars, Log log) throws Exception {
        // 3. D8 on-device.
        log.update("Converting " + jars.size() + " libraries to DEX (this takes a while)…");
        File dexOut = new File(root, "dex2"); // persistent: allows resuming after a crash
        if (!dexOut.isDirectory() && !dexOut.mkdirs()) throw new IOException("Cannot create " + dexOut);
        File platform = JavaCompiler.findAndroidJar(context, true);
        if (platform == null) throw new IOException("Bundled android.jar unavailable");
        convertAll(context, jars, platform, dexOut, log);

        // 4. Package DEX + resources.
        log.update("Packaging compiler bundle…");
        File bundle = new File(root, "kotlin-compiler-android.dex.jar");
        if (bundle.exists() && !bundle.delete()) throw new IOException("Cannot replace " + bundle);
        packageBundle(dexOut, jars, bundle);

        // 5. Install (validates multi-dex + K2JVMCompiler entry point).
        log.update("Installing compiler…");
        FileInputStream in = new FileInputStream(bundle);
        KotlinOnDeviceCompiler.importCompiler(context, in);
        bundle.delete();
        // Keep the stdlib that matches this compiler so builds compile and package the same version.
        KotlinToolchain.saveFromJars(context, jars, KOTLIN_VERSION);
        deleteTree(dexOut);
    }

    private static void copyFile(File from, File to) throws IOException {
        FileInputStream in = new FileInputStream(from);
        FileOutputStream out = new FileOutputStream(to);
        try { byte[] b = new byte[65536]; int n; while ((n = in.read(b)) != -1) out.write(b, 0, n); }
        finally { try { in.close(); } finally { out.close(); } }
    }

    private static void convertAll(Context context, List<File> jars, File platform, File dexOut, Log log) throws Exception {
        convertAll(context, jars, new ArrayList<File>(), platform, dexOut, log);
    }

    /**
     * Converts each jar separately (bounded memory), numbering DEX output classes1..N.
     * extraClasspath: jars D8 may look into (not converted), e.g. the compiler a plugin links against.
     */
    static void convertAll(final Context context, final List<File> jars, final List<File> extraClasspath,
                           final File platform, final File dexOut, final Log log) throws Exception {
        final ClassLoader loader = AndroidR8Loader.get(context);
        final Throwable[] failure = new Throwable[1];
        final String[] where = new String[1];
        Thread t = new Thread(null, new Runnable() {
            public void run() {
                int index = 0;
                try {
                    File doneFile = new File(dexOut, "done.txt");
                    Set<String> done = new HashSet<String>();
                    if (doneFile.isFile()) {
                        for (String line : new String(read(new FileInputStream(doneFile)), "UTF-8").split("\\n"))
                            if (line.length() > 0) done.add(line);
                        File[] existing = dexOut.listFiles();
                        if (existing != null) for (File f : existing)
                            if (f.getName().matches("classes\\d*\\.dex")) index++;
                    } else {
                        File[] stale = dexOut.listFiles();
                        if (stale != null) for (File f : stale) deleteTree(f);
                    }
                    File chunkDir = new File(dexOut.getParentFile(), "chunks");
                    List<File> none = new ArrayList<File>();
                    for (int i = 0; i < jars.size(); i++) {
                        File jar = jars.get(i);
                        where[0] = jar.getName();
                        deleteTree(chunkDir);
                        List<File> chunks = splitJar(jar, chunkDir, 3L * 1024 * 1024);
                        for (int c = 0; c < chunks.size(); c++) {
                            String key = jar.getName() + "#" + jar.length() + "#" + c + "#desugar";
                            File chunk = chunks.get(c);
                            if (done.contains(key)) { chunk.delete(); continue; }
                            Runtime rt = Runtime.getRuntime();
                            log.update("DEX jar " + (i + 1) + "/" + jars.size() + " part " + (c + 1) + "/"
                                + chunks.size() + "  heap " + ((rt.totalMemory() - rt.freeMemory()) >> 20) + " MB\n"
                                + jar.getName() + "\n(resumes automatically if interrupted)");
                            File part = new File(dexOut, "part");
                            deleteTree(part);
                            if (!part.mkdirs()) throw new IOException("Cannot create " + part);
                            try {
                                // Desugaring MUST stay on: Android has no LambdaMetafactory, so raw
                                // invokedynamic lambdas fail with NoSuchMethodError at runtime.
                                runD8One(loader, chunk, withExtra(others(jars, jar), extraClasspath), platform, part, false);
                            } catch (Throwable first) {
                                StandaloneApkBuild.cleanDexOutput(part);
                                try { runD8One(loader, chunk, none, platform, part, false); }
                                catch (Throwable second) { second.addSuppressed(first); throw second; }
                            }
                            File[] made = part.listFiles();
                            java.util.Arrays.sort(made);
                            for (File f : made) {
                                if (!f.getName().matches("classes\\d*\\.dex")) continue;
                                index++;
                                File dest = new File(dexOut, index == 1 ? "classes.dex" : "classes" + index + ".dex");
                                if (!f.renameTo(dest)) throw new IOException("Cannot move " + f);
                            }
                            deleteTree(part);
                            chunk.delete();
                            FileOutputStream mark = new FileOutputStream(doneFile, true);
                            try { mark.write((key + "\n").getBytes("UTF-8")); } finally { mark.close(); }
                            done.add(key);
                            System.gc();
                        }
                    }
                    deleteTree(chunkDir);
                } catch (Throwable e) { failure[0] = e; }
            }
        }, "kotlin-d8", 256L * 1024L * 1024L);
        t.start(); t.join();
        if (failure[0] != null) {
            String chain = chain(failure[0]);
            try {
                File log2 = new File(context.getFilesDir(), "kotlin-toolchain/install-error.txt");
                FileOutputStream o = new FileOutputStream(log2);
                try { o.write(("Jar: " + where[0] + "\n" + chain).getBytes("UTF-8")); } finally { o.close(); }
            } catch (IOException ignored) { }
            throw new IOException("D8 failed on " + where[0] + ":\n" + chain);
        }
        File[] dex = dexOut.listFiles();
        boolean any = false;
        if (dex != null) for (File f : dex) if (f.getName().matches("classes\\d*\\.dex")) any = true;
        if (!any) throw new IOException("D8 produced no DEX output");
    }

    private static List<File> withExtra(List<File> list, List<File> extra) {
        if (extra != null) for (File f : extra) if (f != null && f.isFile() && !list.contains(f)) list.add(f);
        return list;
    }

    private static List<File> others(List<File> all, File except) {
        List<File> list = new ArrayList<File>();
        for (File f : all) if (!f.equals(except)) list.add(f);
        return list;
    }

    /** Splits a jar's .class files into jars of at most maxBytes (uncompressed) to bound D8 memory. */
    private static List<File> splitJar(File jar, File dir, long maxBytes) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
        List<File> out = new ArrayList<File>();
        ZipFile zf = new ZipFile(jar);
        ZipOutputStream zo = null;
        long size = 0;
        try {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String n = e.getName();
                if (e.isDirectory() || !n.endsWith(".class") || n.startsWith("META-INF/")
                    || n.endsWith("module-info.class") || n.contains("..")) continue;
                byte[] data = read(zf.getInputStream(e));
                if (zo == null || size + data.length > maxBytes) {
                    if (zo != null) zo.close();
                    File f = new File(dir, "chunk" + out.size() + ".jar");
                    zo = new ZipOutputStream(new FileOutputStream(f));
                    out.add(f); size = 0;
                }
                zo.putNextEntry(new ZipEntry(n));
                zo.write(data);
                zo.closeEntry();
                size += data.length;
            }
        } finally {
            if (zo != null) zo.close();
            zf.close();
        }
        return out;
    }

    private static void runD8One(ClassLoader loader, File program, List<File> libraries, File platform,
                                 File output, boolean noDesugar) throws Exception {
        Class<?> commandType = Class.forName("com.android.tools.r8.D8Command", true, loader);
        Object builder = commandType.getMethod("builder").invoke(null);
        Class<?> builderType = builder.getClass();
        Class<?> outputMode = Class.forName("com.android.tools.r8.OutputMode", true, loader);
        java.lang.reflect.Method outputSetter = null;
        for (java.lang.reflect.Method m : builderType.getMethods()) {
            Class<?>[] pt = m.getParameterTypes();
            if (m.getName().equals("setOutput") && pt.length == 2 && pt[1].equals(outputMode)) { outputSetter = m; break; }
        }
        if (outputSetter == null) throw new NoSuchMethodException("D8 builder setOutput(Path, OutputMode)");
        Class<?> pathType = outputSetter.getParameterTypes()[0];
        builderType.getMethod("addProgramFiles", java.util.Collection.class).invoke(builder,
            java.util.Collections.singletonList(StandaloneApkBuild.d8Path(loader, pathType, program)));
        if (!noDesugar) {
            List<Object> lib = new ArrayList<Object>();
            lib.add(StandaloneApkBuild.d8Path(loader, pathType, platform));
            builderType.getMethod("addLibraryFiles", java.util.Collection.class).invoke(builder, lib);
            if (!libraries.isEmpty()) {
                // The other compiler jars: lets D8 resolve interfaces/superclasses across chunk boundaries.
                List<Object> cp = new ArrayList<Object>();
                for (File f : libraries) cp.add(StandaloneApkBuild.d8Path(loader, pathType, f));
                builderType.getMethod("addClasspathFiles", java.util.Collection.class).invoke(builder, cp);
            }
        }
        builderType.getMethod("setMinApiLevel", int.class).invoke(builder, 26);
        try { builderType.getMethod("setThreadCount", int.class).invoke(builder, 1); }
        catch (NoSuchMethodException ignored) { }
        if (noDesugar) {
            try { builderType.getMethod("setDisableDesugaring", boolean.class).invoke(builder, true); }
            catch (NoSuchMethodException ignored) { }
        }
        @SuppressWarnings("unchecked") Object mode = Enum.valueOf((Class<Enum>) outputMode, "DexIndexed");
        outputSetter.invoke(builder, StandaloneApkBuild.d8Path(loader, pathType, output), mode);
        Object command = builderType.getMethod("build").invoke(builder);
        Class.forName("com.android.tools.r8.D8", true, loader).getMethod("run", commandType).invoke(null, command);
    }

    /** Full cause chain, so D8's generic "failed to complete" shows the real error. */
    static String chain(Throwable error) {
        StringBuilder sb = new StringBuilder();
        Throwable cur = error;
        for (int depth = 0; cur != null && depth < 8; depth++) {
            Throwable shown = StandaloneApkBuild.unwrap(cur);
            sb.append(depth == 0 ? "" : "\ncaused by ").append(shown.getClass().getName()).append(": ")
              .append(shown.getMessage());
            StackTraceElement[] st = shown.getStackTrace();
            for (int i = 0; i < Math.min(3, st.length); i++) sb.append("\n   at ").append(st[i]);
            for (Throwable sup : shown.getSuppressed()) {
                Throwable u = StandaloneApkBuild.unwrap(sup);
                sb.append("\n[first attempt] ").append(u.getClass().getSimpleName()).append(": ").append(u.getMessage());
                Throwable c = u.getCause();
                if (c != null) sb.append("\n   cause: ").append(c.getClass().getName()).append(": ").append(c.getMessage());
            }
            if (shown.getCause() == shown) break;
            cur = shown.getCause();
        }
        return sb.length() > 2500 ? sb.substring(0, 2500) : sb.toString();
    }

    static void packageBundle(File dexDir, List<File> jars, File bundle) throws IOException {
        ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(bundle));
        try {
            Set<String> seen = new HashSet<String>();
            File[] files = dexDir.listFiles();
            if (files == null) throw new IOException("No DEX output");
            for (File f : files) {
                if (!f.getName().matches("classes\\d*\\.dex")) continue;
                putFile(zip, f.getName(), f); seen.add(f.getName());
            }
            Map<String, StringBuilder> services = new LinkedHashMap<String, StringBuilder>();
            for (File jar : jars) {
                ZipFile zf = new ZipFile(jar);
                try {
                    Enumeration<? extends ZipEntry> en = zf.entries();
                    while (en.hasMoreElements()) {
                        ZipEntry e = en.nextElement();
                        String n = e.getName();
                        if (e.isDirectory() || n.endsWith(".class") || n.contains("..") || n.startsWith("/")) continue;
                        String up = n.toUpperCase(java.util.Locale.US);
                        if (up.startsWith("META-INF/") && (up.endsWith(".SF") || up.endsWith(".RSA")
                            || up.endsWith(".DSA") || up.endsWith(".MF"))) continue;
                        if (n.startsWith("META-INF/versions/")) continue;
                        if (n.matches("classes\\d*\\.dex")) continue;
                        if (n.startsWith("META-INF/services/")) {
                            StringBuilder sb = services.get(n);
                            if (sb == null) { sb = new StringBuilder(); services.put(n, sb); }
                            sb.append(new String(read(zf.getInputStream(e)), "UTF-8").trim()).append('\n');
                            continue;
                        }
                        if (!seen.add(n)) continue;
                        zip.putNextEntry(new ZipEntry(n));
                        zip.write(read(zf.getInputStream(e)));
                        zip.closeEntry();
                    }
                } finally { zf.close(); }
            }
            for (Map.Entry<String, StringBuilder> s : services.entrySet()) {
                zip.putNextEntry(new ZipEntry(s.getKey()));
                zip.write(s.getValue().toString().getBytes("UTF-8"));
                zip.closeEntry();
            }
        } finally { zip.close(); }
    }

    private static void putFile(ZipOutputStream zip, String name, File f) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        FileInputStream in = new FileInputStream(f);
        try { byte[] b = new byte[65536]; int n; while ((n = in.read(b)) != -1) zip.write(b, 0, n); }
        finally { in.close(); }
        zip.closeEntry();
    }

    private static byte[] read(InputStream in) throws IOException {
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[32768]; int n;
            while ((n = in.read(b)) != -1) out.write(b, 0, n);
            return out.toByteArray();
        } finally { in.close(); }
    }

    static boolean hasEntry(File jar, String name) {
        ZipFile z = null;
        try { z = new ZipFile(jar); return z.getEntry(name) != null; }
        catch (IOException e) { return false; }
        finally { if (z != null) try { z.close(); } catch (IOException ignored) { } }
    }

    static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }
}
