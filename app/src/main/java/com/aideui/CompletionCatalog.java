package com.aideui;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Non-UI class/package index for editor completions.
 *
 * Build this off the main thread. Package completion reads only an immutable,
 * volatile snapshot and never opens a JAR during a keystroke. The Android SDK
 * stub JAR is an authoritative list of SDK classes, not a full Java language
 * server: this does not resolve methods, overloads, or types of local variables.
 */
public final class CompletionCatalog {
    private CompletionCatalog() { }

    private static volatile Set<String> fullNames = Collections.emptySet();
    private static final int MAX_CLASSES = 35000;
    private static final int MAX_ARCHIVE_ENTRIES = 100000;

    /** Take an already-open platform JAR; callers own and close the stream. */
    public static int load(InputStream androidJar, List<File> libraries) throws IOException {
        Set<String> result = new HashSet<String>();
        scan(androidJar, result);
        if (libraries != null) {
            for (File file : libraries) {
                if (file == null || !file.isFile() || !file.getName().endsWith(".jar")) continue;
                InputStream in = null;
                try {
                    in = new FileInputStream(file);
                    scan(in, result);
                } catch (IOException ignored) {
                    // One corrupt optional library should not hide valid SDK classes.
                } finally {
                    if (in != null) try { in.close(); } catch (IOException ignored) { }
                }
            }
        }
        fullNames = Collections.unmodifiableSet(result);
        return result.size();
    }

    static void scan(InputStream archive, Set<String> output) throws IOException {
        if (archive == null) return;
        ZipInputStream zin = new ZipInputStream(archive);
        int visited = 0;
        // Closing the ZipInputStream also closes the supplied archive stream.
        try {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null && visited++ < MAX_ARCHIVE_ENTRIES) {
                if (output.size() >= MAX_CLASSES) break;
                if (entry.isDirectory()) continue;
                String path = entry.getName();
                if (!path.endsWith(".class") || path.indexOf('$') >= 0 || path.startsWith("META-INF/")) continue;
                // Package prefixes and simple class names are ordinary Java identifiers.
                String withoutExtension = path.substring(0, path.length() - 6);
                if (withoutExtension.indexOf('/') <= 0) continue;
                String qname = withoutExtension.replace('/', '.');
                if (!validName(qname)) continue;
                output.add(qname);
                zin.closeEntry();
            }
        } finally { zin.close(); }
    }

    static boolean validName(String name) {
        if (name.length() > 180 || name.indexOf('.') < 1) return false;
        String[] parts = name.split("\\.");
        if (parts.length < 2) return false;
        for (String p : parts) {
            if (p.length() == 0 || !Character.isJavaIdentifierStart(p.charAt(0))) return false;
            for (int i = 1; i < p.length(); i++)
                if (!Character.isJavaIdentifierPart(p.charAt(i))) return false;
        }
        return true;
    }

    /** Returns direct children only, e.g. java. => io/lang/util, java.util. => ArrayList/List/Map. */
    public static List<String> children(String entered) {
        if (entered == null || entered.length() > 160) return Collections.emptyList();
        int lastDot = entered.lastIndexOf('.');
        if (lastDot < 0) return Collections.emptyList();
        String base = entered.substring(0, lastDot + 1);
        String fragment = entered.substring(lastDot + 1);
        TreeSet<String> matches = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
        for (String name : fullNames) {
            if (!name.startsWith(base) || name.length() <= base.length()) continue;
            String rest = name.substring(base.length());
            int next = rest.indexOf('.');
            String child = next < 0 ? rest : rest.substring(0, next);
            if (child.length() > 0 && child.startsWith(fragment)) matches.add(child);
        }
        return new ArrayList<String>(matches);
    }

    /** Imported class names, cached in memory; no filesystem access per keypress. */
    public static List<String> importedNames(String source) { return importedNames(source, false); }

    private static final String[] KOTLIN_DEFAULT_IMPORTS = { "kotlin.", "kotlin.annotation.", "kotlin.collections.",
        "kotlin.comparisons.", "kotlin.io.", "kotlin.jvm.", "kotlin.ranges.", "kotlin.sequences.", "kotlin.text." };

    public static List<String> importedNames(String source, boolean kotlin) {
        if (source == null || source.length() > 250000) return Collections.emptyList();
        Pattern imports = Pattern.compile("(?m)^\\s*import\\s+(?!static\\s)([A-Za-z_$][A-Za-z0-9_.$*]+)[ \\t]*;?");
        Matcher m = imports.matcher(source);
        Set<String> direct = new HashSet<String>();
        Set<String> wildcard = new HashSet<String>();
        while (m.find()) {
            String name = m.group(1);
            if (name.endsWith(".*")) wildcard.add(name.substring(0, name.length() - 1));
            else direct.add(name);
        }
        wildcard.add("java.lang.");
        if (kotlin) for (String pkg : KOTLIN_DEFAULT_IMPORTS) wildcard.add(pkg);
        TreeSet<String> found = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
        for (String name : fullNames) {
            boolean include = direct.contains(name);
            if (!include) for (String pkg : wildcard) {
                if (name.startsWith(pkg) && name.substring(pkg.length()).indexOf('.') < 0) {
                    include = true; break;
                }
            }
            if (include) found.add(name.substring(name.lastIndexOf('.') + 1));
            if (found.size() >= 750) break;
        }
        return new ArrayList<String>(found);
    }

    public static boolean isReady() { return !fullNames.isEmpty(); }
}
