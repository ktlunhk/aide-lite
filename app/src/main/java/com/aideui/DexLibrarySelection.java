package com.aideui;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Avoid feeding unused cached Maven dependencies into D8. Imports are not
 * retained in .class files, so a Java-only application with an unused AndroidX
 * import should not need Kotlin stdlib (or any other unused Maven JAR) in DEX.
 *
 * Deliberately conservative: on unknown bytecode, reflection, manifest-held
 * library components, or any directly referenced cached class, we retain the
 * complete Maven classpath rather than generate an APK with missing code.
 * This is NOT a replacement for full Gradle runtime dependency resolution.
 */
final class DexLibrarySelection {
    private DexLibrarySelection() { }
    private static final int MAX_CLASSES = 5000;
    private static final long MAX_CLASS_BYTES = 4L * 1024 * 1024;
    private static final Pattern DESCRIPTOR = Pattern.compile("L([A-Za-z0-9_/$]+)(?:<|;)");
    private static final Pattern JAVA_NAME = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)+");

    static final class Choice {
        final List<String> programs;
        final int skipped;
        final String explanation;
        Choice(List<String> p, int s, String message) {
            programs = p; skipped = s; explanation = message;
        }
    }

    static Choice choose(File compiledClasses, File module, List<String> allLibraries) {
        List<String> original = new ArrayList<String>(allLibraries);
        try {
            List<File> cached = MavenLibraries.classpath(module);
            if (cached.isEmpty()) return new Choice(original, 0, "No Maven cache to filter");
            Set<String> cachedPaths = new HashSet<String>();
            for (File item : cached) cachedPaths.add(item.getCanonicalPath());
            int managedCount = 0;
            for (String lib : allLibraries) {
                if (cachedPaths.contains(new File(lib).getCanonicalPath())) managedCount++;
                else return new Choice(original, 0, "Keeping Maven libraries due to explicit local JAR dependencies");
            }
            if (managedCount == 0) return new Choice(original, 0, "No cached runtime libraries");

            Set<String> refs = new HashSet<String>();
            int[] count = {0};
            boolean[] dynamic = {false};
            collectClasses(compiledClasses, refs, count, dynamic);
            if (count[0] == 0 || dynamic[0])
                return new Choice(original, 0, "Keeping Maven libraries (dynamic loading or no class analysis)");
            collectManifestNames(new File(module, "src/main/AndroidManifest.xml"), refs);
            // If any cached class is referenced, retain the full dependency graph.
            // This errs on the side of runtime correctness, rather than silently
            // omitting a Kotlin or AndroidX transitive dependency.
            for (File jar : cached) {
                ZipFile zip = new ZipFile(jar);
                try {
                    java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
                    while (entries.hasMoreElements()) {
                        ZipEntry entry = entries.nextElement();
                        if (!entry.isDirectory() && entry.getName().endsWith(".class")
                            && refs.contains(entry.getName().substring(0, entry.getName().length() - 6))) {
                            return new Choice(original, 0, "Keeping Maven libraries: referenced class " + entry.getName());
                        }
                    }
                } finally { zip.close(); }
            }
            ArrayList<String> filtered = new ArrayList<String>();
            int skipped = 0;
            for (String lib : allLibraries) {
                if (cachedPaths.contains(new File(lib).getCanonicalPath())) skipped++;
                else filtered.add(lib);
            }
            return new Choice(filtered, skipped,
                "Skipping " + skipped + " unused cached Maven JARs during D8 (still available to ECJ)");
        } catch (Exception uncertain) {
            return new Choice(original, 0, "Could not prove Maven libraries unused; keeping them: "
                    + uncertain.getClass().getSimpleName());
        }
    }

    private static void collectClasses(File f, Set<String> refs, int[] count, boolean[] dynamic) throws Exception {
        if (f.isDirectory()) {
            File[] files = f.listFiles();
            if (files == null) throw new IOException("Unreadable compiled class directory");
            for (File child : files) collectClasses(child, refs, count, dynamic);
            return;
        }
        if (!f.isFile() || !f.getName().endsWith(".class")) return;
        if (++count[0] > MAX_CLASSES || f.length() > MAX_CLASS_BYTES)
            throw new IOException("Too many or oversized class files for safe library pruning");
        DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f)));
        try {
            if (in.readInt() != 0xCAFEBABE) throw new IOException("Not a Java class file: " + f);
            in.readUnsignedShort(); // minor
            in.readUnsignedShort(); // major
            int entries = in.readUnsignedShort();
            String[] utf = new String[entries];
            int[] classIndex = new int[entries];
            for (int i = 1; i < entries; i++) {
                int tag = in.readUnsignedByte();
                switch (tag) {
                    case 1: utf[i] = in.readUTF(); break;
                    case 3: case 4: in.readInt(); break;
                    case 5: case 6: in.readLong(); i++; break;
                    case 7: classIndex[i] = in.readUnsignedShort(); break;
                    case 8: case 16: case 19: case 20: in.readUnsignedShort(); break;
                    case 9: case 10: case 11: case 12: case 17: case 18:
                        in.readUnsignedShort(); in.readUnsignedShort(); break;
                    case 15: in.readUnsignedByte(); in.readUnsignedShort(); break;
                    default: throw new IOException("Unsupported Java class constant pool entry " + tag);
                }
            }
            for (int i = 1; i < entries; i++) {
                if (classIndex[i] > 0 && classIndex[i] < entries) addType(utf[classIndex[i]], refs);
                if (utf[i] == null) continue;
                String value = utf[i];
                Matcher matcher = DESCRIPTOR.matcher(value);
                while (matcher.find()) addType(matcher.group(1), refs);
                if (value.equals("forName") || value.equals("loadClass") || value.equals("java/util/ServiceLoader"))
                    dynamic[0] = true;
                if (JAVA_NAME.matcher(value).matches()) addType(value.replace('.', '/'), refs);
            }
        } finally { in.close(); }
    }

    private static void addType(String type, Set<String> refs) {
        if (type == null) return;
        String t = type;
        while (t.startsWith("[")) t = t.substring(1);
        if (t.startsWith("L") && t.endsWith(";")) t = t.substring(1, t.length() - 1);
        if (t.length() > 2 && t.indexOf('/') > 0 && t.indexOf(' ') < 0) refs.add(t);
    }

    private static void collectManifestNames(File manifest, Set<String> refs) throws Exception {
        if (!manifest.isFile()) return;
        if (manifest.length() > 1024 * 1024) throw new IOException("Oversized manifest");
        String xml = MavenLibraries.readText(manifest);
        Matcher matcher = Pattern.compile("android:name\\s*=\\s*['\\\"]([^'\\\"]+)['\\\"]").matcher(xml);
        while (matcher.find()) {
            String name = matcher.group(1);
            if (JAVA_NAME.matcher(name).matches()) refs.add(name.replace('.', '/'));
        }
    }
}
