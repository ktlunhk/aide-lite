package com.aideui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Safe, opt-in import fixes for recognizable ECJ unresolved-type diagnostics.
 * Does not write files. Edits are applied to the live editor for Undo/Save.
 */
public final class JavaQuickFix {
    private JavaQuickFix() { }

    private static final Map<String, String> KNOWN_TYPES = new LinkedHashMap<String, String>();
    static {
        // Deliberately conservative: no guessing between ambiguous or project-local classes.
        String[][] types = {
            {"List", "java.util.List"}, {"ArrayList", "java.util.ArrayList"},
            {"Map", "java.util.Map"}, {"HashMap", "java.util.HashMap"},
            {"Set", "java.util.Set"}, {"HashSet", "java.util.HashSet"},
            {"LinkedHashMap", "java.util.LinkedHashMap"},
            {"LinkedHashSet", "java.util.LinkedHashSet"},
            {"Collections", "java.util.Collections"}, {"Arrays", "java.util.Arrays"},
            {"Iterator", "java.util.Iterator"}, {"Comparator", "java.util.Comparator"},
            {"Queue", "java.util.Queue"}, {"Deque", "java.util.Deque"},
            {"LinkedList", "java.util.LinkedList"}, {"TreeMap", "java.util.TreeMap"},
            {"TreeSet", "java.util.TreeSet"}, {"Optional", "java.util.Optional"},
            {"Date", "java.util.Date"}, {"Calendar", "java.util.Calendar"},
            {"Random", "java.util.Random"}, {"UUID", "java.util.UUID"},
            {"File", "java.io.File"}, {"IOException", "java.io.IOException"},
            {"InputStream", "java.io.InputStream"},
            {"OutputStream", "java.io.OutputStream"},
            {"BufferedReader", "java.io.BufferedReader"},
            {"InputStreamReader", "java.io.InputStreamReader"},
            {"FileInputStream", "java.io.FileInputStream"},
            {"FileOutputStream", "java.io.FileOutputStream"},
            {"FileReader", "java.io.FileReader"},
            {"FileWriter", "java.io.FileWriter"},
            {"BigDecimal", "java.math.BigDecimal"},
            {"BigInteger", "java.math.BigInteger"},
            {"Pattern", "java.util.regex.Pattern"},
            {"Matcher", "java.util.regex.Matcher"},
            {"Activity", "android.app.Activity"},
            {"Bundle", "android.os.Bundle"},
            {"Intent", "android.content.Intent"},
            {"Context", "android.content.Context"},
            {"View", "android.view.View"},
            {"ViewGroup", "android.view.ViewGroup"},
            {"TextView", "android.widget.TextView"},
            {"EditText", "android.widget.EditText"},
            {"Button", "android.widget.Button"},
            {"LinearLayout", "android.widget.LinearLayout"},
            {"Toast", "android.widget.Toast"},
            {"Log", "android.util.Log"},
            {"Uri", "android.net.Uri"},
            {"Color", "android.graphics.Color"}
        };
        for (String[] pair : types) KNOWN_TYPES.put(pair[0], pair[1]);
    }

    // Examples: "List cannot be resolved to a type", "List cannot be resolved".
    // Refuse variable/member diagnostics: their fix might not be an import.
    private static final Pattern UNRESOLVED = Pattern.compile(
            "(?:^|\\s|:)('?)([A-Za-z_$][A-Za-z0-9_$]*)\\1 cannot be resolved(?: to a type)?(?:\\s|$)");
    private static final Pattern IMPORT = Pattern.compile(
            "(?m)^\\s*import\\s+(?:static\\s+)?([\\w.$*]+)\\s*;");

    public static String importForError(String message, String code) {
        if (message == null || code == null) return null;
        if (message.contains("to a variable") || message.contains("to a method") ||
                message.contains("to a field")) return null;
        Matcher m = UNRESOLVED.matcher(message);
        if (!m.find()) return null;
        String name = m.group(2);
        String qualified = KNOWN_TYPES.get(name);
        if (qualified == null) return null;
        // Avoid importing types that are already provided or conflict with another import.
        Matcher imports = IMPORT.matcher(code);
        while (imports.find()) {
            String existing = imports.group(1);
            if (existing.equals(qualified) || existing.equals(qualified.substring(0, qualified.lastIndexOf('.')) + ".*"))
                return null;
            if (existing.endsWith("." + name) && !existing.equals(qualified)) return null;
        }
        if (Pattern.compile("\\b(?:class|interface|enum|record)\\s+" + Pattern.quote(name) + "\\b").matcher(code).find())
            return null;
        return qualified;
    }

    /** Inserts an import in the conventional import region, leaving all other text unchanged. */
    public static String addImport(String code, String qualified) {
        if (code == null || qualified == null || !qualified.matches("[A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)+"))
            throw new IllegalArgumentException("Invalid import");
        Matcher imports = IMPORT.matcher(code);
        while (imports.find()) if (qualified.equals(imports.group(1))) return code;
        String nl = code.contains("\r\n") ? "\r\n" : "\n";
        String statement = "import " + qualified + ";" + nl;
        // Keep ordinary imports alphabetical; place before static imports.
        imports.reset();
        int afterLastNormal = -1, beforeFirstStatic = -1;
        while (imports.find()) {
            String existing = imports.group(1);
            String entire = imports.group();
            if (entire.trim().startsWith("import static ")) {
                if (beforeFirstStatic < 0) beforeFirstStatic = imports.start();
            } else {
                if (existing.compareTo(qualified) > 0) return code.substring(0, imports.start()) + statement + code.substring(imports.start());
                int end = imports.end();
                if (end < code.length() && code.charAt(end) == '\r') end++;
                if (end < code.length() && code.charAt(end) == '\n') end++;
                afterLastNormal = end;
            }
        }
        if (afterLastNormal >= 0) return code.substring(0, afterLastNormal) + statement + code.substring(afterLastNormal);
        if (beforeFirstStatic >= 0) return code.substring(0, beforeFirstStatic) + statement + code.substring(beforeFirstStatic);
        Matcher pkg = Pattern.compile("(?m)^\\s*package\\s+[\\w.]+\\s*;").matcher(code);
        if (pkg.find()) {
            int end = pkg.end();
            // Insert after the package statement, keeping the original rest.
            return code.substring(0, end) + nl + nl + statement + code.substring(end);
        }
        return statement + nl + code;
    }

    public static boolean hasListOfOnLine(String code, int line) {
        return replaceListOfOnLine(code, line) != null;
    }

    /** Replaces ONLY List.of on the reported source line; caller should explain semantics differ. */
    public static String replaceListOfOnLine(String code, int line) {
        if (code == null || line <= 0) return null;
        int start = 0;
        for (int i = 1; i < line; i++) {
            int next = code.indexOf('\n', start);
            if (next < 0) return null;
            start = next + 1;
        }
        int end = code.indexOf('\n', start);
        if (end < 0) end = code.length();
        String lineText = code.substring(start, end);
        if (lineText.trim().startsWith("//") || lineText.trim().startsWith("*")) return null;
        Matcher m = Pattern.compile("(?<![\\w$.])List\\s*\\.\\s*of\\s*\\(").matcher(lineText);
        if (!m.find()) return null;
        // Preserve the parentheses and arguments; only replace the invocation prefix.
        return code.substring(0, start + m.start()) + "Arrays.asList(" + code.substring(start + m.end());
    }

    public static List<String> suggestedImports(String code) {
        List<String> result = new ArrayList<String>();
        if (code == null) return result;
        for (String name : KNOWN_TYPES.keySet()) {
            if (Pattern.compile("\\b" + name + "\\b").matcher(code).find()) {
                String candidate = importForError(name + " cannot be resolved to a type", code);
                if (candidate != null) result.add(candidate);
            }
        }
        return result;
    }
}
