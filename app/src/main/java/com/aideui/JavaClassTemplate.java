package com.aideui;

import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Safe, dependency-free template for Java classes in both Gradle and classic AIDE projects. */
public final class JavaClassTemplate {
    private JavaClassTemplate() { }

    private static final Set<String> RESERVED = new HashSet<String>(Arrays.asList(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
        "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
        "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
        "interface", "long", "native", "new", "package", "private", "protected", "public",
        "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
        "throw", "throws", "transient", "try", "void", "volatile", "while", "true", "false",
        "null", "_"));

    public static boolean validIdentifier(String value) {
        if (value == null || value.length() == 0 || RESERVED.contains(value)) return false;
        if (!Character.isJavaIdentifierStart(value.charAt(0))) return false;
        for (int i = 1; i < value.length(); i++) {
            if (!Character.isJavaIdentifierPart(value.charAt(i))) return false;
        }
        return true;
    }

    private static final Set<String> KOTLIN_RESERVED = new HashSet<String>(Arrays.asList(
        "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in", "interface",
        "is", "null", "object", "package", "return", "super", "this", "throw", "true", "try", "typealias",
        "typeof", "val", "var", "when", "while", "_"));

    public static boolean validKotlinIdentifier(String value) {
        if (value == null || value.length() == 0 || KOTLIN_RESERVED.contains(value)) return false;
        if (!Character.isJavaIdentifierStart(value.charAt(0))) return false;
        for (int i = 1; i < value.length(); i++) {
            if (!Character.isJavaIdentifierPart(value.charAt(i))) return false;
        }
        return true;
    }

    public static String kotlinSource(String className, String pkg) {
        if (!validKotlinIdentifier(className)) throw new IllegalArgumentException("Invalid Kotlin class name");
        if (pkg == null) throw new IllegalArgumentException("Choose a source folder first");
        StringBuilder content = new StringBuilder();
        if (pkg.length() > 0) content.append("package ").append(pkg).append("\n\n");
        content.append("class ").append(className).append(" {\n\n}\n");
        return content.toString();
    }

    /** Returns null outside a recognized Java source tree; "" means default package. */
    public static String packageForFolder(File folder) {
        if (folder == null) return null;
        String normalized = folder.getAbsolutePath().replace('\\', '/');
        String[] markers = {"/src/main/java", "/src/test/java", "/src/androidTest/java",
            "/src/main/kotlin", "/src/test/kotlin", "/src/androidTest/kotlin", "/src", "/java", "/kotlin"};
        for (String marker : markers) {
            int start = normalized.lastIndexOf(marker);
            if (start < 0) continue;
            int end = start + marker.length();
            if (end != normalized.length() && normalized.charAt(end) != '/') continue;
            String suffix = normalized.substring(end);
            if (suffix.length() == 0) return "";
            String[] segments = suffix.substring(1).split("/");
            for (String segment : segments) if (!validIdentifier(segment)) return null;
            return join(segments);
        }
        return null;
    }

    private static String join(String[] segments) {
        StringBuilder result = new StringBuilder();
        for (String segment : segments) {
            if (result.length() > 0) result.append('.');
            result.append(segment);
        }
        return result.toString();
    }

    public static String source(String className, String pkg) {
        if (!validIdentifier(className)) throw new IllegalArgumentException("Invalid Java class name");
        if (pkg == null) throw new IllegalArgumentException("Choose a Java source folder first");
        StringBuilder content = new StringBuilder();
        if (pkg.length() > 0) content.append("package ").append(pkg).append(";\n\n");
        content.append("public class ").append(className).append(" {\n\n");
        content.append("    public ").append(className).append("() {\n");
        content.append("    }\n\n");
        content.append("}\n");
        return content.toString();
    }
}
