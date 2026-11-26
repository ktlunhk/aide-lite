package com.aideui;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Lets the standalone builder use resources shipped inside AAR libraries (Material, AppCompat,
 * Compose, ...). Gradle normally merges these; here we
 *   1. find the AAR behind every library that MavenLibraries synced,
 *   2. unpack its res/, R.txt and manifest package into the build scratch folder,
 *   3. (in StandaloneApkBuild) compile each res/ with AAPT2 and link it with the app, and
 *   4. generate a "<library package>.R" class for every AAR, because library bytecode
 *      refers to those classes and the AARs do not contain them.
 *
 * Priority follows Gradle: the app overrides libraries, and direct dependencies override
 * transitive ones. Not handled: AAR manifest merging (providers, permissions, activities).
 */
final class AarResources {
    static final long MAX_ENTRY = 40L * 1024 * 1024;
    static final Pattern PACKAGE = Pattern.compile("<manifest\\b[^>]*?\\bpackage\\s*=\\s*\"([A-Za-z_][A-Za-z0-9_.]*)\"",
            Pattern.DOTALL);

    static final class Aar {
        final String name;
        final File resDir;   // null when the AAR has no resources
        final File rTxt;     // null when missing
        final String packageName;
        Aar(String name, File resDir, File rTxt, String packageName) {
            this.name = name; this.resDir = resDir; this.rTxt = rTxt; this.packageName = packageName;
        }
    }

    private AarResources() { }

    /** AARs of the synced classpath, lowest priority first (transitive libraries before direct ones). */
    static List<Aar> extract(File module, File scratch) throws IOException {
        java.util.LinkedHashSet<String> found = new java.util.LinkedHashSet<String>();
        File aarIndex = MavenLibraries.aarIndex(module);
        if (aarIndex.isFile())
            for (String line : MavenLibraries.readText(aarIndex).split("\\r?\\n"))
                if (!line.trim().isEmpty()) found.add(line.trim());
        for (File jar : MavenLibraries.classpath(module)) {
            String n = jar.getName();
            if (n.endsWith("-classes.jar")) found.add(n.substring(0, n.length() - "-classes.jar".length()));
        }
        List<String> prefixes = new ArrayList<String>(found);
        List<Aar> out = new ArrayList<Aar>();
        if (prefixes.isEmpty()) return out;
        Map<String, File> byPrefix = indexAars(new File(MavenLibraries.store(module), "repository"));
        File root = new File(scratch, "aar-res");
        for (int i = prefixes.size() - 1; i >= 0; i--) {
            String prefix = prefixes.get(i);
            File aar = byPrefix.get(prefix);
            if (aar == null) continue;
            Aar unpacked = unpack(aar, prefix, new File(root, prefix));
            if (unpacked != null) out.add(unpacked);
        }
        return out;
    }

    /** repository/<group path>/<artifact>/<version>/<artifact>-<version>.aar -> "group-artifact-version". */
    static Map<String, File> indexAars(File repository) {
        Map<String, File> map = new HashMap<String, File>();
        walk(repository, repository, map, 0);
        return map;
    }

    private static void walk(File base, File dir, Map<String, File> map, int depth) {
        File[] children = dir.listFiles();
        if (children == null || depth > 16) return;
        for (File f : children) {
            if (f.isDirectory()) { walk(base, f, map, depth + 1); continue; }
            if (!f.getName().endsWith(".aar")) continue;
            File version = f.getParentFile(), artifact = version.getParentFile();
            if (artifact == null) continue;
            String rel = base.toURI().relativize(artifact.getParentFile().toURI()).getPath();
            if (rel.endsWith("/")) rel = rel.substring(0, rel.length() - 1);
            if (rel.isEmpty()) continue;
            String group = rel.replace('/', '.');
            map.put(group + "-" + artifact.getName() + "-" + version.getName(), f);
        }
    }

    static Aar unpack(File aar, String prefix, File dest) throws IOException {
        ZipFile zip = new ZipFile(aar);
        try {
            String pkg = null;
            File rTxt = null;
            boolean hasRes = false;
            String canonicalDest = dest.getCanonicalPath() + File.separator;
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                String name = e.getName();
                if (e.isDirectory()) continue;
                boolean res = name.startsWith("res/");
                if (!res && !"R.txt".equals(name) && !"AndroidManifest.xml".equals(name)) continue;
                if (e.getSize() > MAX_ENTRY) throw new IOException("Oversize entry " + name + " in " + aar.getName());
                File target = new File(dest, name);
                if (!target.getCanonicalPath().startsWith(canonicalDest)) continue; // zip-slip guard
                InputStream in = zip.getInputStream(e);
                try { MavenLibraries.safeWriteStream(target, in, MAX_ENTRY); }
                finally { in.close(); }
                if (res) hasRes = true;
                if ("R.txt".equals(name)) rTxt = target;
                if ("AndroidManifest.xml".equals(name)) {
                    Matcher m = PACKAGE.matcher(MavenLibraries.readText(target));
                    if (m.find()) pkg = m.group(1);
                }
            }
            return new Aar(prefix, hasRes ? new File(dest, "res") : null, rTxt, pkg);
        } finally { zip.close(); }
    }

    /** Library package names that need their own R class (distinct from the app's). */
    static List<String> libraryPackages(List<Aar> aars, String appPackage) {
        Set<String> seen = new HashSet<String>();
        List<String> out = new ArrayList<String>();
        for (Aar a : aars)
            if (a.packageName != null && a.rTxt != null && !a.packageName.equals(appPackage) && seen.add(a.packageName))
                out.add(a.packageName);
        return out;
    }

    /**
     * Writes "<pkg>/R.java" for each library: only the symbols listed in that AAR's R.txt, with the
     * final ids AAPT2 assigned to the app's R. Several AARs may share a package; their symbols are united.
     */
    static int generateLibraryR(List<Aar> aars, File appR, File generated, String appPackage) throws IOException {
        if (appR == null || !appR.isFile()) return 0;
        // type -> (name -> declaration text) taken from the app's own R.java
        Map<String, Map<String, String>> appSymbols = parseR(MavenLibraries.readLargeText(appR));
        // package -> type -> names
        Map<String, Map<String, Set<String>>> wanted = new LinkedHashMap<String, Map<String, Set<String>>>();
        for (Aar a : aars) {
            if (a.packageName == null || a.rTxt == null || a.packageName.equals(appPackage)) continue;
            Map<String, Set<String>> types = wanted.get(a.packageName);
            if (types == null) { types = new LinkedHashMap<String, Set<String>>(); wanted.put(a.packageName, types); }
            for (String line : MavenLibraries.readText(a.rTxt).split("\\r?\\n")) {
                String[] p = line.trim().split("\\s+");
                if (p.length < 3 || !(p[0].equals("int") || p[0].equals("int[]"))) continue;
                Set<String> names = types.get(p[1]);
                if (names == null) { names = new java.util.TreeSet<String>(); types.put(p[1], names); }
                names.add(p[2]);
            }
        }
        int written = 0;
        for (Map.Entry<String, Map<String, Set<String>>> pkg : wanted.entrySet()) {
            StringBuilder src = new StringBuilder("package " + pkg.getKey() + ";\n\npublic final class R {\n");
            for (Map.Entry<String, Set<String>> type : pkg.getValue().entrySet()) {
                Map<String, String> have = appSymbols.get(type.getKey());
                if (have == null) continue;
                StringBuilder body = new StringBuilder();
                for (String name : type.getValue()) {
                    String decl = have.get(name);
                    if (decl != null) body.append("        ").append(decl).append('\n');
                }
                if (body.length() == 0) continue;
                src.append("    public static final class ").append(type.getKey()).append(" {\n")
                    .append(body).append("    }\n");
            }
            src.append("}\n");
            File dest = new File(generated, pkg.getKey().replace('.', '/') + "/R.java");
            if (dest.isFile()) continue; // never replace an R produced by AAPT2 itself
            MavenLibraries.safeWrite(dest, src.toString().getBytes("UTF-8"));
            written++;
        }
        return written;
    }

    private static final Pattern INNER = Pattern.compile("public\\s+static\\s+final\\s+class\\s+(\\w+)\\s*\\{");
    private static final Pattern FIELD = Pattern.compile(
            "public\\s+static\\s+final\\s+int(\\s*\\[\\s*\\])?\\s+(\\w+)\\s*=\\s*[^;]*;", Pattern.DOTALL);

    /** Splits an AAPT2 R.java into type -> field name -> full field declaration. */
    static Map<String, Map<String, String>> parseR(String source) {
        Map<String, Map<String, String>> out = new HashMap<String, Map<String, String>>();
        Matcher inner = INNER.matcher(source);
        List<int[]> blocks = new ArrayList<int[]>();
        List<String> names = new ArrayList<String>();
        while (inner.find()) {
            int depth = 1, i = inner.end();
            while (i < source.length() && depth > 0) {
                char c = source.charAt(i++);
                if (c == '{') depth++; else if (c == '}') depth--;
            }
            blocks.add(new int[] { inner.end(), i });
            names.add(inner.group(1));
            inner.region(i, source.length());
        }
        for (int b = 0; b < blocks.size(); b++) {
            Map<String, String> fields = new HashMap<String, String>();
            Matcher f = FIELD.matcher(source.substring(blocks.get(b)[0], blocks.get(b)[1]));
            while (f.find()) fields.put(f.group(2), f.group().replaceAll("\\s+", " "));
            out.put(names.get(b), fields);
        }
        return out;
    }

    /** Test helper / diagnostics. */
    static List<String> names(List<Aar> aars) {
        List<String> out = new ArrayList<String>();
        for (Aar a : aars) out.add(a.name);
        return Collections.unmodifiableList(out);
    }
}
