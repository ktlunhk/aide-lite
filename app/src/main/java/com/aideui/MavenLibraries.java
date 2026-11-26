package com.aideui;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Small Android-compatible Maven dependency downloader for the ECJ editor.
 * Resolves literal Gradle coordinates and POM dependencies from Google Maven
 * and Maven Central. AAR classes.jar and embedded libs/*.jar are extracted into
 * a project-local .aideui-libs directory for code checking (NOT APK packaging).
 *
 * No shell, dynamically loaded code, or untrusted repository URLs are used.
 * Gradle remains responsible for building, dexing and merging AAR resources.
 */
public final class MavenLibraries {
    private MavenLibraries() { }
    public static final String DEFAULT_ACTIVITY = "androidx.activity:activity:1.0.0";
    public static final String REPOSITORY_DIR = ".aideui-libs";
    // Jetpack Compose + Material 3 resolves well over 100 POMs (every KMP root POM plus its -android variant).
    static final int MAX_ARTIFACTS = 400;
    static final int MAX_DEPTH = 20;
    static final long MAX_FILE = 150L * 1024 * 1024;
    static final long MAX_UNPACKED = 80L * 1024 * 1024;
    static final Pattern COORD = Pattern.compile("([A-Za-z0-9_.-]+):([A-Za-z0-9_.-]+):([A-Za-z0-9_.+-]+)");
    static final Pattern GRADLE = Pattern.compile("(?<![A-Za-z0-9_])(?:implementation|api|compile|compileOnly|runtimeOnly|debugImplementation|releaseImplementation)\\s*(?:\\(\\s*)?['\"]([A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:[A-Za-z0-9_.+-]+)['\"]");
    /** implementation platform('group:artifact:version') - a Maven BOM that supplies versions for other dependencies. */
    static final Pattern GRADLE_PLATFORM = Pattern.compile("(?<![A-Za-z0-9_])(?:implementation|api|compile|compileOnly|runtimeOnly|debugImplementation|releaseImplementation)\\s*(?:\\(\\s*)?(?:enforcedPlatform|platform)\\s*\\(\\s*['\\\"]([A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:[A-Za-z0-9_.+-]+)['\\\"]\\s*\\)");
    /** implementation 'group:artifact' - no version; it comes from a platform/BOM. */
    static final Pattern GRADLE_NO_VERSION = Pattern.compile("(?<![A-Za-z0-9_])(?:implementation|api|compile|compileOnly|runtimeOnly|debugImplementation|releaseImplementation)\\s*(?:\\(\\s*)?['\\\"]([A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+)['\\\"]");
    static final Pattern IMPORT = Pattern.compile("(?m)^\\s*import\\s+(?:static\\s+)?(androidx\\.[A-Za-z0-9_.*]+)\\s*;");
    static final String[] BASES = {
        "https://dl.google.com/dl/android/maven2/",
        "https://repo.maven.apache.org/maven2/"
    };

    public interface Progress { void update(String message); }
    public interface Fetcher { /** Return true if found; false on 404. */ boolean fetch(String relative, File output) throws IOException; }

    public static class Coordinate {
        public final String group, artifact, version;
        public Coordinate(String g, String a, String v) {
            group = g; artifact = a; version = v;
        }
        public String toString() { return version.isEmpty() ? group + ":" + artifact + " (version from BOM)" : group + ":" + artifact + ":" + version; }
        public String path() {
            return group.replace('.', '/') + "/" + artifact + "/" + version + "/" + artifact + "-" + version;
        }
        public String id() { return group + ":" + artifact; }
        public String filePrefix() { return group + "-" + artifact + "-" + version; }
    }

    public static Coordinate coordinate(String s) {
        Matcher m = COORD.matcher(s == null ? "" : s.trim());
        if (!m.matches() || s.contains("..") || s.contains("+"))
            throw new IllegalArgumentException("Enter an exact version: group:artifact:version (no + or ranges)");
        return new Coordinate(m.group(1), m.group(2), m.group(3));
    }

    /** Determine the module directory from any file in its src/main/java tree. */
    public static File moduleFromSource(File file) {
        for (File d = file; d != null; d = d.getParentFile()) {
            if ((d.getName().equals("java") || d.getName().equals("kotlin")) && d.getParentFile() != null
                    && d.getParentFile().getName().equals("main")
                    && d.getParentFile().getParentFile() != null
                    && d.getParentFile().getParentFile().getName().equals("src")) {
                return d.getParentFile().getParentFile().getParentFile();
            }
        }
        return null;
    }

    public static File moduleFromDirectory(File start) {
        for (File d = start; d != null; d = d.getParentFile()) {
            if (gradleFile(d).isFile() && new File(d, "src").isDirectory()) return d;
        }
        if (start != null && gradleFile(new File(start, "app")).isFile()) return new File(start, "app");
        return null;
    }

    /** Prefer Groovy build.gradle, but accept simple literal Gradle Kotlin DSL too. */
    public static File gradleFile(File module) {
        File groovy = new File(module, "build.gradle");
        // When both files exist, Gradle itself selects the Groovy script.
        // Keep the same precedence for the lightweight in-app parser.
        if (groovy.isFile()) return groovy;
        return new File(module, "build.gradle.kts");
    }

    public static File store(File module) { return new File(module, REPOSITORY_DIR); }
    public static File index(File module) { return new File(store(module), "classpath.txt"); }
    public static File aarIndex(File module) { return new File(store(module), "aars.txt"); }

    public static List<Coordinate> dependencies(File module) throws IOException {
        File gradle = gradleFile(module);
        if (!gradle.isFile()) return Collections.emptyList();
        String s = stripGradleComments(readText(gradle));
        Matcher m = GRADLE.matcher(s);
        LinkedHashMap<String, Coordinate> found = new LinkedHashMap<String, Coordinate>();
        while (m.find()) {
            Coordinate c = coordinate(m.group(1));
            found.put(c.id(), c);
        }
        // Versionless declarations (Compose style) are resolved against the platform/BOM during sync.
        Matcher bare = GRADLE_NO_VERSION.matcher(s);
        while (bare.find()) {
            String[] parts = bare.group(1).split(":");
            if (parts.length != 2) continue;
            String id = parts[0] + ":" + parts[1];
            if (!found.containsKey(id)) found.put(id, new Coordinate(parts[0], parts[1], ""));
        }
        return new ArrayList<Coordinate>(found.values());
    }

    /** Platforms/BOMs declared with implementation platform('group:artifact:version'). */
    public static List<Coordinate> boms(File module) throws IOException {
        File gradle = gradleFile(module);
        if (!gradle.isFile()) return Collections.emptyList();
        Matcher m = GRADLE_PLATFORM.matcher(stripGradleComments(readText(gradle)));
        LinkedHashMap<String, Coordinate> found = new LinkedHashMap<String, Coordinate>();
        while (m.find()) {
            Coordinate c = coordinate(m.group(1));
            found.put(c.id(), c);
        }
        return new ArrayList<Coordinate>(found.values());
    }

    /** Remove line/block comments but preserve // inside quoted Gradle strings. */
    static String stripGradleComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        boolean line = false, block = false, escaped = false;
        char quoted = 0;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            char next = i + 1 < source.length() ? source.charAt(i + 1) : 0;
            if (line) {
                if (c == '\n') { line = false; out.append(c); }
                continue;
            }
            if (block) {
                if (c == '*' && next == '/') { block = false; i++; }
                else if (c == '\n') out.append('\n');
                continue;
            }
            if (quoted != 0) {
                out.append(c);
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == quoted) quoted = 0;
            } else if (c == '\'' || c == '"') {
                quoted = c; out.append(c);
            } else if (c == '/' && next == '/') {
                line = true; i++;
            } else if (c == '/' && next == '*') {
                block = true; i++;
            } else out.append(c);
        }
        return out.toString();
    }

    public static boolean hasDeclared(File module, Coordinate coordinate) throws IOException {
        for (Coordinate c : dependencies(module)) if (coordinate.id().equals(c.id())) return true;
        return false;
    }

    public static String firstAndroidxImport(String javaCode) {
        Matcher m = IMPORT.matcher(javaCode == null ? "" : javaCode);
        return m.find() ? m.group(1) : null;
    }

    public static boolean importIsInstalled(File source, String javaCode) {
        String imported = firstAndroidxImport(javaCode);
        if (imported == null) return true;
        boolean wildcard = imported.endsWith(".*");
        String name = wildcard ? imported.substring(0, imported.length() - 2) : imported;
        return hasImport(source, name, wildcard);
    }

    public static String importCoordinate(String javaCode) {
        if (javaCode == null) return null;
        Matcher m = IMPORT.matcher(javaCode);
        while (m.find()) {
            String name = m.group(1);
            if (name.startsWith("androidx.activity.")) return DEFAULT_ACTIVITY;
            if (name.startsWith("androidx.appcompat.")) return "androidx.appcompat:appcompat:1.0.2";
            if (name.startsWith("androidx.core.")) return "androidx.core:core:1.0.2";
            if (name.startsWith("androidx.fragment.")) return "androidx.fragment:fragment:1.0.0";
            if (name.startsWith("androidx.recyclerview.")) return "androidx.recyclerview:recyclerview:1.0.0";
            if (name.startsWith("androidx.annotation.")) return "androidx.annotation:annotation:1.0.0";
            if (name.startsWith("androidx.lifecycle.")) return "androidx.lifecycle:lifecycle-runtime:2.0.0";
        }
        return null;
    }

    /** Safe, backup-first Gradle editing; only the module dependency block is edited. */
    public static void addGradleDependency(File module, Coordinate c) throws IOException {
        File gradle = gradleFile(module);
        if (!gradle.isFile()) throw new IOException("Module build.gradle missing: " + gradle);
        if (hasDeclared(module, c)) return;
        String old = readText(gradle);
        String insert = gradle.getName().endsWith(".kts")
            ? "    implementation(\"" + c + "\") // Added by AideUI\n"
            : "    implementation '" + c + "' // Added by AideUI\n";
        Matcher block = Pattern.compile("\\bdependencies\\s*\\{").matcher(old);
        String replacement;
        if (block.find()) replacement = old.substring(0, block.end()) + "\n" + insert + old.substring(block.end());
        else replacement = old + (old.endsWith("\n") ? "" : "\n") + "\ndependencies {\n" + insert + "}\n";
        backupAndWrite(gradle, old, replacement);
        // Older Gradle projects often declare only jcenter(). Add Google's Maven
        // repository to allprojects without changing plugin versions or removing others.
        File rootGradle = gradleFile(module.getParentFile());
        if (rootGradle.isFile() && !rootGradle.equals(gradle)) {
            String root = readText(rootGradle);
            if (!root.contains("google()") && !root.matches("(?s).*google\\s*\\(\\s*\\).*")) {
                String addition = "\n// Google Maven is required for AndroidX (added by AideUI).\n"
                    + (rootGradle.getName().endsWith(".kts")
                        ? "allprojects { repositories { google(); mavenCentral() } }\n"
                        : "allprojects { repositories { google(); mavenCentral() } }\n");
                backupAndWrite(rootGradle, root, root + addition);
            }
        }
    }

    public static List<File> classpath(File module) {
        ArrayList<File> out = new ArrayList<File>();
        File idx = index(module);
        if (!idx.isFile()) return out;
        try {
            String content = readText(idx);
            String[] lines = content.split("\\n");
            for (String path : lines) {
                path = path.trim();
                // Entries are generated relative to the module, never accept traversal.
                if (!path.startsWith(REPOSITORY_DIR + "/classpath/") || path.contains("..") || !path.endsWith(".jar")) continue;
                File jar = new File(module, path);
                if (jar.isFile() && jar.length() > 0) out.add(jar);
            }
        } catch (IOException ignored) { }
        return out;
    }

    public static boolean hasAnyClasspath(File module) { return !classpath(module).isEmpty(); }

    /** Inspect cached class JARs: a valid AndroidX import must exist in a classpath entry. */
    public static boolean hasImport(File sourceFile, String imported, boolean wildcard) {
        File module = moduleFromSource(sourceFile);
        if (module == null) return false;
        String path = imported.replace('.', '/');
        String exact = path + ".class";
        String nested = path.replaceAll("/([^/]+)$", "\\$$1") + ".class";
        String packagePrefix = path + "/";
        for (File jar : classpath(module)) {
            ZipFile z = null;
            try {
                z = new ZipFile(jar);
                if (!wildcard && (z.getEntry(exact) != null || z.getEntry(nested) != null)) return true;
                if (wildcard) {
                    java.util.Enumeration<? extends ZipEntry> entries = z.entries();
                    while (entries.hasMoreElements()) {
                        String n = entries.nextElement().getName();
                        if (n.startsWith(packagePrefix) && n.endsWith(".class")) return true;
                    }
                }
            } catch (IOException ignored) { }
            finally { if (z != null) try { z.close(); } catch (IOException ignored) { } }
        }
        return false;
    }

    /** Class names from cached JARs matching explicit/wildcard imports for autocomplete. */
    public static List<String> completionNames(File source, String javaCode) {
        File module = source == null ? null : moduleFromSource(source);
        if (module == null) return Collections.emptyList();
        Set<String> prefixes = new HashSet<String>();
        Set<String> explicit = new HashSet<String>();
        Matcher imports = Pattern.compile("(?m)^\\s*import\\s+(?!static\\s)([A-Za-z0-9_.*]+)\\s*;")
            .matcher(javaCode == null ? "" : javaCode);
        while (imports.find()) {
            String name = imports.group(1);
            if (name.endsWith(".*")) prefixes.add(name.substring(0, name.length()-1).replace('.', '/'));
            else if (name.lastIndexOf('.') > 0) explicit.add(name.replace('.', '/') + ".class");
        }
        Set<String> found = new java.util.TreeSet<String>();
        for (File jar : classpath(module)) {
            ZipFile z = null;
            try {
                z = new ZipFile(jar);
                java.util.Enumeration<? extends ZipEntry> e = z.entries();
                int scanned = 0;
                while (e.hasMoreElements() && scanned++ < 25000 && found.size() < 1500) {
                    String n = e.nextElement().getName();
                    if (!n.endsWith(".class") || n.indexOf('$') >= 0) continue;
                    boolean useful = explicit.contains(n);
                    for (String prefix : prefixes) if (n.startsWith(prefix) && n.substring(prefix.length()).indexOf('/') < 0) useful = true;
                    if (!useful) continue;
                    String simple = n.substring(n.lastIndexOf('/')+1, n.length()-6);
                    if (simple.length() > 1 && Character.isJavaIdentifierStart(simple.charAt(0))) found.add(simple);
                }
            } catch (IOException ignored) { }
            finally { if (z != null) try { z.close(); } catch (IOException ignored) { } }
        }
        return new ArrayList<String>(found);
    }

    static class Pom {
        String packaging = "jar";
        List<Coordinate> deps = new ArrayList<Coordinate>();
        /** Dependencies that name no version; a BOM may supply it. */
        List<Coordinate> versionless = new ArrayList<Coordinate>();
        /** dependencyManagement entries (group:artifact -> version); this is what a BOM publishes. */
        Map<String, String> managed = new LinkedHashMap<String, String>();
        List<String> problems = new ArrayList<String>();
    }

    static String childText(Element e, String tag) {
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling())
            if (n.getNodeType() == Node.ELEMENT_NODE && tag.equals(n.getLocalName() == null ? n.getNodeName() : n.getLocalName()))
                return n.getTextContent().trim();
        return "";
    }
    static Element child(Element e, String tag) {
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling())
            if (n.getNodeType() == Node.ELEMENT_NODE && tag.equals(n.getLocalName() == null ? n.getNodeName() : n.getLocalName())) return (Element)n;
        return null;
    }
    static String expand(String s, Map<String, String> props) {
        if (s == null) return "";
        for (int tries = 0; tries < 6 && s.contains("${"); tries++) {
            Matcher m = Pattern.compile("\\$\\{([^}]+)\\}").matcher(s);
            StringBuffer b = new StringBuffer(); boolean replaced = false;
            while (m.find()) {
                String val = props.get(m.group(1));
                if (val != null) { m.appendReplacement(b, Matcher.quoteReplacement(val)); replaced = true; }
                else m.appendReplacement(b, Matcher.quoteReplacement(m.group()));
            }
            m.appendTail(b); s = b.toString(); if (!replaced) break;
        }
        return s;
    }

    static Pom parsePom(File file, Coordinate owner) throws Exception {
        byte[] bytes = readLimited(file, 2 * 1024 * 1024);
        String xml = new String(bytes, "UTF-8");
        if (xml.contains("<!DOCTYPE") || xml.contains("<!ENTITY")) throw new IOException("Unsafe Maven POM XML");
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(false);
        f.setExpandEntityReferences(false);
        Document d = f.newDocumentBuilder().parse(file);
        Element root = d.getDocumentElement();
        Pom pom = new Pom();
        String pk = childText(root, "packaging");
        if (!pk.isEmpty()) pom.packaging = pk;
        Map<String, String> props = new HashMap<String, String>();
        props.put("project.groupId", owner.group); props.put("project.artifactId", owner.artifact);
        props.put("project.version", owner.version); props.put("pom.groupId", owner.group);
        props.put("pom.version", owner.version);
        Element parent = child(root, "parent");
        if (parent != null) {
            props.put("project.parent.groupId", childText(parent, "groupId"));
            props.put("project.parent.version", childText(parent, "version"));
        }
        Element properties = child(root, "properties");
        if (properties != null) for (Node n = properties.getFirstChild(); n != null; n = n.getNextSibling())
            if (n.getNodeType() == Node.ELEMENT_NODE) props.put(n.getNodeName(), n.getTextContent().trim());
        Map<String, String> managed = new HashMap<String, String>();
        Element management = child(root, "dependencyManagement");
        Element managedDeps = management == null ? null : child(management, "dependencies");
        if (managedDeps != null) for (Node n = managedDeps.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (!(n instanceof Element) || !"dependency".equals(n.getNodeName())) continue;
            Element e = (Element)n;
            managed.put(expand(childText(e, "groupId"), props) + ":" + expand(childText(e, "artifactId"), props),
                expand(childText(e, "version"), props));
        }
        pom.managed.putAll(managed);
        Element deps = child(root, "dependencies");
        if (deps != null) for (Node n = deps.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (!(n instanceof Element) || !"dependency".equals(n.getNodeName())) continue;
            Element e = (Element)n;
            String scope = childText(e, "scope"), optional = childText(e, "optional"), type = childText(e, "type");
            if ("test".equals(scope) || "provided".equals(scope) || "system".equals(scope)
                    || "import".equals(scope) || "true".equals(optional)
                    || (!type.isEmpty() && !"aar".equals(type) && !"jar".equals(type))) continue;
            String group = expand(childText(e, "groupId"), props);
            String artifact = expand(childText(e, "artifactId"), props);
            String version = childText(e, "version");
            if (version.isEmpty()) version = managed.get(group + ":" + artifact);
            if (version == null || version.isEmpty()) {
                pom.versionless.add(new Coordinate(group, artifact, ""));
                continue;
            }
            version = expand(version, props);
            if (version.startsWith("[") || version.startsWith("(")) {
                // Maven range such as [2.12.0] or [1.0,2.0): use the lower bound (the preferred version).
                String v = version.replaceAll("[\\[\\]()\\s]", "");
                int comma = v.indexOf(',');
                version = comma < 0 ? v : (comma > 0 ? v.substring(0, comma) : v.substring(comma + 1));
            }
            try { pom.deps.add(coordinate(group + ":" + artifact + ":" + version)); }
            catch (Exception err) { pom.problems.add("Skipped unresolved POM dependency " + group + ":" + artifact + ":" + version); }
        }
        return pom;
    }

    public static class Summary {
        public int artifacts, cacheHits;
        public final List<String> warnings = new ArrayList<String>();
        public final List<File> jars = new ArrayList<File>();
        public String message(File module) {
            return "Libraries: " + artifacts + " artifacts (" + cacheHits + " cached); " + jars.size()
                + " classpath JARs\n" + (warnings.isEmpty() ? "No dependency warnings" : "Warnings:\n" + join(warnings, "\n"))
                + "\nCache: " + store(module).getAbsolutePath();
        }
    }

    public static Summary sync(File module, Progress progress) throws Exception {
        return sync(module, progress, false);
    }

    /** Allow project-defined custom HTTPS repositories only when the user opted in. */
    public static Summary sync(File module, Progress progress, boolean customRepositories) throws Exception {
        return sync(module, new HttpFetcher(repositoryUrls(module, customRepositories)), progress);
    }

    /**
     * Global Gradle excludes: exclude(group = "g", module = "m") or exclude group: 'g', module: 'm', e.g. inside
     * configurations.all { ... }. Returns {group, module-or-empty} pairs for the module and its parent build file.
     */
    static List<String[]> excludes(File module) {
        List<String[]> out = new ArrayList<String[]>();
        File[] files = { gradleFile(module), module.getParentFile() == null ? null : gradleFile(module.getParentFile()) };
        Pattern p = Pattern.compile("\\bexclude\\s*\\(?\\s*group\\s*[:=]\\s*[\"']([^\"']+)[\"'](?:\\s*,\\s*module\\s*[:=]\\s*[\"']([^\"']+)[\"'])?");
        for (File f : files) {
            if (f == null || !f.isFile()) continue;
            try {
                String text = readText(f).replaceAll("(?m)^\\s*//.*$", "");
                Matcher m = p.matcher(text);
                while (m.find()) out.add(new String[] { m.group(1), m.group(2) == null ? "" : m.group(2) });
            } catch (IOException ignored) { }
        }
        return out;
    }

    static boolean excluded(List<String[]> excludes, Coordinate c) {
        for (String[] e : excludes)
            if (e[0].equals(c.group) && (e[1].isEmpty() || e[1].equals(c.artifact))) return true;
        return false;
    }

    /** Testable offline sync entry; the production call uses HTTPS-only repositories. */
    static Summary sync(File module, Fetcher fetcher, Progress progress) throws Exception {
        if (module == null || !gradleFile(module).isFile())
            throw new IOException("Open a Gradle module source file before syncing libraries");
        List<Coordinate> roots = dependencies(module);
        Summary summary = new Summary();
        if (roots.isEmpty()) {
            // An empty dependency block must clear stale classes from old versions.
            safeWrite(index(module), new byte[0]);
            return summary;
        }
        // Platforms (BOMs): their dependencyManagement supplies versions for versionless and transitive dependencies.
        Map<String, String> managed = resolveBoms(module, fetcher, progress, summary);
        managed = new HashMap<String, String>(managed);
        LinkedHashMap<String, File> jars = new LinkedHashMap<String, File>();
        LinkedHashSet<String> aarPrefixes = new LinkedHashSet<String>();
        int warningMark = summary.warnings.size();
        List<String[]> excludes = excludes(module);
        // Gradle resolves a conflict to the highest version requested anywhere in the graph. A breadth-first
        // walk can visit a lower version at a shallower depth first, so repeat the walk with every higher
        // requested version recorded as a minimum until nothing changes.
        for (int pass = 0; ; pass++) {
            jars.clear(); aarPrefixes.clear(); summary.artifacts = 0;
            while (summary.warnings.size() > warningMark) summary.warnings.remove(summary.warnings.size() - 1);
            Map<String, String> resolved = new HashMap<String, String>();
            Map<String, String> requested = new HashMap<String, String>();
        LinkedHashMap<String, Coordinate> pending = new LinkedHashMap<String, Coordinate>();
        for (Coordinate c : roots) {
            if (c.version.isEmpty()) {
                String version = managed.get(c.id());
                if (version == null || version.isEmpty())
                    throw new IOException(c.group + ":" + c.artifact + " has no version and no platform/BOM supplies one. "
                        + "Add implementation platform('group:artifact:version') or give the dependency a version.");
                c = new Coordinate(c.group, c.artifact, version);
            }
            else {
                String bom = managed.get(c.id());
                if (bom != null && !bom.isEmpty() && compareVersions(bom, c.version) > 0)
                    c = new Coordinate(c.group, c.artifact, bom);
            }
            pending.put(c.id(), c);
        }
        // Nearest-version-wins breadth-first traversal, with direct dependencies first.
        HashSet<String> visited = new HashSet<String>();
        int depth = 0;
        while (!pending.isEmpty() && depth++ < MAX_DEPTH) {
            LinkedHashMap<String, Coordinate> next = new LinkedHashMap<String, Coordinate>();
            for (Coordinate c : pending.values()) {
                if (!visited.add(c.id())) continue;
                if (summary.artifacts >= MAX_ARTIFACTS) throw new IOException("Too many Maven dependencies (> " + MAX_ARTIFACTS + ")");
                summary.artifacts++;
                resolved.put(c.id(), c.version);
                if (progress != null) progress.update("Resolving " + c + " (" + summary.artifacts + ")");
                File base = new File(store(module), "repository/" + c.path());
                File pom = new File(base.getPath() + ".pom");
                if (fetchCached(fetcher, c.path() + ".pom", pom)) summary.cacheHits++;
                Pom p = parsePom(pom, c);
                summary.warnings.addAll(p.problems);
                if (!excludes.isEmpty()) {
                    for (java.util.Iterator<Coordinate> it = p.deps.iterator(); it.hasNext(); ) {
                        Coordinate dep = it.next();
                        if (excluded(excludes, dep)) { it.remove(); summary.warnings.add("Excluded " + dep.id() + " (build file exclude)"); }
                    }
                    for (java.util.Iterator<Coordinate> it = p.versionless.iterator(); it.hasNext(); )
                        if (excluded(excludes, it.next())) it.remove();
                }
                for (Coordinate open : p.versionless) {
                    String version = managed.get(open.id());
                    if (version != null && !version.isEmpty()) p.deps.add(new Coordinate(open.group, open.artifact, version));
                    else summary.warnings.add("Skipped unresolved POM dependency " + open.id() + " (no version)");
                }
                if (!"pom".equals(p.packaging)) {
                    String suffix = "aar".equals(p.packaging) ? ".aar" : ".jar";
                    File artifact = new File(base.getPath() + suffix);
                    try {
                        if (fetchCached(fetcher, c.path() + suffix, artifact)) summary.cacheHits++;
                    } catch (IOException missing) {
                        if (!missing.getMessage().startsWith("Artifact not found:")) throw missing;
                        // Some Google Maven POMs omit <packaging> even for AARs.
                        suffix = suffix.equals(".jar") ? ".aar" : ".jar";
                        artifact = new File(base.getPath() + suffix);
                        try {
                            if (fetchCached(fetcher, c.path() + suffix, artifact)) summary.cacheHits++;
                        } catch (IOException missing2) {
                            // Kotlin-multiplatform root modules (Compose, lifecycle, coroutines) publish only a POM/.module
                            // that points at the real -android/-jvm artifact; keep going through their dependencies.
                            if (!missing2.getMessage().startsWith("Artifact not found:") || p.deps.isEmpty()) throw missing2;
                            summary.warnings.add(c.id() + ": no jar/aar published; using its dependencies only");
                            artifact = null;
                        }
                    }
                    if (artifact != null) {
                        for (File jar : unpack(module, artifact, c)) jars.put(jar.getAbsolutePath(), jar);
                        if (artifact.getName().endsWith(".aar")) aarPrefixes.add(c.filePrefix());
                    }
                }
                for (Coordinate dep : p.deps) {
                    String seen = requested.get(dep.id());
                    if (seen == null || compareVersions(dep.version, seen) > 0) requested.put(dep.id(), dep.version);
                }
                for (Coordinate dep : p.deps) if (!visited.contains(dep.id())) {
                    // If a direct dependency was explicitly requested, do not replace its version.
                    if (pending.containsKey(dep.id())) continue;
                    Coordinate queued = next.get(dep.id());
                    // Gradle resolves a conflict to the highest requested version, not the first one seen.
                    if (queued != null && compareVersions(dep.version, queued.version) <= 0) continue;
                    // A platform is a lower bound for transitive versions too (Gradle's constraint semantics): the
                    // higher of requested and managed wins, so a newer alpha is never downgraded.
                    String pinned = managed.get(dep.id());
                    next.put(dep.id(), pinned != null && !pinned.isEmpty() && compareVersions(pinned, dep.version) > 0
                        ? new Coordinate(dep.group, dep.artifact, pinned) : dep);
                }
            }
            pending = next;
        }
        if (!pending.isEmpty()) throw new IOException("Dependency graph exceeds maximum depth " + MAX_DEPTH);
            Map<String, String> upgrades = new HashMap<String, String>();
            for (Map.Entry<String, String> e : requested.entrySet()) {
                String have = resolved.get(e.getKey());
                if (have != null && compareVersions(e.getValue(), have) > 0) upgrades.put(e.getKey(), e.getValue());
            }
            if (upgrades.isEmpty() || pass >= 8) break;
            managed.putAll(upgrades);
        }
        summary.jars.addAll(jars.values());
        // A pure-POM dependency graph is valid; publish an empty classpath.
        StringBuilder content = new StringBuilder();
        for (File file : summary.jars) {
            String relative = store(module).getName() + "/classpath/" + file.getName();
            content.append(relative).append('\n');
        }
        // Atomic index publication: old classpath remains valid if download fails.
        File idx = index(module);
        safeWrite(idx, content.toString().getBytes("UTF-8"));
        // Every resolved AAR, including resource-only ones that have no classes.jar (their attrs/styles
        // are still referenced by other libraries, e.g. drawerLayoutStyle).
        StringBuilder aarIndex = new StringBuilder();
        for (String prefix : aarPrefixes) aarIndex.append(prefix).append('\n');
        safeWrite(aarIndex(module), aarIndex.toString().getBytes("UTF-8"));
        // Kept for diagnostics: the build's failure report quotes it (resolution warnings, skipped dependencies).
        safeWrite(new File(store(module), "sync-summary.txt"), summary.message(module).getBytes("UTF-8"));
        return summary;
    }

    /** Returns true if an already valid cache file avoided a network request. */
    /** Maven-ish ordering: numeric segments numerically; alpha < beta < rc < release. */
    static int compareVersions(String a, String b) {
        String[] x = a.split("[.\\-+_]"), y = b.split("[.\\-+_]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            String p = i < x.length ? x[i] : "", q = i < y.length ? y[i] : "";
            boolean pn = p.matches("\\d+"), qn = q.matches("\\d+");
            if (pn && qn) {
                int c = new java.math.BigInteger(p).compareTo(new java.math.BigInteger(q));
                if (c != 0) return c;
            } else {
                int c = Integer.compare(rank(p), rank(q));
                if (c != 0) return c;
                c = p.compareToIgnoreCase(q);
                if (c != 0) return c;
            }
        }
        return 0;
    }

    private static int rank(String q) {
        String s = q.toLowerCase(java.util.Locale.US);
        if (s.isEmpty() || s.matches("\\d+")) return 4;          // release (or plain number) beats any qualifier
        if (s.startsWith("alpha") || s.equals("a")) return 1;
        if (s.startsWith("beta") || s.equals("b")) return 2;
        if (s.startsWith("rc") || s.startsWith("cr")) return 3;
        if (s.equals("final") || s.equals("ga") || s.equals("release")) return 4;
        return 3;
    }

    /** Reads every declared platform/BOM and merges their managed versions. */
    static Map<String, String> resolveBoms(File module, Fetcher fetcher, Progress progress, Summary summary) throws Exception {
        Map<String, String> managed = new HashMap<String, String>();
        for (Coordinate bom : boms(module)) {
            if (progress != null) progress.update("Resolving platform " + bom);
            File pomFile = new File(store(module), "repository/" + bom.path() + ".pom");
            if (fetchCached(fetcher, bom.path() + ".pom", pomFile)) summary.cacheHits++;
            Pom parsed = parsePom(pomFile, bom);
            summary.warnings.addAll(parsed.problems);
            for (Map.Entry<String, String> e : parsed.managed.entrySet())
                if (!e.getValue().isEmpty() && !managed.containsKey(e.getKey())) managed.put(e.getKey(), e.getValue());
        }
        return managed;
    }

    static boolean fetchCached(Fetcher fetcher, String relative, File dest) throws IOException {
        if (dest.isFile() && dest.length() > 0) return true;
        File parent = dest.getParentFile(); if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create " + parent);
        File tmp = new File(parent, dest.getName() + ".part");
        if (tmp.isFile()) tmp.delete();
        try {
            if (!fetcher.fetch(relative, tmp)) throw new IOException("Artifact not found: " + relative);
            if (!tmp.isFile() || tmp.length() == 0 || tmp.length() > MAX_FILE) throw new IOException("Invalid download " + relative);
            if (!tmp.renameTo(dest)) throw new IOException("Cannot finish download " + dest);
        } finally { if (tmp.exists()) tmp.delete(); }
        return false;
    }

    static List<File> unpack(File module, File artifact, Coordinate c) throws IOException {
        List<File> out = new ArrayList<File>();
        File dest = new File(store(module), "classpath");
        if (!dest.isDirectory() && !dest.mkdirs()) throw new IOException("Cannot create " + dest);
        if (artifact.getName().endsWith(".jar")) {
            File copy = new File(dest, c.filePrefix() + ".jar");
            if (!copy.isFile() || copy.length() == 0) copyFile(artifact, copy);
            out.add(copy); return out;
        }
        ZipFile z = new ZipFile(artifact);
        try {
            java.util.Enumeration<? extends ZipEntry> entries = z.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                String name = e.getName();
                if (!"classes.jar".equals(name) && !(name.startsWith("libs/") && name.endsWith(".jar") && name.indexOf('/', 5) < 0)) continue;
                if (e.isDirectory() || e.getSize() > MAX_UNPACKED) throw new IOException("Oversize JAR in " + artifact.getName());
                String outname = c.filePrefix() + ("classes.jar".equals(name) ? "-classes" : "-" + name.substring(5, name.length() - 4)) + ".jar";
                File copy = new File(dest, outname);
                if (!copy.isFile() || copy.length() == 0) {
                    InputStream in = z.getInputStream(e);
                    try { safeWriteStream(copy, in, MAX_UNPACKED); }
                    finally { in.close(); }
                }
                out.add(copy);
            }
        } finally { z.close(); }
        return out;
    }

    /** Parse simple maven { url 'https://…' } and url = uri("https://…") blocks. */
    public static List<String> repositoryUrls(File module, boolean includeCustom) throws IOException {
        List<String> urls = new ArrayList<String>();
        for (String url : BASES) urls.add(url);
        if (!includeCustom || module == null) return urls;
        File[] files = {gradleFile(module), gradleFile(module.getParentFile()),
            new File(module.getParentFile(), "settings.gradle"),
            new File(module.getParentFile(), "settings.gradle.kts")};
        Pattern block = Pattern.compile("(?s)\\bmaven\\s*\\{([^{}]{0,1500})\\}");
        Pattern url = Pattern.compile("\\burl\\s*(?:=\\s*)?(?:uri\\s*\\(\\s*)?['\"](https://[^'\"]+)['\"]");
        for (File f : files) {
            if (!f.isFile()) continue;
            Matcher b = block.matcher(readText(f));
            while (b.find()) {
                Matcher m = url.matcher(b.group(1));
                if (!m.find()) continue;
                String candidate = m.group(1);
                if (!safeRepositoryUrl(candidate) || urls.size() >= 7) continue;
                if (!candidate.endsWith("/")) candidate += "/";
                if (!urls.contains(candidate)) urls.add(candidate);
            }
        }
        return urls;
    }

    static boolean safeRepositoryUrl(String value) {
        try {
            URL u = new URL(value);
            String host = u.getHost().toLowerCase(java.util.Locale.US);
            if (!"https".equalsIgnoreCase(u.getProtocol()) || new java.net.URI(value).getUserInfo() != null
                    || u.getQuery() != null || u.getRef() != null || u.getPort() != -1
                    || host.length() < 4 || host.equals("localhost") || host.endsWith(".local")
                    || host.endsWith(".internal") || host.endsWith(".localhost")
                    || host.startsWith("127.") || host.startsWith("10.")
                    || host.startsWith("192.168.") || host.startsWith("172.")
                    || host.startsWith("169.254.") || host.equals("0.0.0.0")
                    || host.equals("[::1]") || host.indexOf('.') < 0
                    || value.indexOf("..") >= 0 || value.indexOf('\\') >= 0) return false;
            return true;
        } catch (Exception ex) { return false; }
    }

    static class HttpFetcher implements Fetcher {
        final List<String> bases;
        HttpFetcher(List<String> urls) { bases = urls; }
        public boolean fetch(String relative, File output) throws IOException {
            IOException error = null;
            for (String base : bases) {
                HttpURLConnection c = null;
                try {
                    c = (HttpURLConnection)new URL(base + relative).openConnection();
                    c.setConnectTimeout(15000); c.setReadTimeout(30000);
                    c.setInstanceFollowRedirects(false);
                    c.setRequestProperty("Accept", "application/octet-stream,application/xml,*/*");
                    c.setRequestProperty("User-Agent", "AideUI-Maven-Library-Resolver/1.0");
                    int status = c.getResponseCode();
                    if (status == 404) continue;
                    if (status >= 300 && status < 400) {
                        String loc = c.getHeaderField("Location");
                        URL next = loc == null ? null : new URL(c.getURL(), loc);
                        if (next == null || !safeRepositoryUrl(next.toString()))
                            throw new IOException("Unsafe repository redirect from " + base);
                        c.disconnect();
                        c = (HttpURLConnection)next.openConnection();
                        c.setConnectTimeout(15000); c.setReadTimeout(30000);
                        c.setInstanceFollowRedirects(false);
                        status = c.getResponseCode();
                    }
                    if (status < 200 || status >= 300) { error = new IOException("HTTP " + status + " from " + base); continue; }
                    InputStream in = c.getInputStream();
                    try { safeWriteStream(output, in, MAX_FILE); }
                    finally { in.close(); }
                    return true;
                } catch (IOException ex) { error = ex; }
                finally { if (c != null) c.disconnect(); }
            }
            if (error != null) throw error;
            return false;
        }
    }

    static void backupAndWrite(File file, String old, String changed) throws IOException {
        if (old.equals(changed)) return;
        File bak = new File(file.getParentFile(), file.getName() + ".aideui-bak-" + System.currentTimeMillis());
        copyFile(file, bak);
        safeWrite(file, changed.getBytes("UTF-8"));
    }

    static String readText(File file) throws IOException { return new String(readLimited(file, 2 * 1024 * 1024), "UTF-8"); }
    /** For generated files such as R.java, which exceed 2 MB once Material/Compose resources are linked. */
    static String readLargeText(File file) throws IOException { return new String(readLimited(file, 128L * 1024 * 1024), "UTF-8"); }
    static byte[] readLimited(File f, long max) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        InputStream in = new FileInputStream(f);
        try { byte[] buf = new byte[8192]; long n = 0; int k;
            while ((k = in.read(buf)) >= 0) { n += k; if (n > max) throw new IOException("File too large: " + f); bytes.write(buf, 0, k); }
        } finally { in.close(); }
        return bytes.toByteArray();
    }
    static void copyFile(File a, File b) throws IOException {
        InputStream in = new FileInputStream(a);
        try { safeWriteStream(b, in, MAX_UNPACKED); }
        finally { in.close(); }
    }
    static void safeWrite(File dest, byte[] bytes) throws IOException {
        java.io.ByteArrayInputStream in = new java.io.ByteArrayInputStream(bytes);
        safeWriteStream(dest, in, Math.max(bytes.length, 1));
    }
    static void safeWriteStream(File dest, InputStream input, long max) throws IOException {
        File parent = dest.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create " + parent);
        File tmp = new File(parent, dest.getName() + ".tmp");
        FileOutputStream raw = new FileOutputStream(tmp);
        BufferedOutputStream out = new BufferedOutputStream(raw);
        boolean ok = false;
        try { byte[] buf = new byte[8192]; long count = 0; int k;
            while ((k = input.read(buf)) >= 0) {
                count += k; if (count > max) throw new IOException("File too large: " + dest);
                out.write(buf, 0, k);
            }
            out.flush(); raw.getFD().sync(); ok = true;
        } finally { out.close(); if (!ok) tmp.delete(); }
        if (dest.exists() && !dest.delete()) { tmp.delete(); throw new IOException("Cannot replace " + dest); }
        if (!tmp.renameTo(dest)) { tmp.delete(); throw new IOException("Cannot install " + dest); }
    }
    static String join(List<String> l, String sep) {
        StringBuilder b = new StringBuilder();
        for (String s : l) { if (b.length() > 0) b.append(sep); b.append(s); }
        return b.toString();
    }
}
