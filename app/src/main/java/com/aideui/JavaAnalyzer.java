package com.aideui;

import android.graphics.Color;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Lexer + highlighter + light syntax checker for Java source. */
public class JavaAnalyzer {

    public static class Err {
        public int start, end, line; public String msg; public boolean warning;
        public Err(int s, int e, String m) { start = s; end = e; msg = m; }
    }

    public static class Result {
        public CharSequence text;
        public List<Err> errors = new ArrayList<Err>();
    }

    static final int WORD = 0, STRING = 1, NUMBER = 2, COMMENT = 3, PUNCT = 4, OP = 5, AT = 6;

    static class Tok {
        int type, start, end;
        Tok(int t, int s, int e) { type = t; start = s; end = e; }
    }

    static final Set<String> KEYWORDS = new HashSet<String>(Arrays.asList(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
        "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
        "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native",
        "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp",
        "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void",
        "volatile", "while", "true", "false", "null", "var"));

    // Package roots the checker treats as resolvable. Add your libraries here.
    static final String[] KNOWN_ROOTS = {
        "java.", "javax.", "android.", "dalvik.", "org.json", "org.xml", "org.w3c",
        "org.apache.http", "junit."};

    static final int C_KEYWORD = Color.parseColor("#1E7FD8");
    static final int C_TYPE    = Color.parseColor("#29B6F6");
    static final int C_PUNCT   = Color.parseColor("#29B6F6");
    static final int C_GREEN   = Color.parseColor("#2E8B3A");
    static final int C_GRAY    = Color.parseColor("#757575");

    // ---------------- lexer ----------------

    static List<Tok> lex(String s, List<Err> errs) {
        List<Tok> out = new ArrayList<Tok>();
        int n = s.length(), i = 0;
        while (i < n) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            int st = i;
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '/') {
                while (i < n && s.charAt(i) != '\n') i++;
                out.add(new Tok(COMMENT, st, i));
            } else if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                int e = s.indexOf("*/", i + 2);
                if (e < 0) { errs.add(new Err(st, st + 2, "Unterminated comment")); i = n; }
                else i = e + 2;
                out.add(new Tok(COMMENT, st, i));
            } else if (c == '"' && s.startsWith("\"\"\"", i)) {
                int e = s.indexOf("\"\"\"", i + 3);
                if (e < 0) { errs.add(new Err(st, st + 3, "Unterminated text block")); i = n; }
                else i = e + 3;
                out.add(new Tok(STRING, st, i));
            } else if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < n && s.charAt(j) != c && s.charAt(j) != '\n') j += (s.charAt(j) == '\\') ? 2 : 1;
                if (j < n && s.charAt(j) == c) i = j + 1;
                else {
                    i = Math.min(j, n);
                    errs.add(new Err(st, Math.max(st + 1, i),
                        c == '"' ? "Unterminated string literal" : "Unterminated character literal"));
                }
                out.add(new Tok(STRING, st, i));
            } else if (Character.isJavaIdentifierStart(c)) {
                while (i < n && Character.isJavaIdentifierPart(s.charAt(i))) i++;
                out.add(new Tok(WORD, st, i));
            } else if (Character.isDigit(c)) {
                while (i < n && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_' || s.charAt(i) == '.')) i++;
                out.add(new Tok(NUMBER, st, i));
            } else if (c == '@') {
                i++; out.add(new Tok(AT, st, i));
            } else if ("(){}[];,.".indexOf(c) >= 0) {
                i++; out.add(new Tok(PUNCT, st, i));
            } else {
                i++; out.add(new Tok(OP, st, i));
            }
        }
        return out;
    }

    // ---------------- main entry ----------------

    public static Result analyze(String text) { return analyze(text, null); }

    /** Inspect project-local Maven classpath when the source file is known. */
    public static Result analyze(String text, java.io.File sourceFile) {
        Result r = new Result();
        List<Tok> toks = lex(text, r.errors);

        r.text = highlight(text, toks);
        check(text, toks, r.errors, sourceFile);

        // line numbers
        for (Err e : r.errors) {
            int line = 1, lim = Math.min(e.start, text.length());
            for (int i = 0; i < lim; i++) if (text.charAt(i) == '\n') line++;
            e.line = line;
        }
        if (r.errors.size() > 300) r.errors = new ArrayList<Err>(r.errors.subList(0, 300));
        liveLineChecks(text, r.errors);
        if (r.errors.size() > 300) r.errors = new ArrayList<Err>(r.errors.subList(0, 300));
        return r;
    }

    static void liveLineChecks(String text, List<Err> errs) {
        String[] lines = text.split("\n", -1);
        int offset = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();
            if (trimmed.length() == 0 || trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
                offset += line.length() + 1;
                continue;
            }
            if (trimmed.matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*\\.")) {
                int st = offset + line.lastIndexOf('.');
                Err e = new Err(st, Math.min(st + 1, text.length()), "Identifier expected after '.'");
                e.line = i + 1;
                errs.add(e);
            } else if (trimmed.matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+")
                    && !trimmed.startsWith("package ") && !trimmed.startsWith("import ")) {
                int st = offset + Math.max(0, line.indexOf(trimmed));
                Err e = new Err(st, Math.min(st + trimmed.length(), text.length()), "Not a complete statement");
                e.line = i + 1;
                errs.add(e);
            }
            offset += line.length() + 1;
        }
    }

    // ---------------- highlighting ----------------

    static String word(String s, Tok t) { return s.substring(t.start, t.end); }

    static void color(SpannableStringBuilder sb, Tok t, int col, boolean bold) {
        sb.setSpan(new ForegroundColorSpan(col), t.start, t.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (bold) sb.setSpan(new StyleSpan(Typeface.BOLD), t.start, t.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    static CharSequence highlight(String s, List<Tok> toks) {
        if (s.length() > 300000) return s;
        SpannableStringBuilder sb = new SpannableStringBuilder(s);
        boolean directive = false;
        for (Tok t : toks) {
            switch (t.type) {
                case COMMENT: case STRING: case NUMBER: color(sb, t, C_GREEN, false); break;
                case PUNCT: color(sb, t, C_PUNCT, false); if (t.type == PUNCT && s.charAt(t.start) == ';') directive = false; break;
                case OP: color(sb, t, C_GREEN, false); break;
                case WORD: {
                    String w = word(s, t);
                    if (KEYWORDS.contains(w)) {
                        color(sb, t, C_KEYWORD, true);
                        if (w.equals("import") || w.equals("package")) directive = true;
                    } else if (directive) {
                        color(sb, t, C_GRAY, false);
                    } else if (Character.isUpperCase(w.charAt(0)) && (w.length() == 1 || !w.equals(w.toUpperCase()))) {
                        color(sb, t, C_TYPE, false);
                    }
                    break;
                }
            }
        }
        return sb;
    }

    // ---------------- syntax / import checks ----------------

    static void check(String s, List<Tok> all, List<Err> errs, java.io.File sourceFile) {
        List<Tok> t = new ArrayList<Tok>();
        for (Tok x : all) if (x.type != COMMENT) t.add(x);

        // brackets
        List<Tok> stack = new ArrayList<Tok>();
        for (Tok x : t) {
            if (x.type != PUNCT) continue;
            char c = s.charAt(x.start);
            if (c == '(' || c == '{' || c == '[') stack.add(x);
            else if (c == ')' || c == '}' || c == ']') {
                char open = c == ')' ? '(' : c == '}' ? '{' : '[';
                if (stack.isEmpty() || s.charAt(stack.get(stack.size() - 1).start) != open)
                    errs.add(new Err(x.start, x.end, "Unexpected '" + c + "'"));
                else stack.remove(stack.size() - 1);
            }
        }
        for (Tok x : stack)
            errs.add(new Err(x.start, x.end, "'" + s.charAt(x.start) + "' is never closed"));

        // package / import statements
        String ownPkg = "";
        for (int i = 0; i < t.size(); i++) {
            Tok x = t.get(i);
            if (x.type != WORD) continue;
            String w = word(s, x);
            if (!w.equals("import") && !w.equals("package")) continue;

            boolean isImport = w.equals("import");
            List<Tok> parts = new ArrayList<Tok>();
            boolean wildcard = false, isStatic = false;
            int j = i + 1;
            Tok last = x;
            for (; j < t.size(); j++) {
                Tok y = t.get(j);
                String yw = s.substring(y.start, y.end);
                if (y.type == PUNCT && yw.equals(";")) break;
                if (y.type == WORD && KEYWORDS.contains(yw) && !yw.equals("static")) break;
                if (y.type == WORD && yw.equals("static")) { isStatic = true; last = y; continue; }
                if (y.type == WORD) parts.add(y);
                if (y.type == OP && yw.equals("*")) wildcard = true;
                last = y;
            }
            boolean hasSemi = j < t.size() && t.get(j).type == PUNCT && s.charAt(t.get(j).start) == ';';
            if (!hasSemi) errs.add(new Err(last.start, last.end, "';' expected"));
            if (parts.isEmpty()) { i = j; continue; }

            StringBuilder name = new StringBuilder();
            for (int k = 0; k < parts.size(); k++) {
                if (k > 0) name.append('.');
                name.append(word(s, parts.get(k)));
            }
            String full = name.toString();

            if (!isImport) { ownPkg = full; i = j; continue; }

            String own2 = "";
            String[] seg = ownPkg.split("\\.");
            if (seg.length >= 2) own2 = seg[0] + "." + seg[1] + ".";
            if (!ownPkg.isEmpty() && (full + ".").startsWith(own2) && !own2.isEmpty()) { i = j; continue; }

            boolean onClasspath = sourceFile != null && MavenLibraries.hasImport(sourceFile, full, wildcard);
            boolean known = onClasspath;
            for (String root : KNOWN_ROOTS)
                if ((full + ".").startsWith(root)) { known = true; break; }
            if (!known) {
                String problem = full.startsWith("androidx.")
                    ? "AndroidX library missing for '" + full + "'. Use More > Libraries > Add / Sync."
                    : "Package '" + word(s, parts.get(0)) + "' does not exist";
                errs.add(new Err(parts.get(0).start, parts.get(0).end, problem));
            } else if (!onClasspath && !wildcard && !isStatic) {
                try { Class.forName(full, false, JavaAnalyzer.class.getClassLoader()); }
                catch (Throwable e) {
                    // nested classes, e.g. android.os.Build.VERSION
                    String alt = full; boolean ok = false;
                    for (int k = alt.lastIndexOf('.'); k > 0 && !ok; k = alt.lastIndexOf('.', k - 1)) {
                        alt = alt.substring(0, k) + "$" + alt.substring(k + 1);
                        try { Class.forName(alt, false, JavaAnalyzer.class.getClassLoader()); ok = true; }
                        catch (Throwable e2) { }
                    }
                    if (!ok) {
                        Tok lt = parts.get(parts.size() - 1);
                        errs.add(new Err(lt.start, lt.end, "Cannot resolve '" + word(s, lt) + "'"));
                    }
                }
            }
            i = j;
        }
    }
}
