package com.aideui;

import android.graphics.Color;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import java.util.HashSet;
import java.util.Arrays;
import java.util.Set;

/** Lightweight Kotlin editing colors; this is not a compiler or diagnostic engine. */
public final class KotlinSyntaxHighlighter {
    private KotlinSyntaxHighlighter() {}
    private static final int KEYWORD = Color.rgb(25, 119, 195);
    private static final int LITERAL = Color.rgb(182, 45, 45);
    private static final int COMMENT = Color.rgb(45, 140, 70);
    private static final int TYPE = Color.rgb(15, 135, 155);
    private static final Set<String> WORDS = new HashSet<String>(Arrays.asList(
        "package", "import", "as", "class", "interface", "object", "fun", "val", "var",
        "typealias", "constructor", "init", "this", "super", "override", "open", "abstract",
        "private", "protected", "public", "internal", "data", "sealed", "enum", "annotation",
        "companion", "inline", "noinline", "crossinline", "reified", "suspend", "tailrec",
        "operator", "infix", "lateinit", "const", "by", "get", "set", "field", "where",
        "if", "else", "when", "for", "while", "do", "return", "break", "continue",
        "try", "catch", "finally", "throw", "is", "in", "out", "null", "true", "false",
        "typeof", "dynamic", "actual", "expect", "external"));
    private static final Set<String> TYPES = new HashSet<String>(Arrays.asList(
        "String", "Int", "Long", "Short", "Byte", "Double", "Float", "Boolean", "Char",
        "Unit", "Any", "Nothing", "Array", "List", "MutableList", "Map", "MutableMap", "Set"));
    private static void paint(SpannableStringBuilder b, int a, int z, int color, boolean bold) {
        if (z <= a) return;
        b.setSpan(new ForegroundColorSpan(color), a, z, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (bold) b.setSpan(new StyleSpan(Typeface.BOLD), a, z, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }
    public static CharSequence highlight(String s) {
        if (s == null) return "";
        if (s.length() > 300000) return s;
        SpannableStringBuilder b = new SpannableStringBuilder(s);
        int n = s.length();
        for (int i = 0; i < n;) {
            int start = i;
            if (s.startsWith("//", i)) {
                i += 2; while (i < n && s.charAt(i) != '\n') i++;
                paint(b, start, i, COMMENT, false);
            } else if (s.startsWith("/*", i)) {
                i += 2; int depth = 1;
                while (i < n && depth > 0) {
                    if (s.startsWith("/*", i)) { depth++; i += 2; }
                    else if (s.startsWith("*/", i)) { depth--; i += 2; }
                    else i++;
                }
                paint(b, start, i, COMMENT, false);
            } else if (s.startsWith("\"\"\"", i)) {
                i += 3; int end = s.indexOf("\"\"\"", i);
                i = end < 0 ? n : end + 3;
                paint(b, start, i, LITERAL, false);
            } else if (s.charAt(i) == '\"' || s.charAt(i) == '\'') {
                char quote = s.charAt(i++);
                while (i < n) {
                    char c = s.charAt(i++);
                    if (c == '\\' && i < n) i++;
                    else if (c == quote) break;
                    else if (c == '\n') break;
                }
                paint(b, start, i, LITERAL, false);
            } else if (Character.isJavaIdentifierStart(s.charAt(i))) {
                i++; while (i < n && Character.isJavaIdentifierPart(s.charAt(i))) i++;
                String word = s.substring(start, i);
                if (WORDS.contains(word)) paint(b, start, i, KEYWORD, true);
                else if (TYPES.contains(word)) paint(b, start, i, TYPE, false);
            } else i++;
        }
        return b;
    }
}
