package com.aideui;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Dexes big dependency sets without running out of memory.
 *
 * One D8 run over every library keeps the intermediate code of all classes in the 512 MB app heap at the
 * same time; a Compose app (or one using jadx-sized libraries) does not fit and the process dies with an
 * OutOfMemoryError. Instead, like Gradle's incremental dexing, this class
 *   1. dexes the app's own classes on their own (libraries are only a desugaring classpath),
 *   2. dexes the libraries in small groups, each group in its own D8 run, and
 *   3. caches every group's DEX output by the content of its jars, so a library is dexed once and later
 *      builds only re-dex the app.
 * The APK then carries the app DEX as classes.dex and each library DEX as classes2.dex, classes3.dex, ...
 * (native multidex, which every supported API level 21+ has).
 */
final class IncrementalDex {
    private IncrementalDex() { }

    /** Below this total jar size one D8 run is fast and safe, so the simple path is kept. */
    static final long MIN_TOTAL_BYTES = 6L * 1024 * 1024;
    /** Jar bytes per D8 run; class files expand a lot inside D8, so keep each run small. */
    static final long GROUP_BYTES = 3L * 1024 * 1024;
    static final long CACHE_LIMIT_BYTES = 400L * 1024 * 1024;
    /** Bump when the way DEX files are produced changes, so old cache entries are not reused. */
    static final int FORMAT = 1;
    private static final Pattern DEX_NAME = Pattern.compile("classes(\\d*)\\.dex");

    static boolean worthIt(List<String> libraries) {
        long total = 0;
        for (String library : libraries) total += new File(library).length();
        return total >= MIN_TOTAL_BYTES;
    }

    static void run(File cacheRoot, ClassLoader loader, File programJar, File platform, List<String> libraries,
                    File output, int minApi, ProjectBuild.Progress progress) throws Exception {
        List<File> libs = new ArrayList<File>();
        for (String library : libraries) libs.add(new File(library));
        File cache = new File(cacheRoot, "lib-dex/api" + minApi);
        if (!cache.isDirectory() && !cache.mkdirs()) throw new IOException("Cannot create " + cache);
        List<List<File>> groups = group(libs);

        File work = new File(output.getParentFile(), "dex-app");
        deleteTree(work);
        if (!work.mkdirs()) throw new IOException("Cannot create " + work);
        StandaloneApkBuild.stage(progress, "Dexing app classes...", 58);
        File[] appDex = null;
        try {
            StandaloneApkBuild.runD8(loader, Collections.singletonList(programJar), libs, platform, work, minApi);
            appDex = dexFiles(work);
        } catch (Throwable failure) { throw explain(failure, "the app classes"); }
        if (appDex.length == 0) throw new IOException("D8 produced no DEX for the app classes");

        List<File> libraryDex = new ArrayList<File>();
        for (int i = 0; i < groups.size(); i++) {
            List<File> group = groups.get(i);
            File entry = new File(cache, key(group, minApi));
            if (!entry.isDirectory()) {
                StandaloneApkBuild.stage(progress, "Dexing libraries " + (i + 1) + "/" + groups.size() + " ("
                    + describe(group) + ")...", 58 + (20 * (i + 1)) / (groups.size() + 1));
                File temp = new File(cache, entry.getName() + ".tmp" + System.nanoTime());
                if (!temp.mkdirs()) throw new IOException("Cannot create " + temp);
                List<File> classpath = new ArrayList<File>(libs);
                classpath.removeAll(group);
                try {
                    StandaloneApkBuild.runD8(loader, group, classpath, platform, temp, minApi);
                } catch (Throwable failure) {
                    deleteTree(temp);
                    throw explain(failure, describe(group));
                }
                if (!temp.renameTo(entry)) {
                    deleteTree(temp);              // another build finished this entry first
                    if (!entry.isDirectory()) throw new IOException("Cannot store dexed libraries in " + entry);
                }
            }
            entry.setLastModified(System.currentTimeMillis());
            libraryDex.addAll(Arrays.asList(dexFiles(entry)));
        }

        StandaloneApkBuild.cleanDexOutput(output);
        int number = 1;
        for (File dex : appDex) copy(dex, new File(output, name(number++)));
        for (File dex : libraryDex) copy(dex, new File(output, name(number++)));
        prune(cache);
    }

    private static String name(int number) { return number == 1 ? "classes.dex" : "classes" + number + ".dex"; }

    /** Consecutive jars up to GROUP_BYTES each; a jar larger than that is a group of its own. */
    static List<List<File>> group(List<File> libs) {
        List<List<File>> groups = new ArrayList<List<File>>();
        List<File> current = new ArrayList<File>();
        long size = 0;
        for (File lib : libs) {
            if (!current.isEmpty() && size + lib.length() > GROUP_BYTES) {
                groups.add(current); current = new ArrayList<File>(); size = 0;
            }
            current.add(lib); size += lib.length();
        }
        if (!current.isEmpty()) groups.add(current);
        return groups;
    }

    private static String describe(List<File> group) {
        String first = group.get(0).getName();
        if (first.endsWith(".jar")) first = first.substring(0, first.length() - 4);
        return group.size() == 1 ? first : first + " +" + (group.size() - 1);
    }

    /** Cache key from the jars' names, sizes and entry checksums (no decompression), plus format and API level. */
    static String key(List<File> group, int minApi) throws IOException {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-1");
            sha.update(("v" + FORMAT + ":" + minApi + ":").getBytes("UTF-8"));
            for (File jar : group) {
                sha.update((jar.getName() + ":" + jar.length() + ":").getBytes("UTF-8"));
                long h = 1125899906842597L;
                ZipFile zip = new ZipFile(jar);
                try {
                    Enumeration<? extends ZipEntry> entries = zip.entries();
                    while (entries.hasMoreElements()) {
                        ZipEntry e = entries.nextElement();
                        h = 31 * h + e.getName().hashCode();
                        h = 31 * h + e.getCrc();
                        h = 31 * h + e.getSize();
                    }
                } finally { zip.close(); }
                sha.update(Long.toHexString(h).getBytes("UTF-8"));
            }
            StringBuilder out = new StringBuilder();
            for (byte b : sha.digest()) out.append(String.format("%02x", b & 255));
            return out.toString();
        } catch (java.security.NoSuchAlgorithmException e) { throw new IOException("SHA-1 unavailable", e); }
    }

    private static IOException explain(Throwable failure, String what) {
        Throwable cause = StandaloneApkBuild.unwrap(failure);
        // D8 reports failures of its worker threads wrapped; an OutOfMemoryError may sit deeper in the chain.
        for (Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause())
            if (t instanceof OutOfMemoryError) { cause = t; break; }
        if (cause instanceof OutOfMemoryError)
            return new IOException("Out of memory while dexing " + what + ". Close other apps and build again "
                + "(already dexed libraries are cached, so each retry gets further).", cause);
        if (cause instanceof IOException) return (IOException) cause;
        if (cause instanceof NoSuchMethodException || cause instanceof NoSuchMethodError) return new NoDexApi(cause);
        return new IOException("D8 failed on " + what + ": " + StandaloneApkBuild.summarize(cause), cause);
    }

    /** The bundled D8 lacks the API this path needs; the caller falls back to one single D8 run. */
    static final class NoDexApi extends IOException {
        NoDexApi(Throwable cause) { super("D8 builder lacks addClasspathFiles", cause); }
    }

    static File[] dexFiles(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return new File[0];
        List<File> dex = new ArrayList<File>();
        for (File f : files) if (f.isFile() && DEX_NAME.matcher(f.getName()).matches()) dex.add(f);
        Collections.sort(dex, new Comparator<File>() {
            public int compare(File a, File b) { return Integer.compare(index(a), index(b)); }
        });
        return dex.toArray(new File[0]);
    }

    private static int index(File dex) {
        Matcher m = DEX_NAME.matcher(dex.getName());
        if (!m.matches()) return Integer.MAX_VALUE;
        return m.group(1).length() == 0 ? 1 : Integer.parseInt(m.group(1));
    }

    private static void copy(File from, File to) throws IOException {
        FileInputStream in = new FileInputStream(from);
        try {
            FileOutputStream out = new FileOutputStream(to);
            try {
                byte[] buffer = new byte[65536];
                int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            } finally { out.close(); }
        } finally { in.close(); }
    }

    /** Drops the least recently used entries once the cache is over its limit. */
    static void prune(File cache) {
        File[] entries = cache.listFiles();
        if (entries == null) return;
        long total = 0;
        for (File e : entries) total += size(e);
        if (total <= CACHE_LIMIT_BYTES) return;
        Arrays.sort(entries, new Comparator<File>() {
            public int compare(File a, File b) { return a.lastModified() < b.lastModified() ? -1 : a.lastModified() > b.lastModified() ? 1 : 0; }
        });
        for (File e : entries) {
            if (total <= CACHE_LIMIT_BYTES * 3 / 4) break;
            total -= size(e);
            deleteTree(e);
        }
    }

    private static long size(File f) {
        if (f.isFile()) return f.length();
        long total = 0;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) total += size(k);
        return total;
    }

    static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }
}
