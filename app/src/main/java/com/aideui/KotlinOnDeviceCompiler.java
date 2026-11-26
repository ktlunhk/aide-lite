package com.aideui;

import android.content.Context;
import dalvik.system.DexClassLoader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;
import java.util.zip.ZipEntry;
import java.util.Enumeration;

/**
 * Optional, in-process Kotlin compiler adapter. Unlike a Termux/Gradle shell runner,
 * compilation stays inside the AideUI process. An ART-compatible compiler DEX/JAR
 * must be supplied in assets; desktop kotlinc JARs are NOT Android executables.
 */
final class KotlinOnDeviceCompiler {
    static final String COMPILER_ASSET = "kotlin-compiler-android.dex.jar";
    static final String STDLIB_ASSET = "kotlin-stdlib.jar";
    private KotlinOnDeviceCompiler() { }

    static List<File> collectSources(File module, boolean classic, boolean release) {
        List<File> files = new ArrayList<File>();
        if (classic) {
            collect(new File(module, "src"), files);
        } else {
            collect(new File(module, "src/main/java"), files);
            collect(new File(module, "src/main/kotlin"), files);
            collect(new File(module, release ? "src/release/java" : "src/debug/java"), files);
            collect(new File(module, release ? "src/release/kotlin" : "src/debug/kotlin"), files);
        }
        return files;
    }

    static void collect(File dir, List<File> files) {
        if (!dir.isDirectory()) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) collect(child, files);
            else if (child.isFile() && child.getName().endsWith(".kt")) files.add(child);
        }
    }

    /**
     * Prefer an explicitly installed compiler bundle, then the packaged asset.
     * This does not download or synthesize a Kotlin compiler.
     */
    private static File installedCompiler(Context context) {
        return KotlinToolchain.compiler(context);
    }

    /** True when a pre-built kotlin-compiler-android.dex.jar is packaged in the APK assets. */
    static boolean bundledAvailable(Context context) {
        InputStream in = null;
        try { in = context.getAssets().open(COMPILER_ASSET); return true; }
        catch (IOException e) { return false; }
        finally { if (in != null) try { in.close(); } catch (IOException ignored) { } }
    }

    /** Installs the packaged compiler (and its matching stdlib + version) into files/kotlin-toolchain. */
    static synchronized void installBundled(Context context) throws IOException {
        if (KotlinToolchain.compiler(context).isFile()) return;
        importCompiler(context, context.getAssets().open(COMPILER_ASSET));
        File stdlib = extractAssetStdlib(context);
        File dir = KotlinToolchain.dir(context);
        File target = KotlinToolchain.installedStdlib(context);
        FileInputStream in = new FileInputStream(stdlib);
        FileOutputStream out = new FileOutputStream(target);
        try { byte[] b = new byte[65536]; int n; while ((n = in.read(b)) != -1) out.write(b, 0, n); }
        finally { try { in.close(); } finally { out.close(); } }
        String version = KotlinToolchain.manifestVersion(target);
        if (version == null) version = KotlinCompilerInstaller.KOTLIN_VERSION;
        FileOutputStream v = new FileOutputStream(KotlinToolchain.versionFile(context));
        try { v.write((version + "\n").getBytes("UTF-8")); } finally { v.close(); }
    }

    static void ensureCompilerAvailable(Context context) throws IOException {
        File installed = installedCompiler(context);
        if (installed.isFile()) {
            verifyCompilerDex(installed);
            return;
        }
        // A pre-built compiler packaged in assets is installed once into the toolchain folder.
        if (bundledAvailable(context)) { installBundled(context); return; }
        // Validate the *actual* packaged archive, not just its PK signature.
        // A desktop JAR also begins with PK but cannot be loaded by ART.
        InputStream in;
        try {
            in = context.getAssets().open(COMPILER_ASSET);
        } catch (IOException missing) {
            throw new IOException("The Kotlin compiler is missing from this AideUI build (assets/kotlin-compiler-android.dex.jar). "
                + "Java-only projects are unaffected.", missing);
        }
        try { in.close(); } finally { }
        File bundled = extract(context, COMPILER_ASSET);
        verifyCompilerDex(bundled);
    }

    /** Import an explicitly user-selected DEX compiler bundle without external storage permissions. */
    static File importCompiler(Context context, InputStream input) throws IOException {
        if (input == null) throw new IOException("No compiler file selected");
        File directory = new File(context.getFilesDir(), "kotlin-toolchain");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create toolchain directory");
        File pending = new File(directory, "compiler-import.tmp");
        File target = installedCompiler(context);
        try {
            FileOutputStream out = new FileOutputStream(pending);
            try {
                byte[] buffer = new byte[32768]; int n; long total = 0;
                while ((n = input.read(buffer)) != -1) {
                    total += n;
                    if (total > 150L * 1024L * 1024L) throw new IOException("Compiler archive exceeds 150 MB limit");
                    out.write(buffer, 0, n);
                }
            } finally { out.close(); }
            verifyCompilerDex(pending);
            if (!pending.setReadOnly()) throw new IOException("Cannot mark compiler DEX read-only");
            // Keep the previous compiler until the replacement is safely installed.
            File backup = new File(directory, "compiler-backup.tmp");
            if (backup.exists() && !backup.delete()) throw new IOException("Cannot clear compiler backup");
            boolean hadOld = target.exists();
            if (hadOld && !target.renameTo(backup)) throw new IOException("Cannot back up existing compiler");
            if (!pending.renameTo(target)) {
                if (hadOld && !backup.renameTo(target))
                    throw new IOException("Compiler installation failed; previous compiler remains at " + backup);
                throw new IOException("Cannot install compiler bundle; previous compiler restored");
            }
            if (backup.exists()) backup.delete();
            return target;
        } finally {
            try { input.close(); } catch (IOException ignored) { }
            if (pending.exists()) pending.delete();
        }
    }

    private static void verifyCompilerDex(File archive) throws IOException {
        ZipFile zip = new ZipFile(archive);
        try {
            byte[] marker = "Lorg/jetbrains/kotlin/cli/jvm/K2JVMCompiler;".getBytes("UTF-8");
            int dexCount = 0;
            boolean found = false;
            for (int i = 1; i < 1000; i++) {
                String name = i == 1 ? "classes.dex" : "classes" + i + ".dex";
                ZipEntry dex = zip.getEntry(name);
                if (dex == null) { if (i == 1) continue; else break; }
                if (dex.getSize() == 0) throw new IOException("Empty " + name + " in " + archive.getName());
                dexCount++;
                InputStream in = zip.getInputStream(dex);
                try {
                    byte[] magic = new byte[8];
                    int count = 0;
                    while (count < 8) {
                        int n = in.read(magic, count, 8 - count);
                        if (n < 0) break;
                        count += n;
                    }
                    if (count != 8 || magic[0] != 'd' || magic[1] != 'e' || magic[2] != 'x'
                        || magic[3] != '\n' || magic[7] != 0)
                        throw new IOException("Invalid DEX header in " + name);
                    if (!found) {
                        int matched = 0, value;
                        while ((value = in.read()) != -1) {
                            if (value == (marker[matched] & 0xff)) {
                                if (++matched == marker.length) { found = true; break; }
                            } else {
                                matched = value == (marker[0] & 0xff) ? 1 : 0;
                            }
                        }
                    }
                } finally { in.close(); }
            }
            if (dexCount == 0)
                throw new IOException("Kotlin compiler bundle lacks classes.dex: " + archive.getName()
                    + ". A standard JVM compiler JAR cannot run through DexClassLoader.");
            if (!found)
                throw new IOException("DEX archive does not contain org.jetbrains.kotlin.cli.jvm.K2JVMCompiler. "
                    + "Build the bundle with tools/make-compiler-dex.sh.");
        } finally { zip.close(); }
    }

    private static File extract(Context context, String name) throws IOException {
        File folder = new File(context.getCodeCacheDir(), "myaide-kotlin-toolchain");
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("Cannot create compiler cache");
        File target = new File(folder, name);
        InputStream source = context.getAssets().open(name);
        File temp = new File(folder, name + ".tmp");
        FileOutputStream out = new FileOutputStream(temp);
        try {
            byte[] b = new byte[32768]; int n;
            while ((n = source.read(b)) >= 0) if (n > 0) out.write(b, 0, n);
        } finally { try { source.close(); } finally { out.close(); } }
        if (target.isFile() && !target.delete()) throw new IOException("Cannot update toolchain cache");
        // Android 14+ requires dynamically loaded code to be read-only before loading.
        // Standard library JARs are classpath inputs, not dynamically loaded code.
        if (COMPILER_ASSET.equals(name) && !temp.setReadOnly())
            throw new IOException("Cannot make compiler cache read-only");
        if (!temp.renameTo(target)) throw new IOException("Cannot cache compiler " + name);
        return target;
    }

    /** Stdlib for the build: the one matching the installed compiler when available. */
    static File extractStdlib(Context context) throws IOException { return KotlinToolchain.stdlib(context); }

    static File extractAssetStdlib(Context context) throws IOException { return extract(context, STDLIB_ASSET); }

    static File extractAssetCompiler(Context context) throws IOException { return extract(context, COMPILER_ASSET); }
}
