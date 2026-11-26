package com.aideui;

import org.xml.sax.InputSource;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

/** Live, read-only well-formedness checks on the unsaved XML editor buffer.
 * No file IO and no changes to XML or the project. */
public final class XmlAnalyzer {
    private XmlAnalyzer() { }

    public static List<JavaAnalyzer.Err> analyze(String xml) {
        List<JavaAnalyzer.Err> errors = new ArrayList<JavaAnalyzer.Err>();
        if (xml == null || xml.trim().length() == 0) return errors; // new empty file
        // Avoid blocking the editor on huge XML files.
        if (xml.length() > 1024 * 1024) return errors;
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setValidating(false);
            // External entities and DTDs must never be fetched by the editor.
            try { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); }
            catch (Exception ignored) { }
            try { factory.setFeature("http://xml.org/sax/features/external-general-entities", false); }
            catch (Exception ignored) { }
            try { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false); }
            catch (Exception ignored) { }
            SAXParser parser = factory.newSAXParser();
            InputSource source = new InputSource(new StringReader(xml));
            parser.parse(source, new DefaultHandler());
        } catch (SAXParseException ex) {
            int line = Math.max(1, ex.getLineNumber());
            int column = Math.max(1, ex.getColumnNumber());
            int offset = lineColumnToOffset(xml, line, column);
            String message = ex.getMessage();
            if (message == null || message.trim().isEmpty()) message = "Invalid XML syntax";
            JavaAnalyzer.Err error = new JavaAnalyzer.Err(offset,
                    Math.min(xml.length(), offset + 1), "XML: " + message);
            error.line = line;
            errors.add(error);
        } catch (Exception ex) {
            JavaAnalyzer.Err error = new JavaAnalyzer.Err(0,
                    Math.min(xml.length(), 1), "XML validation failed: " + ex.getMessage());
            error.line = 1;
            errors.add(error);
        }
        return errors;
    }

    static int lineColumnToOffset(String text, int line, int column) {
        int offset = 0;
        int currentLine = 1;
        while (offset < text.length() && currentLine < line) {
            if (text.charAt(offset++) == '\n') currentLine++;
        }
        // Column numbers are 1-based and refer to the parser position.
        int lineEnd = text.indexOf('\n', offset);
        if (lineEnd < 0) lineEnd = text.length();
        return Math.min(lineEnd, offset + Math.max(0, column - 1));
    }
}
