package com.aideui;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Conservative, source-preserving Java layout formatter. It is intentionally
 * independent of an AndroidX library and never rewrites identifiers, literals,
 * comments, or the order of Java tokens. Formatting happens in memory; Save is
 * a separate operation and Undo can restore the original buffer.
 *
 * This is not Eclipse JDT's full semantic formatter; it handles whitespace,
 * indentation and common brace layouts only.
 */
public final class CodeFormatter {
    private CodeFormatter() { }

    public static final class Style {
        public int indentSize = 4;
        public boolean tabs = false;
        public boolean braceNextLine = false;
        public boolean spaceAfterComma = true;
        public boolean spaceAroundAssignment = true;
        public int maxBlankLines = 2;
        public Style copy() {
            Style s = new Style();
            s.indentSize = indentSize; s.tabs = tabs;
            s.braceNextLine = braceNextLine;
            s.spaceAfterComma = spaceAfterComma;
            s.spaceAroundAssignment = spaceAroundAssignment;
            s.maxBlankLines = maxBlankLines;
            return s;
        }
        public String indent(int depth) {
            if (depth <= 0) return "";
            StringBuilder b = new StringBuilder(depth * Math.max(1, indentSize));
            for (int i = 0; i < depth; i++) {
                if (tabs) b.append('\t');
                else for (int j = 0; j < indentSize; j++) b.append(' ');
            }
            return b.toString();
        }
    }

    public static final class Edit {
        public final String text;
        public final int start, end;
        public Edit(String text, int start, int end) {
            this.text = text; this.start = start; this.end = end;
        }
    }

    static final class State {
        boolean blockComment;
        boolean textBlock;
        State copy() {
            State t = new State();
            t.blockComment = blockComment; t.textBlock = textBlock;
            return t;
        }
    }

    static final class Line {
        final String text;
        final String eol;
        final int start;
        Line(String text, String eol, int start) {
            this.text = text; this.eol = eol; this.start = start;
        }
    }

    static List<Line> lines(String s) {
        ArrayList<Line> out = new ArrayList<Line>();
        int pos = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') {
                int stop = i > pos && s.charAt(i-1) == '\r' ? i-1 : i;
                out.add(new Line(s.substring(pos, stop), s.substring(stop, i+1), pos));
                pos = i + 1;
            }
        }
        if (pos < s.length() || out.isEmpty()) out.add(new Line(s.substring(pos), "", pos));
        return out;
    }

    static String trimEnd(String s) {
        int i = s.length();
        while (i > 0 && (s.charAt(i-1) == ' ' || s.charAt(i-1) == '\t')) i--;
        return s.substring(0, i);
    }
    static String trimStart(String s) {
        int i = 0;
        while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\t')) i++;
        return s.substring(i);
    }

    /** Identifies Java code locations without treating strings and comments as code. */
    static boolean[] codeMask(String text, State state) {
        boolean[] mask = new boolean[text.length()];
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            char next = i + 1 < text.length() ? text.charAt(i+1) : 0;
            if (state.textBlock) {
                if (c == '"' && text.startsWith("\"\"\"", i) && (i == 0 || text.charAt(i-1) != '\\')) {
                    i += 3; state.textBlock = false;
                } else i++;
                continue;
            }
            if (state.blockComment) {
                if (c == '*' && next == '/') { state.blockComment = false; i += 2; }
                else i++;
                continue;
            }
            if (c == '/' && next == '/') break;
            if (c == '/' && next == '*') { state.blockComment = true; i += 2; continue; }
            if (c == '"' && text.startsWith("\"\"\"", i)) { state.textBlock = true; i += 3; continue; }
            if (c == '"' || c == '\'') {
                char q = c;
                i++;
                while (i < text.length()) {
                    char ch = text.charAt(i++);
                    if (ch == '\\') { i = Math.min(text.length(), i + 1); }
                    else if (ch == q) break;
                }
                continue;
            }
            mask[i] = true;
            i++;
        }
        return mask;
    }

    static int braceDelta(String line, State state) {
        boolean[] m = codeMask(line, state);
        int n = 0;
        for (int i = 0; i < line.length(); i++) {
            if (!m[i]) continue;
            if (line.charAt(i) == '{') n++;
            else if (line.charAt(i) == '}') n--;
        }
        return n;
    }

    static int leadingClosings(String code, State state) {
        boolean[] m = codeMask(code, state.copy());
        int k = 0;
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (!m[i] || c == ' ' || c == '\t') continue;
            if (c == '}') k++;
            else break;
        }
        return k;
    }

    static boolean startsInLiteral(State state) {
        return state.blockComment || state.textBlock;
    }

    static String maskTransform(String code, State initial, Style style) {
        boolean[] mask = codeMask(code, initial.copy());
        StringBuilder out = new StringBuilder(code.length() + 16);
        int at = 0;
        while (at < code.length()) {
            boolean active = mask[at];
            int end = at + 1;
            while (end < code.length() && mask[end] == active) end++;
            String fragment = code.substring(at, end);
            if (active) {
                // Whole-run replacements ensure no quoted text or comments are touched.
                fragment = fragment.replaceAll("\\b(if|for|while|switch|catch|synchronized)\\s*\\(", "$1 (");
                fragment = fragment.replaceAll("\\b(try|else|finally|do)\\s*\\{", "$1 {");
                fragment = fragment.replaceAll("\\)\\s*\\{", ") {");
                fragment = fragment.replaceAll("(?<=[A-Za-z0-9_\\]\\)])\\{", " {");
                fragment = fragment.replaceAll("\\}\\s*(else|catch|finally)\\b", "} $1");
                if (style.spaceAfterComma) fragment = fragment.replaceAll(",(?=\\S)", ", ");
                fragment = fragment.replaceAll(";(?=\\S)", "; ");
                if (style.spaceAroundAssignment) {
                    // Only a plain '=' is changed: never ==, <=, >=, !=, =>, +=, -=, etc.
                    fragment = fragment.replaceAll("(?<![=<>!+*/%&|^\\-])\\s*=(?!=|>)\\s*", " = ");
                }
            }
            out.append(fragment);
            at = end;
        }
        return trimEnd(out.toString());
    }

    static boolean header(String s) {
        if (s == null || s.length() == 0) return false;
        String x = trimEnd(s.trim());
        if (x.length() == 0 || x.endsWith(";") || x.endsWith(",") || x.endsWith("=") || x.endsWith("->")) return false;
        if (x.indexOf("//") >= 0 || x.indexOf("/*") >= 0) return false;
        if (x.matches(".*\\b(new)\\s+[A-Za-z0-9_<>\\[\\].]+\\s*$")) return false;
        if (x.matches(".*\\b(class|interface|enum|record)\\s+[A-Za-z_$][A-Za-z0-9_$]*(?:.*)?")) return true;
        if (x.matches("(?s).*(?:\\)|\\btry|\\belse|\\bfinally|\\bdo|\\bstatic)\\s*$")) return true;
        return x.matches("(?s).*(?:\\bdefault|\\bcase\\s+[^:]+)\\s*:\\s*$");
    }

    static boolean unbracedControl(String previous) {
        if (previous == null) return false;
        String p = previous.trim();
        if (p.endsWith("{") || p.endsWith(";")) return false;
        if (p.equals("else") || p.equals("do")) return true;
        return p.matches("(?:if|for|while|else\\s+if|catch|synchronized)\\s*\\(.*\\)\\s*");
    }

    static String eolFor(String source) { return source.indexOf("\r\n") >= 0 ? "\r\n" : "\n"; }

    /**
     * Expand short inline statement blocks into physical lines without touching
     * any quoted text, block comments, or Java for-loop header semicolons.
     * Kept separate from indentation so selection formatting can retain the
     * exact original line boundaries.
     */
    static String expandStatements(String source) {
        State state = new State();
        String newline = eolFor(source);
        StringBuilder output = new StringBuilder(source.length() + 50);
        List<Line> input = lines(source);
        int parens = 0;
        // Scope stack survives physical lines, including lambdas inside calls.
        ArrayList<Integer> blockParens = new ArrayList<Integer>();
        ArrayList<Boolean> blockKinds = new ArrayList<Boolean>();
        for (Line line : input) {
            String raw = line.text;
            boolean[] mask = codeMask(raw, state);
            StringBuilder expanded = new StringBuilder(raw.length() + 8);
            for (int i = 0; i < raw.length(); i++) {
                char c = raw.charAt(i);
                if (mask[i] && c == '(') parens++;
                else if (mask[i] && c == ')') parens = Math.max(0, parens - 1);
                if (mask[i] && c == '{') {
                    int p = i - 1;
                    while (p >= 0 && Character.isWhitespace(raw.charAt(p))) p--;
                    char prev = p >= 0 ? raw.charAt(p) : 0;
                    boolean block = prev != '=' && prev != '[' && prev != ']' && prev != ',';
                    if (prev == '>' && p > 0 && raw.charAt(p - 1) != '-') block = false;
                    blockParens.add(Integer.valueOf(parens));
                    blockKinds.add(Boolean.valueOf(block));
                    expanded.append(c);
                    int next = i + 1;
                    while (next < raw.length() && (raw.charAt(next) == ' ' || raw.charAt(next) == '\t')) next++;
                    if (block && next < raw.length() && raw.charAt(next) != '}'
                            && !raw.substring(next).startsWith("//")) {
                        expanded.append(newline);
                        i = next - 1;
                    }
                } else if (mask[i] && c == '}') {
                    boolean block = !blockKinds.isEmpty() && blockKinds.remove(blockKinds.size() - 1).booleanValue();
                    if (!blockParens.isEmpty()) blockParens.remove(blockParens.size() - 1);
                    if (block && expanded.length() > 0) {
                        int at = expanded.length()-1;
                        while (at >= 0 && (expanded.charAt(at) == ' ' || expanded.charAt(at) == '\t')) at--;
                        if (at >= 0 && expanded.charAt(at) != '{' && expanded.charAt(at) != '\n') {
                            expanded.append(newline);
                        }
                    }
                    expanded.append(c);
                } else if (mask[i] && c == ';') {
                    expanded.append(c);
                    int next = i + 1;
                    while (next < raw.length() && Character.isWhitespace(raw.charAt(next))) next++;
                    boolean endOfStatement = parens == 0;
                    if (!endOfStatement && !blockKinds.isEmpty()) {
                        int level = blockKinds.size() - 1;
                        endOfStatement = blockKinds.get(level).booleanValue()
                            && parens == blockParens.get(level).intValue();
                    }
                    if (endOfStatement && next < raw.length() && raw.charAt(next) != '}'
                            && !(raw.charAt(next) == '/' && next + 1 < raw.length()
                                && (raw.charAt(next + 1) == '/' || raw.charAt(next + 1) == '*'))) {
                        expanded.append(newline);
                        i = next - 1;
                    }
                } else expanded.append(c);
            }
            output.append(expanded).append(line.eol);
        }
        return output.toString();
    }

    /** Format the entire file, or only selected physical lines when hasSelection is true. */
    public static String format(String source, Style style, int selStart, int selEnd) {
        return format(source, style, selStart, selEnd, false);
    }

    /**
     * Kotlin mode only re-indents and normalises spacing: it never splits lines, never moves braces
     * (lambdas and single-line bodies are common) and has no switch/case rule.
     */
    public static String format(String source, Style style, int selStart, int selEnd, boolean kotlin) {
        if (source == null) return "";
        if (source.length() > 750000) throw new IllegalArgumentException("Formatter limit: 750 KB per file");
        Style opts = style == null ? new Style() : style;
        List<Line> src = lines(source);
        boolean selected = selStart >= 0 && selEnd > selStart;
        if (selected) {
            selStart = Math.max(0, Math.min(source.length(), selStart));
            selEnd = Math.max(selStart, Math.min(source.length(), selEnd));
        } else {
            // For full-file formatting, one-line code blocks and adjacent
            // statements may safely be expanded to separate lines.
            if (!kotlin) {
                source = expandStatements(source);
                src = lines(source);
            }
        }
        String newline = eolFor(source);
        StringBuilder dest = new StringBuilder(source.length() + 50);
        State state = new State();
        int depth = 0, blanks = 0;
        String prevSourceContent = null;
        // The output uses original line terminators. Brace relocation is limited to
        // complete-file operations and straightforward statement/block headers.
        for (int i = 0; i < src.size(); i++) {
            Line line = src.get(i);
            String raw = line.text;
            String eol = line.eol;
            boolean inRange = !selected || (line.start < selEnd && line.start + line.text.length() + eol.length() > selStart);
            State initial = state.copy();
            String withoutIndent = trimStart(raw);
            String trimmed = trimEnd(withoutIndent);
            int closing = leadingClosings(trimmed, initial);
            int lineDepth = Math.max(0, depth - closing);
            if (!kotlin && trimmed.matches("(?s)(case\\s+.+:|default:)\\s*(?:.*)")) lineDepth = Math.max(0, lineDepth - 1);
            // An unbraced if/for/while body is indented one level even though it
            // does not change the brace depth. Preserve wrapped call alignment.
            if (!trimmed.startsWith("{") && !trimmed.startsWith("}")
                    && unbracedControl(prevSourceContent)) lineDepth++;
            if (prevSourceContent != null && !trimmed.startsWith("}")
                    && (prevSourceContent.endsWith("(") || prevSourceContent.endsWith(",")
                         || trimmed.startsWith(".")
                         || (kotlin && (trimmed.startsWith("?.") || trimmed.startsWith("?:")
                             || trimmed.startsWith("&&") || trimmed.startsWith("||")
                             || (prevSourceContent.endsWith("=") && !prevSourceContent.endsWith("==")))))) lineDepth++;
            String produced = raw;
            if (inRange && !startsInLiteral(initial)) {
                if (trimmed.length() == 0) {
                    produced = "";
                    blanks++;
                } else {
                    blanks = 0;
                    String formatted = maskTransform(trimmed, initial, opts);
                    produced = opts.indent(lineDepth) + formatted;
                    // Never move the tokens of array initializers or multi-statement lines.
                    if (!kotlin && !selected && opts.braceNextLine && formatted.endsWith(" {")
                            && formatted.indexOf('{') == formatted.length() - 1
                            && header(formatted.substring(0, formatted.length()-2))) {
                        produced = opts.indent(lineDepth) + trimEnd(formatted.substring(0, formatted.length()-2))
                                + newline + opts.indent(lineDepth) + "{";
                    }
                }
            } else if (trimmed.length() > 0) blanks = 0;
            else blanks++;
            // For same-line braces, merge a standalone '{' with a safe header.
            if (!kotlin && !selected && inRange && !opts.braceNextLine && !startsInLiteral(initial)
                    && trimmed.equals("{") && prevSourceContent != null && header(prevSourceContent)
                    && dest.length() >= newline.length() && dest.substring(dest.length()-newline.length()).equals(newline)) {
                dest.setLength(dest.length() - newline.length());
                dest.append(" {").append(eol);
            } else if (inRange && trimmed.length() == 0 && blanks > Math.max(0, opts.maxBlankLines)) {
                // Avoid destroying an existing trailing newline; skip repeated blank lines.
                if (i == src.size()-1 && eol.length() == 0) { /* no-op */ }
            } else {
                dest.append(produced).append(eol);
            }
            // Only the immediately preceding source line may supply a brace header.
            prevSourceContent = trimmed.length() > 0 ? trimmed : null;
            depth = Math.max(0, depth + braceDelta(raw, state));
        }
        return dest.toString();
    }

    public static Edit indent(String source, int start, int end, Style style, boolean unindent) {
        if (source == null) source = "";
        Style opts = style == null ? new Style() : style;
        int lo = Math.max(0, Math.min(source.length(), start));
        int hi = Math.max(lo, Math.min(source.length(), end));
        boolean selection = hi > lo;
        // If a selected range ends at the start of a new line, leave that line alone.
        if (selection && hi > 0 && source.charAt(hi-1) == '\n') hi--;
        int first = source.lastIndexOf('\n', Math.max(0, lo-1)) + 1;
        int last = source.indexOf('\n', hi);
        if (last == -1) last = source.length();
        String section = source.substring(first, last);
        String[] original = section.split("\\n", -1);
        StringBuilder replacement = new StringBuilder(section.length() + 40);
        String unit = opts.tabs ? "\t" : opts.indent(1);
        for (int i = 0; i < original.length; i++) {
            String s = original[i];
            if (s.trim().length() != 0) {
                if (unindent) {
                    if (s.startsWith("\t")) s = s.substring(1);
                    else {
                        int spaces = 0;
                        while (spaces < s.length() && spaces < opts.indentSize && s.charAt(spaces) == ' ') spaces++;
                        s = s.substring(spaces);
                    }
                } else s = unit + s;
            }
            if (i > 0) replacement.append('\n');
            replacement.append(s);
        }
        String result = source.substring(0, first) + replacement + source.substring(last);
        return new Edit(result, first, first + replacement.length());
    }

    public static Style learn(String src) {
        Style learned = new Style();
        if (src == null) return learned;
        List<Line> lines = lines(src);
        int[] widths = new int[9];
        int tabs = 0, spaces = 0;
        int same = 0, next = 0;
        String previous = "";
        State state = new State();
        for (Line line : lines) {
            String s = line.text;
            if (s.length() == 0) continue;
            int lead = 0;
            while (lead < s.length() && (s.charAt(lead) == ' ' || s.charAt(lead) == '\t')) lead++;
            String code = trimEnd(s.substring(lead));
            if (code.length() == 0) { braceDelta(s, state); continue; }
            if (!startsInLiteral(state)) {
                if (lead > 0) {
                    if (s.charAt(0) == '\t') tabs++;
                    else {
                        spaces++;
                        if (lead >= 2 && lead <= 8) widths[lead]++;
                    }
                }
                if (code.equals("{") && header(previous)) next++;
                else if (code.endsWith("{") && header(code.substring(0, code.length()-1).trim())) same++;
            }
            braceDelta(s, state);
            previous = code;
        }
        learned.tabs = tabs > spaces;
        if (!learned.tabs) {
            int best = 4;
            int score = -1;
            int[] candidates = {2, 4, 8};
            for (int w : candidates) {
                int total = 0;
                for (int i = 1; i < widths.length; i++) if (i % w == 0) total += widths[i];
                if (total > score || (total == score && w == 4)) { score = total; best = w; }
            }
            learned.indentSize = best;
        }
        learned.braceNextLine = next > same;
        return learned;
    }
}
