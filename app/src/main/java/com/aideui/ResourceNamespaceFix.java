package com.aideui;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Fixes only the compile-time R import when a Java package differs from Gradle namespace.
 *  It NEVER changes the user's source file. */
final class ResourceNamespaceFix {
    private static final Pattern PACKAGE = Pattern.compile("(?m)^\\s*package\\s+([A-Za-z_][\\w]*(?:\\.[A-Za-z_][\\w]*)*)\\s*;");
    private static final Pattern R_USE = Pattern.compile("(?<![\\w.])R\\.[a-zA-Z_][\\w]*\\.[a-zA-Z_][\\w]*");
    private static final Pattern EXPLICIT_R = Pattern.compile("(?m)^\\s*import\\s+(?:static\\s+)?[\\w.]+\\.R(?:\\.[\\w*]+)?\\s*;");
    private static final Pattern OWN_R = Pattern.compile("\\b(?:class|interface|enum)\\s+R\\b");

    static String packageName(String text) {
        Matcher m = PACKAGE.matcher(text);
        return m.find() ? m.group(1) : null;
    }
    static boolean needsImport(String text, String namespace) {
        String pkg = packageName(text);
        return namespace != null && pkg != null && !namespace.equals(pkg)
            && R_USE.matcher(text).find() && !EXPLICIT_R.matcher(text).find()
            && !OWN_R.matcher(text).find();
    }
    /** One import statement is inserted immediately after the package line. */
    static int packageEndLine(String text) {
        Matcher m = PACKAGE.matcher(text);
        if (!m.find()) return 0;
        int line = 1;
        for (int i = 0; i < m.end(); i++) if (text.charAt(i) == '\n') line++;
        return line;
    }
    static String withRImport(String text, String namespace) {
        if (!needsImport(text, namespace)) return text;
        Matcher m = PACKAGE.matcher(text);
        if (!m.find()) return text;
        // Insert an import without changing any existing source statements.
        String nl = text.contains("\r\n") ? "\r\n" : "\n";
        return text.substring(0, m.end()) + nl + "import " + namespace + ".R;"
            + text.substring(m.end());
    }
    static File prepareSource(File original, File sourceRoot, File scratch, String namespace) throws IOException {
        if (original == null || !original.getName().endsWith(".java") || !original.isFile()) return original;
        FileInputStream in = new FileInputStream(original);
        byte[] contents;
        try {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16384]; int n;
            while ((n = in.read(buf)) > 0) bytes.write(buf, 0, n);
            contents = bytes.toByteArray();
        } finally { in.close(); }
        String input = new String(contents, "UTF-8");
        if (!needsImport(input, namespace)) return original;
        String relative = original.getCanonicalPath().substring(sourceRoot.getCanonicalPath().length() + 1);
        File target = new File(scratch, relative);
        File parent = target.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create compile-only directory: " + parent);
        FileOutputStream out = new FileOutputStream(target);
        try { out.write(withRImport(input, namespace).getBytes("UTF-8")); } finally { out.close(); }
        return target;
    }
}
