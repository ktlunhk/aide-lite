package com.aideui;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Converts the Android-ready DEX archive supplied inside the user's ZeroAicy AIDE APK
 * into one classes.dex-only JAR per dex file for Android's DexClassLoader.
 * This includes R8/D8 build-tool bytecode, NOT original AIDE UI/application code.
 */
public final class R8DexBundle {
    private R8DexBundle() { }
    public static final String SHA256 = "cb2f0927efae6b7ea8da23c9c18613b8cad11accd8602542e0159d652177e27d";
    private static final int[] ORDER = {2, 4, 3, 1};
    private static final long MAX_DEX_BYTES = 20L * 1024 * 1024;
    private static final long MAX_RESOURCE_BYTES = 10L * 1024 * 1024;

    /** The versioned output files are treated as immutable and loaded only from app-private storage. */
    public static List<File> prepare(File archive, File directory) throws Exception {
        if (!directory.isDirectory() && !directory.mkdirs())
            throw new IOException("Cannot create R8 code cache: " + directory);
        if (!SHA256.equals(sha256(archive)))
            throw new IOException("R8 bundle differs from the AIDE APK that was inspected. Reinstall AideUI.");
        List<File> result = new ArrayList<File>();
        for (int part : ORDER) result.add(new File(directory, "aideui-r8-" + part + "-cb2f0927.jar"));
        boolean ready = true;
        for (int i = 0; i < result.size(); i++) {
            if (!validJar(result.get(i)) || (i == 0 && !hasEntry(result.get(i), "resources/new_api_database.ser"))) {
                ready = false; break;
            }
        }
        if (ready) return result;

        ZipFile src = new ZipFile(archive);
        try {
            for (int i = 0; i < ORDER.length; i++) {
                int part = ORDER[i];
                String name = part == 1 ? "classes.dex" : "classes" + part + ".dex";
                ZipEntry dex = src.getEntry(name);
                if (dex == null || dex.isDirectory() || dex.getSize() > MAX_DEX_BYTES)
                    throw new IOException("Missing or oversized " + name + " in AIDE R8 bundle");
                File finalJar = result.get(i);
                File temp = new File(directory, finalJar.getName() + ".tmp");
                if (temp.exists() && !temp.delete()) throw new IOException("Cannot remove " + temp);
                ZipOutputStream output = new ZipOutputStream(new FileOutputStream(temp));
                boolean completed = false;
                try {
                    addEntry(src, dex, output, "classes.dex", MAX_DEX_BYTES);
                    // D8 uses runtime lookup of a small compatibility API database.
                    if (part == 2) {
                        java.util.Enumeration<? extends ZipEntry> all = src.entries();
                        while (all.hasMoreElements()) {
                            ZipEntry entry = all.nextElement();
                            String resource = entry.getName();
                            if (entry.isDirectory() || resource.equals("classes.dex")
                                    || resource.matches("classes[0-9]+\\.dex")) continue;
                            if (resource.startsWith("/") || resource.contains("..") || resource.indexOf('\\') >= 0)
                                throw new IOException("Unexpected R8 resource path: " + resource);
                            addEntry(src, entry, output, resource, MAX_RESOURCE_BYTES);
                        }
                    }
                    completed = true;
                } finally {
                    output.close();
                    if (!completed) temp.delete();
                }
                if (!temp.setReadOnly()) throw new IOException("Could not mark D8 code archive read-only");
                if (finalJar.exists() && !finalJar.delete()) throw new IOException("Cannot replace " + finalJar);
                if (!temp.renameTo(finalJar)) throw new IOException("Cannot finalize " + finalJar);
            }
        } finally { src.close(); }
        for (File file : result) if (!validJar(file)) throw new IOException("Invalid R8 code cache: " + file);
        return result;
    }

    static boolean hasEntry(File file, String name) {
        ZipFile zip = null;
        try { zip = new ZipFile(file); return zip.getEntry(name) != null; }
        catch (Exception e) { return false; }
        finally { if (zip != null) try { zip.close(); } catch (Exception ignored) { } }
    }

    static boolean validJar(File file) {
        if (!file.isFile() || file.length() < 100) return false;
        ZipFile zip = null;
        try { zip = new ZipFile(file); return zip.getEntry("classes.dex") != null; }
        catch (Exception e) { return false; }
        finally { if (zip != null) try { zip.close(); } catch (Exception ignored) { } }
    }

    static void addEntry(ZipFile src, ZipEntry entry, ZipOutputStream out, String name, long cap)
            throws Exception {
        ZipEntry dst = new ZipEntry(name);
        out.putNextEntry(dst);
        InputStream in = src.getInputStream(entry);
        long total = 0;
        try {
            byte[] data = new byte[32768]; int n;
            while ((n = in.read(data)) != -1) {
                total += n;
                if (total > cap) throw new IOException("R8 archive entry too large: " + name);
                out.write(data, 0, n);
            }
        } finally { in.close(); out.closeEntry(); }
    }

    public static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        FileInputStream input = new FileInputStream(file);
        try {
            byte[] data = new byte[65536]; int n;
            while ((n = input.read(data)) != -1) digest.update(data, 0, n);
        } finally { input.close(); }
        StringBuilder out = new StringBuilder();
        for (byte b : digest.digest()) out.append(String.format(java.util.Locale.US, "%02x", b & 255));
        return out.toString();
    }
}
