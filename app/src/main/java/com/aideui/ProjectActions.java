package com.aideui;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Helpers for Project menu. Parses common literal Gradle DSL forms without running Gradle. */
public final class ProjectActions {
    private ProjectActions() { }

    public static final class Variant {
        public final String name, buildType, flavor;
        Variant(String n, String type, String flavor) { name = n; buildType = type; this.flavor = flavor; }
        public String toString() { return name; }
    }

    static final Pattern OPEN_BLOCK = Pattern.compile("\\b([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\{");
    static final Pattern CREATE_BLOCK = Pattern.compile("\\b(?:create|register|named|getByName)\\s*\\(\\s*['\"]([A-Za-z_$][A-Za-z0-9_$]*)['\"]\\s*\\)\\s*\\{");

    /** Find the closing brace outside quotes; braces in Gradle string values are ignored. */
    static int matchingBrace(String s, int brace) {
        int level = 0; char quote = 0; boolean escaped = false;
        for (int i = brace; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == quote) quote = 0;
            } else if (c == '\'' || c == '"') quote = c;
            else if (c == '{') level++;
            else if (c == '}' && --level == 0) return i;
        }
        return -1;
    }

    static String section(String source, String name) {
        Matcher m = Pattern.compile("\\b" + Pattern.quote(name) + "\\s*\\{").matcher(source);
        if (!m.find()) return "";
        int start = source.indexOf('{', m.start());
        int end = matchingBrace(source, start);
        return end < 0 ? "" : source.substring(start + 1, end);
    }

    static List<String> blockNames(String source) {
        LinkedHashSet<String> out = new LinkedHashSet<String>();
        Matcher m = Pattern.compile("\\b(?:[A-Za-z_$][A-Za-z0-9_$]*\\s*\\{|(?:create|register|named|getByName)\\s*\\(\\s*['\"][A-Za-z_$][A-Za-z0-9_$]*['\"]\\s*\\)\\s*\\{)").matcher(source);
        int from = 0;
        while (m.find(from) && out.size() < 24) {
            String expr = source.substring(m.start(), m.end());
            Matcher literal = CREATE_BLOCK.matcher(expr);
            if (literal.find()) out.add(literal.group(1));
            else {
                Matcher direct = OPEN_BLOCK.matcher(expr);
                if (direct.find()) out.add(direct.group(1));
            }
            int open = source.indexOf('{', m.start());
            int close = matchingBrace(source, open);
            from = close < 0 ? m.end() : close + 1;
        }
        return new ArrayList<String>(out);
    }

    public static List<Variant> variants(File module) throws IOException {
        if (module == null) throw new IOException("No Gradle module selected");
        File gradle = MavenLibraries.gradleFile(module);
        if (!gradle.isFile() && StandaloneApkBuild.isClassicProject(module)) {
            List<Variant> classic = new ArrayList<Variant>();
            classic.add(new Variant("debug", "debug", ""));
            classic.add(new Variant("release", "release", ""));
            return classic;
        }
        if (!gradle.isFile()) throw new IOException("Missing build.gradle or build.gradle.kts in " + module.getAbsolutePath());
        String src = MavenLibraries.stripGradleComments(MavenLibraries.readText(gradle));
        String android = section(src, "android");
        if (android.isEmpty()) throw new IOException("No Android configuration found in " + gradle.getName());
        List<String> types = blockNames(section(android, "buildTypes"));
        // Android Gradle Plugin creates these even when they are not explicitly declared.
        if (!types.contains("debug")) types.add(0, "debug");
        if (!types.contains("release")) types.add("release");
        List<String> flavors = blockNames(section(android, "productFlavors"));
        ArrayList<Variant> results = new ArrayList<Variant>();
        if (flavors.isEmpty()) {
            for (String type : types) results.add(new Variant(type, type, ""));
        } else {
            for (String flavor : flavors) for (String type : types) {
                results.add(new Variant(flavor + Character.toUpperCase(type.charAt(0)) + type.substring(1), type, flavor));
                if (results.size() >= 50) break;
            }
        }
        return results;
    }

    /** Existing release APKs, not the Java editor's class output. Never creates or signs APKs. */
    public static List<File> releaseApks(File module) {
        List<File> output = new ArrayList<File>();
        if (module == null) return output;
        File root = new File(module, "build/outputs/apk");
        gatherApks(root, output, 0);
        Collections.sort(output, new Comparator<File>() {
            public int compare(File a, File b) {
                if (a.lastModified() == b.lastModified()) return a.getName().compareTo(b.getName());
                return a.lastModified() > b.lastModified() ? -1 : 1;
            }
        });
        return output;
    }

    static void gatherApks(File dir, List<File> out, int depth) {
        if (!dir.isDirectory() || depth > 5 || out.size() >= 40) return;
        File[] entries = dir.listFiles();
        if (entries == null) return;
        for (File entry : entries) {
            if (out.size() >= 40) break;
            if (entry.isDirectory()) gatherApks(entry, out, depth + 1);
            else if (entry.getName().toLowerCase(java.util.Locale.US).endsWith(".apk") && entry.length() > 0)
                out.add(entry);
        }
    }
}
