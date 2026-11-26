package com.aideui;

import java.io.File;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.List;

/**
 * Deterministic Gradle change detection for the v13 on-device auto-sync loop.
 * Has no Android dependencies so file and state logic can be tested on the host.
 * A fingerprint is written ONLY after the entire Maven classpath is published.
 */
public final class GradleAutoSync {
    private GradleAutoSync() { }

    private static final String SUCCESS_FILE = "last-successful-sync.txt";
    private static final String STATUS_FILE = "last-sync-status.txt";
    private static final String[] TRACKED = {
        "build.gradle", "build.gradle.kts", "gradle.properties", "kotlin.properties"
    };

    private static void hashFile(MessageDigest sha, File f) throws IOException {
        sha.update(f.getAbsolutePath().getBytes("UTF-8"));
        sha.update((byte)0);
        if (!f.isFile()) { sha.update((byte)0); return; }
        byte[] bytes = MavenLibraries.readLimited(f, 2L * 1024 * 1024);
        sha.update(bytes);
    }

    /** Includes module, root build/repositories and settings inputs. */
    public static String fingerprint(File module) throws IOException {
        if (module == null) throw new IOException("No Gradle module selected");
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            for (String n : TRACKED) hashFile(sha, new File(module, n));
            File root = module.getParentFile();
            if (root != null) {
                for (String n : TRACKED) hashFile(sha, new File(root, n));
                hashFile(sha, new File(root, "settings.gradle"));
                hashFile(sha, new File(root, "settings.gradle.kts"));
                hashFile(sha, new File(root, "gradle/libs.versions.toml"));
            }
            byte[] bytes = sha.digest();
            StringBuilder s = new StringBuilder();
            for (byte v : bytes) {
                int b = v & 255;
                if (b < 16) s.append('0');
                s.append(Integer.toHexString(b));
            }
            return s.toString();
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IOException("SHA-256 unavailable", ex);
        }
    }

    public static boolean needsSync(File module) throws IOException {
        File gradle = MavenLibraries.gradleFile(module);
        if (!gradle.isFile()) return false;
        String previous = successFingerprint(module);
        if (!fingerprint(module).equals(previous)) return true;
        if (!MavenLibraries.index(module).isFile()) return true;
        List<MavenLibraries.Coordinate> deps = MavenLibraries.dependencies(module);
        return !deps.isEmpty() && MavenLibraries.classpath(module).isEmpty();
    }

    public static String successFingerprint(File module) {
        File stamp = new File(MavenLibraries.store(module), SUCCESS_FILE);
        if (!stamp.isFile()) return "";
        try { return MavenLibraries.readText(stamp).trim(); }
        catch (IOException ex) { return ""; }
    }

    /** Mark a successful sync only if the Gradle files did not change during sync. */
    public static boolean markSuccess(File module, String startedFingerprint) throws IOException {
        if (!fingerprint(module).equals(startedFingerprint)) return false;
        MavenLibraries.safeWrite(new File(MavenLibraries.store(module), SUCCESS_FILE),
            (startedFingerprint + "\n").getBytes("UTF-8"));
        return true;
    }

    public static void saveStatus(File module, String status) throws IOException {
        if (status == null) status = "";
        if (status.length() > 1200) status = status.substring(0, 1200);
        MavenLibraries.safeWrite(new File(MavenLibraries.store(module), STATUS_FILE),
            (status + "\n").getBytes("UTF-8"));
    }

    public static String status(File module) {
        File f = new File(MavenLibraries.store(module), STATUS_FILE);
        try { return f.isFile() ? MavenLibraries.readText(f).trim() : "Not synced yet"; }
        catch (IOException ex) { return "Status unavailable: " + ex.getMessage(); }
    }
}
