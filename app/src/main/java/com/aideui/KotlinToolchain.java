package com.aideui;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Single place that knows where the on-device Kotlin toolchain lives.
 *
 * Layout (files/kotlin-toolchain/):
 *   kotlin-compiler-android.dex.jar   the DEX'd compiler (read-only, installed by the installer)
 *   kotlin-stdlib.jar                 stdlib matching the compiler version (saved by the installer)
 *   version.txt                       Kotlin version of the installed compiler
 *   jvm.properties   (optional)       key=value pairs applied as System properties in the compiler process
 *   extra-args.txt   (optional)       one extra compiler argument per line
 */
final class KotlinToolchain {
    private KotlinToolchain() { }

    static File dir(Context context) { return new File(context.getFilesDir(), "kotlin-toolchain"); }
    static File compiler(Context context) { return new File(dir(context), KotlinOnDeviceCompiler.COMPILER_ASSET); }
    static File installedStdlib(Context context) { return new File(dir(context), "kotlin-stdlib.jar"); }
    static File versionFile(Context context) { return new File(dir(context), "version.txt"); }
    static File jvmProperties(Context context) { return new File(dir(context), "jvm.properties"); }
    static File extraArgs(Context context) { return new File(dir(context), "extra-args.txt"); }

    /** Kotlin version recorded by the installer, or null when unknown (manual import). */
    static String version(Context context) {
        File f = versionFile(context);
        if (!f.isFile()) return null;
        try {
            String v = new String(readAll(new FileInputStream(f)), "UTF-8").trim();
            return v.length() == 0 ? null : v;
        } catch (IOException e) { return null; }
    }

    /**
     * Stdlib used for compiling AND packaged into the APK. A stdlib that matches the compiler is
     * preferred; the 1.9.0 stdlib bundled in assets is only a fallback for manually imported compilers.
     */
    static File stdlib(Context context) throws IOException {
        File matched = installedStdlib(context);
        if (!(matched.isFile() && matched.length() > 0)) {
            // Self-heal: an earlier install did not keep the matching stdlib; recover it from the jar cache.
            List<File> cached = cachedCompilerJars(context);
            if (!cached.isEmpty()) {
                String known = version(context);
                saveFromJars(context, cached, known != null ? known : KotlinCompilerInstaller.KOTLIN_VERSION);
            }
        }
        if (matched.isFile() && matched.length() > 0) return matched;
        return KotlinOnDeviceCompiler.extractAssetStdlib(context);
    }

    static boolean stdlibMatchesCompiler(Context context) {
        return installedStdlib(context).isFile() && version(context) != null;
    }

    /** Picks the kotlin-stdlib jar out of the installer's jar list and stores it with the version. */
    static void saveFromJars(Context context, List<File> jars, String fallbackVersion) throws IOException {
        File chosen = null;
        for (File jar : jars) {
            String n = jar.getName().toLowerCase(java.util.Locale.US);
            // Cache names look like org.jetbrains.kotlin-kotlin-stdlib-2.3.21.jar
            if (!n.contains("kotlin-stdlib") || n.contains("jdk") || n.contains("common")) continue;
            if (hasEntry(jar, "kotlin/KotlinVersion.class") && !hasEntry(jar, "org/jetbrains/kotlin/cli/jvm/K2JVMCompiler.class")) {
                chosen = jar; break;
            }
        }
        if (chosen == null) {
            // Fallback by content: any non-compiler jar that holds the stdlib core.
            for (File jar : jars) {
                if (hasEntry(jar, "kotlin/KotlinVersion.class") && hasEntry(jar, "kotlin/jvm/internal/FunctionReferenceImpl.class")
                    && !hasEntry(jar, "org/jetbrains/kotlin/cli/jvm/K2JVMCompiler.class")) { chosen = jar; break; }
            }
        }
        if (chosen == null) return;   // do not record a version for a stdlib we do not have
        File dir = dir(context);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
        String version = fallbackVersion;
        String fromJar = manifestVersion(chosen);
        if (fromJar != null) version = fromJar;
        File tmp = new File(dir, "kotlin-stdlib.jar.tmp");
        copy(chosen, tmp);
        File target = installedStdlib(context);
        if (target.exists() && !target.delete()) throw new IOException("Cannot replace " + target);
        if (!tmp.renameTo(target)) throw new IOException("Cannot store matching Kotlin stdlib");
        FileOutputStream out = new FileOutputStream(versionFile(context));
        try { out.write((version + "\n").getBytes("UTF-8")); } finally { out.close(); }
    }

    /** Jars the installer downloaded (still cached after installation). */
    static List<File> cachedCompilerJars(Context context) {
        return MavenLibraries.classpath(new File(dir(context), "build/module"));
    }

    /** Folder on shared storage used by export. */
    static File exportFolder() {
        return new File(android.os.Environment.getExternalStorageDirectory(), "KotlinCompilerExport");
    }

    /** Copies the installed compiler DEX bundle, the stdlib and (if still cached) the source jars. */
    static String export(Context context) throws IOException { return export(context, true); }

    static String export(Context context, boolean includeJars) throws IOException {
        File compiler = compiler(context);
        if (!compiler.isFile()) throw new IOException("No Kotlin compiler is installed yet.");
        File out = exportFolder();
        if (!out.isDirectory() && !out.mkdirs()) throw new IOException("Cannot create " + out);
        StringBuilder report = new StringBuilder("Exported to " + out + "\n\n");
        copy(compiler, new File(out, compiler.getName()));
        report.append("• ").append(compiler.getName()).append(" (").append(compiler.length() >> 20).append(" MB)\n");
        File stdlib = stdlib(context);
        copy(stdlib, new File(out, "kotlin-stdlib.jar"));
        report.append("• kotlin-stdlib.jar").append(stdlibMatchesCompiler(context) ? "" : " (bundled 1.9.0 fallback)").append('\n');
        String version = version(context);
        if (version != null) {
            FileOutputStream v = new FileOutputStream(new File(out, "version.txt"));
            try { v.write((version + "\n").getBytes("UTF-8")); } finally { v.close(); }
            report.append("• version.txt (").append(version).append(")\n");
        }
        List<File> jars = includeJars ? cachedCompilerJars(context) : new java.util.ArrayList<File>();
        if (!includeJars) {
            report.append("• jars/ not included (use More… > Libraries… > Export Kotlin Compiler… for the full set)\n");
        } else if (!jars.isEmpty()) {
            File jarDir = new File(out, "jars");
            if (!jarDir.isDirectory() && !jarDir.mkdirs()) throw new IOException("Cannot create " + jarDir);
            long bytes = 0;
            for (File jar : jars) { copy(jar, new File(jarDir, jar.getName())); bytes += jar.length(); }
            report.append("• jars/ — ").append(jars.size()).append(" compiler source jars (").append(bytes >> 20).append(" MB)\n");
        } else {
            report.append("• jars/ skipped: the downloaded jars are no longer cached\n");
        }
        report.append("\nTo reuse: More… > Libraries… > Import Kotlin Compiler… and pick ").append(compiler.getName()).append('.');
        return report.toString();
    }

    /** One-line human readable state, used by the self-test report. */
    static String describe(Context context) {
        File c = compiler(context);
        StringBuilder sb = new StringBuilder();
        sb.append("Compiler: ").append(c.isFile() ? "installed (" + (c.length() >> 20) + " MB)" : "NOT installed");
        String v = version(context);
        sb.append("\nCompiler version: ").append(v == null ? "unknown" : v);
        sb.append("\nStdlib: ").append(stdlibMatchesCompiler(context) ? "matches compiler" : "bundled 1.9.0 fallback (may not match compiler)");
        return sb.toString();
    }

    static String manifestVersion(File jar) {
        ZipFile z = null;
        try {
            z = new ZipFile(jar);
            ZipEntry e = z.getEntry("META-INF/MANIFEST.MF");
            if (e == null) return null;
            String text = new String(readAll(z.getInputStream(e)), "UTF-8");
            for (String line : text.split("\\r?\\n"))
                if (line.startsWith("Implementation-Version:")) return line.substring(23).trim();
        } catch (IOException ignored) {
        } finally { if (z != null) try { z.close(); } catch (IOException ignored) { } }
        return null;
    }

    private static boolean hasEntry(File jar, String name) {
        ZipFile z = null;
        try { z = new ZipFile(jar); return z.getEntry(name) != null; }
        catch (IOException e) { return false; }
        finally { if (z != null) try { z.close(); } catch (IOException ignored) { } }
    }

    private static void copy(File from, File to) throws IOException {
        FileInputStream in = new FileInputStream(from);
        FileOutputStream out = new FileOutputStream(to);
        try { byte[] b = new byte[65536]; int n; while ((n = in.read(b)) != -1) out.write(b, 0, n); }
        finally { try { in.close(); } finally { out.close(); } }
    }

    static byte[] readAll(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[32768]; int n;
            while ((n = in.read(b)) != -1) out.write(b, 0, n);
            return out.toByteArray();
        } finally { in.close(); }
    }
}
