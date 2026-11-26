package com.aideui;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small, dependency-free Java editor actions (not a semantic language server). */
public final class CodeActions {
    private CodeActions() { }

    public static final class SelectionEdit {
        public final String text;
        public final int start, end;
        public SelectionEdit(String t, int a, int b) { text = t; start = a; end = b; }
    }
    public static final class Completion {
        public final String label;
        public final int start, end;
        Completion(String text, int a, int b) { label = text; start = a; end = b; }
    }
    public static final class Definition {
        public final File file;
        public final int line, offset, rank;
        public final String label;
        Definition(File f, int l, int o, int r, String s) {
            file = f; line = l; offset = o; rank = r; label = s;
        }
    }

    public static String wordAt(String text, int selectionStart, int selectionEnd) {
        if (selectionStart < 0 || selectionStart > text.length()) return "";
        int a = selectionStart, b = Math.min(text.length(), Math.max(selectionStart, selectionEnd));
        if (b == a) {
            if (a == text.length() || !Character.isJavaIdentifierPart(text.charAt(a))) {
                if (a == 0 || !Character.isJavaIdentifierPart(text.charAt(a - 1))) return "";
                a--;
            }
            while (a > 0 && Character.isJavaIdentifierPart(text.charAt(a - 1))) a--;
            b = selectionStart;
            while (b < text.length() && Character.isJavaIdentifierPart(text.charAt(b))) b++;
        }
        String w = text.substring(a, b);
        return RefactorEngine.identifier(w) ? w : "";
    }

    /** Toggle // comments for complete touched lines, preserving indent and newline style. */
    public static SelectionEdit toggleComments(String src, int start, int end) {
        start = Math.max(0, Math.min(start, src.length()));
        end = Math.max(start, Math.min(end, src.length()));
        int first = src.lastIndexOf('\n', Math.max(0, start - 1)) + 1;
        int last = end;
        if (last > first && last <= src.length() && src.charAt(last - 1) == '\n') last--;
        int lineEnd = src.indexOf('\n', last);
        if (lineEnd < 0) lineEnd = src.length();
        else lineEnd++; // Preserve EOL after final line.
        String section = src.substring(first, lineEnd);
        String[] lines = section.split("(?<=\\n)", -1);
        boolean hasContent = false, allCommented = true;
        for (String s : lines) {
            String noEol = s.endsWith("\n") ? s.substring(0, s.length() - 1) : s;
            int p = 0;
            while (p < noEol.length() && Character.isWhitespace(noEol.charAt(p))) p++;
            if (p >= noEol.length()) continue;
            hasContent = true;
            if (!noEol.startsWith("//", p)) allCommented = false;
        }
        if (!hasContent) return new SelectionEdit(src, first, lineEnd);
        StringBuilder out = new StringBuilder();
        for (String s : lines) {
            String noEol = s.endsWith("\n") ? s.substring(0, s.length() - 1) : s;
            String eol = s.endsWith("\n") ? "\n" : "";
            int p = 0;
            while (p < noEol.length() && Character.isWhitespace(noEol.charAt(p))) p++;
            if (p < noEol.length()) {
                if (allCommented && noEol.startsWith("//", p)) {
                    int rem = p + 2;
                    if (rem < noEol.length() && noEol.charAt(rem) == ' ') rem++;
                    noEol = noEol.substring(0, p) + noEol.substring(rem);
                } else if (!allCommented) {
                    noEol = noEol.substring(0, p) + "// " + noEol.substring(p);
                }
            }
            out.append(noEol).append(eol);
        }
        String replacement = out.toString();
        return new SelectionEdit(src.substring(0, first) + replacement + src.substring(lineEnd),
                first, first + replacement.length());
    }

    private static final Set<String> COMMON = new LinkedHashSet<String>(Arrays.asList(
        "Activity", "AlertDialog", "ArrayList", "Arrays", "Boolean", "Bundle", "Button",
        "Calendar", "CharSequence", "Color", "Context", "EditText", "File", "FileInputStream",
        "FileOutputStream", "Handler", "HashMap", "HashSet", "ImageView", "Intent", "Integer",
        "LinearLayout", "List", "Map", "Object", "Override", "PopupMenu", "Runnable",
        "ScrollView", "Set", "String", "StringBuilder", "System", "TextView", "Toast",
        "View", "ViewGroup", "add", "append", "clear", "contains", "equals", "findViewById",
        "getApplicationContext", "getResources", "getText", "getString", "indexOf",
        "isEmpty", "length", "onClick", "onCreate", "println", "remove", "setContentView",
        "setOnClickListener", "setText", "size", "startActivity", "substring", "toString"));

    private static final Set<String> QUALIFIED = new LinkedHashSet<String>(Arrays.asList(
        "java.io", "java.lang", "java.math", "java.net", "java.nio", "java.security", "java.sql", "java.text", "java.time", "java.util",
        "java.util.ArrayList", "java.util.Arrays", "java.util.Calendar", "java.util.Collections", "java.util.HashMap", "java.util.HashSet",
        "java.util.List", "java.util.Map", "java.util.Set", "java.util.TreeMap", "java.util.TreeSet",
        "android.app", "android.content", "android.graphics", "android.net", "android.os", "android.text", "android.util", "android.view", "android.widget",
        "android.app.Activity", "android.app.AlertDialog", "android.content.Context", "android.content.Intent", "android.graphics.Color",
        "android.os.Bundle", "android.os.Handler", "android.view.View", "android.view.ViewGroup",
        "android.widget.Button", "android.widget.EditText", "android.widget.ImageView", "android.widget.LinearLayout", "android.widget.ScrollView", "android.widget.TextView", "android.widget.Toast",
        "androidx.activity", "androidx.activity.ComponentActivity", "androidx.activity.OnBackPressedCallback",
        "androidx.appcompat.app", "androidx.appcompat.app.AppCompatActivity",
        "androidx.core", "androidx.core.content", "androidx.fragment.app", "androidx.recyclerview.widget"
    ));

    private static final Set<String> KOTLIN_WORDS = new LinkedHashSet<String>(Arrays.asList(
        "package", "import", "class", "interface", "object", "fun", "val", "var", "override", "open",
        "abstract", "sealed", "data", "enum", "annotation", "companion", "init", "constructor", "this",
        "super", "if", "else", "when", "for", "while", "do", "return", "break", "continue", "try", "catch",
        "finally", "throw", "is", "in", "as", "null", "true", "false", "private", "protected", "public",
        "internal", "lateinit", "by", "get", "set", "typealias", "inline", "suspend", "const", "vararg",
        "out", "where", "operator", "infix", "tailrec", "crossinline", "noinline", "reified", "final",
        "println", "print", "listOf", "mutableListOf", "mapOf", "mutableMapOf", "setOf", "mutableSetOf",
        "arrayOf", "intArrayOf", "emptyList", "emptyMap", "require", "check", "error", "TODO", "lazy",
        "apply", "also", "let", "run", "with", "takeIf", "repeat", "forEach", "map", "filter",
        "joinToString", "toString", "toInt", "toLong", "toDouble", "isEmpty", "isNotEmpty", "Unit", "Int",
        "Long", "Double", "Float", "Boolean", "String", "Char", "Any", "Nothing", "List", "MutableList",
        "Map", "MutableMap", "Set", "MutableSet", "Array", "Pair", "Regex"));

    public static List<Completion> completions(String src, int caret, int selectionEnd,
                                                List<String> projectWords) {
        return completions(src, caret, selectionEnd, projectWords, false);
    }

    public static List<Completion> completions(String src, int caret, int selectionEnd,
                                                List<String> projectWords, boolean kotlin) {
        int end = Math.max(0, Math.min(src.length(), selectionEnd));
        int start = Math.max(0, Math.min(caret, end));
        if (start == end) {
            while (start > 0) {
                char ch = src.charAt(start - 1);
                if (Character.isJavaIdentifierPart(ch) || ch == '.') start--;
                else break;
            }
        }
        String prefix = src.substring(start, end);
        String simplePrefix = prefix;
        int replaceStart = start;
        int dot = prefix.lastIndexOf('.');
        if (dot >= 0) {
            replaceStart = start + dot + 1;
            simplePrefix = prefix.substring(dot + 1);
        }
        Set<String> names = new LinkedHashSet<String>();
        if (dot >= 0) {
            names.addAll(qualifiedCompletions(prefix));
            names.addAll(CompletionCatalog.children(prefix));
        } else {
            if (kotlin) names.addAll(KOTLIN_WORDS); else names.addAll(JavaAnalyzer.KEYWORDS);
            names.addAll(COMMON);
            boolean[] code = RefactorEngine.codeMask(src);
            Matcher m = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*").matcher(src);
            while (m.find()) if (code[m.start()] && (m.end() - m.start() > 1)) names.add(m.group());
            if (projectWords != null) names.addAll(projectWords);
        }
        List<String> filtered = new ArrayList<String>();
        for (String n : names) {
            if (dot >= 0) {
                if (!n.equals(simplePrefix) && n.startsWith(simplePrefix)) filtered.add(n);
            } else if (!n.equals(simplePrefix) && n.startsWith(simplePrefix) && n.length() > 0) {
                filtered.add(n);
            }
        }
        Collections.sort(filtered, new Comparator<String>() {
            public int compare(String x, String y) { return x.compareToIgnoreCase(y); }
        });
        List<Completion> result = new ArrayList<Completion>();
        for (String n : filtered) {
            result.add(new Completion(n, replaceStart, end));
            if (result.size() >= 100) break;
        }
        return result;
    }

    static Set<String> qualifiedCompletions(String prefix) {
        Set<String> names = new LinkedHashSet<String>();
        int dot = prefix.lastIndexOf('.');
        if (dot < 0) return names;
        String base = prefix.substring(0, dot + 1);
        String typed = prefix.substring(dot + 1);
        for (String qualified : QUALIFIED) {
            if (!qualified.startsWith(base)) continue;
            String remainder = qualified.substring(base.length());
            if (remainder.length() == 0) continue;
            int nextDot = remainder.indexOf('.');
            String segment = nextDot < 0 ? remainder : remainder.substring(0, nextDot);
            if (segment.startsWith(typed) && segment.length() > 0) names.add(segment);
        }
        return names;
    }

    public static SelectionEdit applyCompletion(String src, Completion c) {
        String text = src.substring(0, c.start) + c.label + src.substring(c.end);
        int offset = c.start + c.label.length();
        return new SelectionEdit(text, offset, offset);
    }

    public static List<Definition> definitions(File current, File root, String symbol,
                                                int caretOffset) throws IOException {
        if (!RefactorEngine.identifier(symbol)) return Collections.emptyList();
        List<File> files = new ArrayList<File>();
        gatherJava(root, files, 2000);
        if (current != null && current.isFile() && !files.contains(current)) files.add(0, current);
        List<Definition> matches = new ArrayList<Definition>();
        for (File file : files) {
            String s;
            try { s = RefactorEngine.read(file); } catch (IOException ignored) { continue; }
            List<JavaAnalyzer.Tok> toks = JavaAnalyzer.lex(s, new ArrayList<JavaAnalyzer.Err>());
            List<JavaAnalyzer.Tok> words = new ArrayList<JavaAnalyzer.Tok>();
            for (JavaAnalyzer.Tok tok : toks) if (tok.type != JavaAnalyzer.COMMENT) words.add(tok);
            for (int i = 0; i < words.size(); i++) {
                JavaAnalyzer.Tok tok = words.get(i);
                if (tok.type != JavaAnalyzer.WORD || !symbol.equals(s.substring(tok.start, tok.end))) continue;
                String prev = i > 0 ? s.substring(words.get(i-1).start, words.get(i-1).end) : "";
                String prev2 = i > 1 ? s.substring(words.get(i-2).start, words.get(i-2).end) : "";
                String next = i + 1 < words.size() ? s.substring(words.get(i+1).start, words.get(i+1).end) : "";
                int rank = 100;
                int lineBegin = s.lastIndexOf('\n', Math.max(0, tok.start - 1)) + 1;
                String beforeOnLine = s.substring(lineBegin, tok.start);
                if (prev.equals("class") || prev.equals("interface") || prev.equals("enum")
                        || prev.equals("record") || prev.equals("@interface")
                        || prev.equals("object") || prev.equals("typealias")) rank = 0;
                else if (next.equals("(") && java.util.regex.Pattern.compile("\\bfun\\b[^(=]*$").matcher(beforeOnLine).find()) rank = 1;
                else if (prev.equals("val") || prev.equals("var")) rank = 2;
                else if (next.equals(":") && (prev.equals("(") || prev.equals(",")) && !s.substring(lineBegin, tok.start).contains("=")) rank = 2;
                else if (next.equals("(") && !prev.equals(".") && (typeLike(prev) || prev.equals(">")
                        || prev.equals("void") || isModifier(prev))) rank = 1;
                else if ((next.equals("=") || next.equals(";") || next.equals(",")
                        || next.equals(")") || next.equals("["))
                        && (typeLike(prev) || prev.equals("]") || prev.equals(">"))) rank = 2;
                else if (next.equals("(") && prev.equals("new")) rank = 3;
                if (rank == 100) continue;
                int line = 1;
                for (int k = 0; k < tok.start; k++) if (s.charAt(k) == '\n') line++;
                int beg = s.lastIndexOf('\n', Math.max(0, tok.start - 1)) + 1;
                int ending = s.indexOf('\n', tok.end);
                if (ending < 0) ending = s.length();
                String preview = s.substring(beg, ending).trim();
                if (preview.length() > 100) preview = preview.substring(0, 100) + "…";
                matches.add(new Definition(file, line, tok.start, rank, preview));
            }
        }
        Collections.sort(matches, new Comparator<Definition>() {
            public int compare(Definition a, Definition b) {
                int x = a.rank - b.rank;
                if (x != 0) return x;
                boolean sameA = a.file.equals(current), sameB = b.file.equals(current);
                if (sameA != sameB) return sameA ? -1 : 1;
                if (sameA) {
                    long da = Math.abs((long)a.offset - caretOffset), db = Math.abs((long)b.offset - caretOffset);
                    return da < db ? -1 : da == db ? 0 : 1;
                }
                x = a.file.getAbsolutePath().compareTo(b.file.getAbsolutePath());
                return x != 0 ? x : a.line - b.line;
            }
        });
        if (matches.size() > 60) return new ArrayList<Definition>(matches.subList(0, 60));
        return matches;
    }

    static boolean isModifier(String s) {
        return s.equals("public") || s.equals("private") || s.equals("protected") || s.equals("static");
    }
    static boolean typeLike(String s) {
        if (s.length() == 0 || s.equals(".") || s.equals("return") || s.equals("new")) return false;
        if (s.equals("int") || s.equals("void") || s.equals("long") || s.equals("double")
                || s.equals("float") || s.equals("char") || s.equals("byte") || s.equals("short")
                || s.equals("boolean") || s.equals("var")) return true;
        return Character.isUpperCase(s.charAt(0)) || s.equals("String") || s.equals("Object");
    }
    static void gatherJava(File dir, List<File> out, int cap) {
        if (dir == null || out.size() >= cap) return;
        if (dir.isFile()) { if (dir.getName().endsWith(".java") || dir.getName().endsWith(".kt")) out.add(dir); return; }
        File[] fs = dir.listFiles();
        if (fs == null) return;
        Arrays.sort(fs);
        for (File f : fs) {
            if (out.size() >= cap) break;
            if (f.isDirectory()) {
                if (!f.getName().startsWith(".") && !f.getName().equals("build")
                        && !f.getName().equals("node_modules")) gatherJava(f, out, cap);
            } else if ((f.getName().endsWith(".java") || f.getName().endsWith(".kt")) && f.length() < 1024 * 1024) out.add(f);
        }
    }
}
