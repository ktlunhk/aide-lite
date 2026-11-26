package com.aideui;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Validates D8 program inputs and resolves a documented Kotlin stdlib split/merge conflict.
 *
 * Kotlin 1.8.0 merged kotlin-stdlib-jdk7 and kotlin-stdlib-jdk8 into kotlin-stdlib.
 * Maven POM-only resolution may accidentally mix 1.8+ stdlib with a 1.6.x JDK8
 * companion; D8 then fails because the same .class is in both JARs.
 *
 * Only D8's input list changes. The Maven cache, Gradle build file and editor
 * classpath are left untouched. No arbitrary duplicates are silently dropped.
 */
final class DexDuplicateGuard {
    private DexDuplicateGuard() { }

    static final int MAX_ARCHIVES = 1000;
    static final int MAX_CLASSES = 800000;

    static final class Result {
        final List<String> programs;
        final List<String> omitted;
        final String explanation;
        Result(List<String> sources, List<String> skipped, String reason) {
            programs = sources; omitted = skipped; explanation = reason;
        }
    }

    static final class KotlinJar {
        final String artifact;
        final int major, minor, patch;
        KotlinJar(String name, int ma, int mi, int pa) {
            artifact = name; major = ma; minor = mi; patch = pa;
        }
        boolean merged() { return major > 1 || major == 1 && minor >= 8; }
        int compare(KotlinJar other) {
            if (major != other.major) return major < other.major ? -1 : 1;
            if (minor != other.minor) return minor < other.minor ? -1 : 1;
            if (patch != other.patch) return patch < other.patch ? -1 : 1;
            return 0;
        }
    }

    static KotlinJar kotlin(File file) {
        String s = file.getName();
        if (!s.endsWith(".jar")) return null;
        if (s.startsWith("org.jetbrains.kotlin-")) s = s.substring("org.jetbrains.kotlin-".length());
        String[] variants = {"kotlin-stdlib-jdk8-", "kotlin-stdlib-jdk7-", "kotlin-stdlib-"};
        String[] names = {"kotlin-stdlib-jdk8", "kotlin-stdlib-jdk7", "kotlin-stdlib"};
        for (int i = 0; i < variants.length; i++) {
            if (!s.startsWith(variants[i])) continue;
            String version = s.substring(variants[i].length(), s.length() - 4);
            // Version coordinates must be numeric, with optional Maven suffixes.
            if (!version.matches("[0-9]+\\.[0-9]+(?:\\.[0-9]+)?(?:[-.][A-Za-z0-9.-]+)?")) return null;
            String[] n = version.split("[.-]");
            try {
                return new KotlinJar(names[i], Integer.parseInt(n[0]), Integer.parseInt(n[1]),
                        n.length > 2 && n[2].matches("[0-9]+") ? Integer.parseInt(n[2]) : 0);
            } catch (NumberFormatException badVersion) { return null; }
        }
        return null;
    }

    /**
     * Select the compatible D8 runtime libraries and reject any remaining duplicate classes.
     * Call after the ECJ compiled program JAR is generated, before invoking either D8 engine.
     */
    static Result resolve(File compiledProgramJar, List<String> libraries) throws IOException {
        List<String> selected = new ArrayList<String>();
        List<String> omitted = new ArrayList<String>();
        KotlinJar newest = null;
        File newestFile = null;
        for (String filename : libraries) {
            KotlinJar k = kotlin(new File(filename));
            if (k == null || !"kotlin-stdlib".equals(k.artifact)) continue;
            if (newest == null || k.compare(newest) > 0) {
                newest = k; newestFile = new File(filename);
            }
        }
        // Guava 27+ contains ListenableFuture itself; Gradle drops the standalone listenablefuture:1.0 stub.
        boolean guavaHasListenableFuture = false;
        for (String filename : libraries) {
            String n = new File(filename).getName();
            if (n.startsWith("com.google.guava-guava-") && !n.contains("-android-")) guavaHasListenableFuture = true;
            else if (n.startsWith("com.google.guava-guava-") && n.contains("-android")) guavaHasListenableFuture = true;
        }
        for (String filename : libraries) {
            File jar = new File(filename);
            if (guavaHasListenableFuture && jar.getName().startsWith("com.google.guava-listenablefuture-")) {
                omitted.add(jar.getName() + " (ListenableFuture is part of Guava)");
                continue;
            }
            KotlinJar k = kotlin(jar);
            if (k != null && newest != null) {
                if ("kotlin-stdlib".equals(k.artifact) && !jar.equals(newestFile)) {
                    omitted.add(jar.getName() + " (older Kotlin stdlib)");
                    continue;
                }
                if (newest.merged() && !"kotlin-stdlib".equals(k.artifact)
                        && k.compare(newest) <= 0) {
                    // Kotlin 1.8+ includes the content of earlier stdlib-jdk7/8 jars.
                    omitted.add(jar.getName() + " (merged into Kotlin stdlib 1.8+)");
                    continue;
                }
            }
            selected.add(filename);
        }
        List<String> notes = new ArrayList<String>();
        File work = compiledProgramJar != null && compiledProgramJar.getParentFile() != null
                ? new File(compiledProgramJar.getParentFile(), "dedup") : new File(System.getProperty("java.io.tmpdir"), "aideui-dedup");
        selected = dedupe(compiledProgramJar, selected, notes, work);
        String info = omitted.isEmpty() && notes.isEmpty()
                ? "D8 classpath checked: no duplicate program classes"
                : "D8 classpath checked" + (omitted.isEmpty() ? "" : "; excluded " + omitted.size()
                    + " redundant JAR(s): " + join(omitted))
                    + (notes.isEmpty() ? "" : "; " + join(notes));
        return new Result(selected, omitted, info);
    }

    static String join(List<String> strings) {
        StringBuilder out = new StringBuilder();
        for (String s : strings) { if (out.length() > 0) out.append(", "); out.append(s); }
        return out.toString();
    }

    /**
     * Library JARs can overlap (a fat jar in libs/ that bundles a library which Maven also supplies). The first
     * JAR in the list keeps the class; later JARs are copied without the classes already seen, so D8 never gets
     * the same type twice. A clash with the app's own compiled classes is still an error. Each change is reported.
     */
    private static List<String> dedupe(File compiledProgramJar, List<String> programs, List<String> notes, File work)
            throws IOException {
        if (programs.size() + 1 > MAX_ARCHIVES)
            throw new IOException("Too many D8 input archives to safely check for duplicate classes");
        Map<String, String> seen = new HashMap<String, String>();
        Set<String> programClasses = new HashSet<String>();
        int[] count = {0};
        if (compiledProgramJar != null) {
            inspect(compiledProgramJar, seen, count, null, null);
            programClasses.addAll(seen.keySet());
        }
        List<String> out = new ArrayList<String>();
        List<String> fullyCovered = new ArrayList<String>(), partial = new ArrayList<String>();
        int totalDropped = 0;
        Set<String> distinct = new HashSet<String>();
        for (String path : programs) {
            File jar = new File(path);
            String canonical = jar.getCanonicalPath();
            if (!distinct.add(canonical)) continue;
            Set<String> dropped = new java.util.TreeSet<String>();
            inspect(jar, seen, count, programClasses, dropped);
            if (dropped.isEmpty()) { out.add(path); continue; }
            if (!work.isDirectory() && !work.mkdirs()) throw new IOException("Cannot create " + work);
            File filtered = new File(work, jar.getName());
            int remaining = copyWithout(jar, filtered, dropped);
            totalDropped += dropped.size();
            if (remaining == 0) {
                // Everything in this JAR is already provided elsewhere: do not pass it to D8 at all.
                filtered.delete();
                fullyCovered.add(jar.getName());
            } else {
                out.add(filtered.getAbsolutePath());
                partial.add(jar.getName());
            }
        }
        if (totalDropped > 0) {
            List<String> names = new ArrayList<String>(fullyCovered);
            names.addAll(partial);
            notes.add("avoided " + totalDropped + " duplicate class(es) across " + names.size() + " JAR(s) ("
                + fullyCovered.size() + " left out entirely): "
                + join(names.size() > 8 ? names.subList(0, 8) : names) + (names.size() > 8 ? ", ..." : ""));
        }
        return out;
    }

    private static int copyWithout(File from, File to, Set<String> skip) throws IOException {
        int classes = 0;
        ZipFile zip = new ZipFile(from);
        java.util.zip.ZipOutputStream out = new java.util.zip.ZipOutputStream(
            new java.io.BufferedOutputStream(new java.io.FileOutputStream(to)));
        try {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            byte[] buf = new byte[16384];
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (skip.contains(entry.getName())) continue;
                if (entry.getName().endsWith(".class") && !entry.getName().startsWith("META-INF/")) classes++;
                out.putNextEntry(new ZipEntry(entry.getName()));
                if (!entry.isDirectory()) {
                    java.io.InputStream in = zip.getInputStream(entry);
                    try { int n; while ((n = in.read(buf)) > 0) out.write(buf, 0, n); }
                    finally { in.close(); }
                }
                out.closeEntry();
            }
        } finally { out.close(); zip.close(); }
        return classes;
    }

    /** Records class names; a name already seen goes into dropped (library clash) or is an error (app clash). */
    private static void inspect(File jar, Map<String, String> seen, int[] count, Set<String> programClasses,
                                Set<String> dropped) throws IOException {
        if (!jar.isFile()) throw new IOException("D8 input library is missing: " + jar);
        ZipFile zip = new ZipFile(jar);
        try {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || !name.endsWith(".class")
                        || name.startsWith("META-INF/") || name.equals("module-info.class")) continue;
                if (++count[0] > MAX_CLASSES)
                    throw new IOException("D8 classpath too large to validate for duplicate classes");
                String previous = seen.get(name);
                if (previous == null) { seen.put(name, jar.getName()); continue; }
                if (dropped == null || (programClasses != null && programClasses.contains(name)))
                    throw new IOException("Duplicate D8 class " + name.replace('/', '.')
                        + " in " + previous + " and " + jar.getName()
                        + ". The app's own classes clash with a library; remove or rename one of them.");
                dropped.add(name);
            }
        } finally { zip.close(); }
    }
}
