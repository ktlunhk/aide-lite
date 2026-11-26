package com.aideui;

import android.graphics.Color;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;

/** Lightweight, tolerant XML syntax coloring. It does not modify or validate source text. */
public final class XmlSyntaxHighlighter {
    private XmlSyntaxHighlighter() { }

    static final int TAG = Color.rgb(30, 127, 216);      // blue, like the original AIDE XML view
    static final int ATTR = Color.rgb(57, 57, 57);       // dark attribute name
    static final int VALUE = Color.rgb(190, 46, 46);     // quoted attribute values in red
    static final int COMMENT = Color.rgb(46, 139, 58);   // green
    static final int DECL = Color.rgb(130, 77, 160);     // XML declarations / processing instructions
    static final int ENTITY = Color.rgb(13, 133, 148);   // &amp; etc.

    static void span(SpannableStringBuilder b, int begin, int end, int color, boolean bold) {
        if (end <= begin || begin < 0 || end > b.length()) return;
        b.setSpan(new ForegroundColorSpan(color), begin, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (bold) b.setSpan(new StyleSpan(Typeface.BOLD), begin, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    static boolean nameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == ':' || c == '.';
    }

    /** Colors incomplete XML as well as valid documents, without throwing on half-typed tags. */
    public static CharSequence highlight(String xml) {
        if (xml == null) return "";
        if (xml.length() > 300000) return xml;
        SpannableStringBuilder result = new SpannableStringBuilder(xml);
        int len = xml.length();
        int pos = 0;
        while (pos < len) {
            char c = xml.charAt(pos);
            if (c == '&') {
                int semi = xml.indexOf(';', pos + 1);
                if (semi > pos && semi - pos <= 32) {
                    span(result, pos, semi + 1, ENTITY, false);
                    pos = semi + 1;
                    continue;
                }
            }
            if (c != '<') { pos++; continue; }
            if (xml.startsWith("<!--", pos)) {
                int stop = xml.indexOf("-->", pos + 4);
                int end = stop < 0 ? len : stop + 3;
                span(result, pos, end, COMMENT, false);
                pos = end;
                continue;
            }
            if (xml.startsWith("<![CDATA[", pos)) {
                int stop = xml.indexOf("]]>", pos + 9);
                int end = stop < 0 ? len : stop + 3;
                span(result, pos, end, COMMENT, false);
                pos = end;
                continue;
            }
            if (xml.startsWith("<?", pos) || xml.startsWith("<!", pos)) {
                boolean processing = xml.startsWith("<?", pos);
                int stop = xml.indexOf(processing ? "?>" : ">", pos + 2);
                int end = stop < 0 ? len : stop + (processing ? 2 : 1);
                span(result, pos, end, DECL, false);
                pos = end;
                continue;
            }
            int p = pos + 1;
            if (p < len && xml.charAt(p) == '/') p++;
            int nameStart = p;
            while (p < len && nameChar(xml.charAt(p))) p++;
            if (p == nameStart) { pos++; continue; }
            span(result, pos, p, TAG, true);
            while (p < len) {
                char x = xml.charAt(p);
                if (x == '>') {
                    span(result, p, p + 1, TAG, false);
                    p++;
                    break;
                }
                if (x == '/' && p + 1 < len && xml.charAt(p + 1) == '>') {
                    span(result, p, p + 2, TAG, false);
                    p += 2;
                    break;
                }
                if (x == '<') break; // malformed unclosed tag: color next tag independently
                if (Character.isWhitespace(x)) { p++; continue; }
                int a = p;
                while (p < len && nameChar(xml.charAt(p))) p++;
                if (p > a) span(result, a, p, ATTR, false);
                else { p++; continue; }
                while (p < len && Character.isWhitespace(xml.charAt(p))) p++;
                if (p < len && xml.charAt(p) == '=') {
                    span(result, p, p + 1, TAG, false);
                    p++;
                    while (p < len && Character.isWhitespace(xml.charAt(p))) p++;
                    if (p < len && (xml.charAt(p) == '"' || xml.charAt(p) == '\'')) {
                        char quote = xml.charAt(p);
                        int start = p++;
                        while (p < len && xml.charAt(p) != quote && xml.charAt(p) != '<') p++;
                        if (p < len && xml.charAt(p) == quote) p++;
                        span(result, start, p, VALUE, false);
                    }
                }
            }
            pos = Math.max(p, pos + 1);
        }
        return result;
    }
}
