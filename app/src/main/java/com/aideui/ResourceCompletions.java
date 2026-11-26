package com.aideui;

import java.io.File;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Project-backed R.type.name completion, without generating placeholder resources. */
public final class ResourceCompletions {
    private ResourceCompletions() { }
    private static final Pattern RESOURCE_GROUP = Pattern.compile("(?:^|[^\\w.])R\\.([a-zA-Z0-9_]*)$");
    private static final Pattern REFERENCE = Pattern.compile("(?:^|[^\\w.])((?:[a-zA-Z_$][\\w$]*\\.)?R)\\.([a-z][a-z0-9_]*)\\.([a-zA-Z0-9_]*)$");
    private static final Pattern ID = Pattern.compile("@\\+id/([a-zA-Z_][a-zA-Z0-9_]*)");
    private static final Pattern ITEM = Pattern.compile("<\\s*(?:item|public)\\b[^>]*\\btype\\s*=\\s*['\"]([a-zA-Z_][\\w]*)['\"][^>]*\\bname\\s*=\\s*['\"]([a-zA-Z_][\\w]*)['\"]|<\\s*(?:item|public)\\b[^>]*\\bname\\s*=\\s*['\"]([a-zA-Z_][\\w]*)['\"][^>]*\\btype\\s*=\\s*['\"]([a-zA-Z_][\\w]*)['\"]");
    private static final Pattern VALUE = Pattern.compile("<\\s*(string|color|dimen|bool|integer|array|string-array|integer-array|style|plurals|fraction|attr|declare-styleable)\\b[^>]*\\bname\\s*=\\s*['\"]([a-zA-Z_][\\w]*)['\"]");
    private static final Pattern RES_TYPES = Pattern.compile("(?:layout|id|string|drawable|mipmap|color|dimen|style|menu|xml|raw|anim|animator|bool|integer|array|plurals|attr|fraction)");

    public static boolean matches(String source, int caret) {
        if (source == null || caret < 0 || caret > source.length()) return false;
        String before = source.substring(Math.max(0, caret - 100), caret);
        return REFERENCE.matcher(before).find() || RESOURCE_GROUP.matcher(before).find();
    }

    public static List<CodeActions.Completion> complete(File javaFile, String source, int caret) {
        if (javaFile == null || source == null || caret < 0 || caret > source.length()) return Collections.emptyList();
        String before = source.substring(Math.max(0, caret - 100), caret);
        Matcher match = REFERENCE.matcher(before);
        String group, typed;
        boolean selectingGroup = false;
        if (match.find()) {
            group = match.group(2);
            typed = match.group(3);
            if (!RES_TYPES.matcher(group).matches()) return Collections.emptyList();
        } else {
            Matcher groupMatch = RESOURCE_GROUP.matcher(before);
            if (!groupMatch.find()) return Collections.emptyList();
            group = "";
            typed = groupMatch.group(1);
            selectingGroup = true;
        }
        File res = findRes(javaFile);
        if (res == null) return Collections.emptyList();
        Set<String> names = selectingGroup ? groups(res) : collect(res, group);
        List<String> sorted = new ArrayList<String>(names);
        Collections.sort(sorted, String.CASE_INSENSITIVE_ORDER);
        List<CodeActions.Completion> completions = new ArrayList<CodeActions.Completion>();
        for (String n : sorted) {
            if (n.startsWith(typed) && !n.equals(typed)) {
                completions.add(new CodeActions.Completion(n, caret - typed.length(), caret));
                if (completions.size() >= 100) break;
            }
        }
        return completions;
    }

    static File findRes(File javaFile) {
        // Gradle: app/src/main/res. Classic AIDE: project/res.
        for (File p = javaFile.getParentFile(); p != null; p = p.getParentFile()) {
            if (new File(p, "AndroidManifest.xml").isFile()) {
                File res = new File(p, "res");
                if (res.isDirectory()) return res;
            }
            File res = new File(p, "src/main/res");
            if (res.isDirectory()) return res;
            if ("main".equals(p.getName()) && "src".equals(p.getParentFile() == null ? "" : p.getParentFile().getName())) {
                res = new File(p, "res");
                if (res.isDirectory()) return res;
            }
        }
        return null;
    }

    static Set<String> groups(File res) {
        Set<String> groups = new LinkedHashSet<String>();
        for (String group : new String[] {"layout", "id", "string", "drawable", "mipmap", "color",
                "dimen", "style", "menu", "xml", "raw", "anim", "animator", "bool",
                "integer", "array", "plurals", "attr", "fraction"}) {
            if (!collect(res, group).isEmpty()) groups.add(group);
        }
        return groups;
    }

    static Set<String> collect(File res, String group) {
        Set<String> names = new LinkedHashSet<String>();
        File[] dirs = res.listFiles();
        if (dirs == null) return names;
        for (File dir : dirs) {
            if (!dir.isDirectory()) continue;
            String folder = dir.getName();
            String base = folder.split("-", 2)[0];
            File[] items = dir.listFiles();
            if (items == null) continue;
            if (base.equals(group) && !group.equals("id") && !group.equals("attr")) {
                for (File item : items) if (item.isFile()) {
                    String name = resourceFileName(item.getName());
                    if (valid(name)) names.add(name);
                }
            }
            for (File item : items) {
                if (!item.isFile() || !item.getName().endsWith(".xml")) continue;
                // For id references inspect all XML resources. For value names inspect values XML.
                if (!group.equals("id") && !base.equals("values")) continue;
                String xml = readSmall(item);
                if (group.equals("id")) {
                    Matcher ids = ID.matcher(xml);
                    while (ids.find()) names.add(ids.group(1));
                }
                if (base.equals("values")) {
                    Matcher values = VALUE.matcher(xml);
                    while (values.find()) {
                        String type = values.group(1);
                        if (type.endsWith("-array")) type = "array";
                        if (type.equals(group)) names.add(values.group(2));
                    }
                    Matcher itemsMatcher = ITEM.matcher(xml);
                    while (itemsMatcher.find()) {
                        String type = itemsMatcher.group(1) != null ? itemsMatcher.group(1) : itemsMatcher.group(4);
                        String name = itemsMatcher.group(2) != null ? itemsMatcher.group(2) : itemsMatcher.group(3);
                        if (group.equals(type)) names.add(name);
                    }
                }
            }
        }
        return names;
    }

    static String resourceFileName(String file) {
        int dot = file.indexOf('.');
        return dot > 0 ? file.substring(0, dot) : file;
    }
    static boolean valid(String name) { return name != null && name.matches("[a-zA-Z_][a-zA-Z0-9_]*"); }
    static String readSmall(File f) {
        if (f.length() > 1024 * 1024) return "";
        try {
            FileInputStream in = new FileInputStream(f);
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[4096]; int n, total = 0;
                while ((n = in.read(buf)) != -1 && total < 1024 * 1024) { out.write(buf, 0, n); total += n; }
                return out.toString("UTF-8");
            } finally { in.close(); }
        } catch (Exception ignored) { return ""; }
    }
}
