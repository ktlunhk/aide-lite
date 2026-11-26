package com.aideui;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Small, self-contained Java-source refactorer for the AIDE-style file browser.
 * Does not require AndroidX or a network connection.
 * Rename is lexical (NOT a type-resolved Java rename). The UI always previews it.
 */
public final class RefactorEngine {
    private RefactorEngine() { }

    /** Java and Kotlin sources are handled by the same engine. */
    static boolean isSource(String name) { return name.endsWith(".java") || name.endsWith(".kt"); }
    static boolean isKotlin(File f) { return f != null && f.getName().endsWith(".kt"); }
    static String stripExtension(String name) { return name.substring(0, name.lastIndexOf('.')); }

    public static final class Change {
        public final File from, to;
        public final String original, updated;
        public final int occurrences;
        public final boolean created;
        Change(File f, File t, String o, String n, int count, boolean create) {
            from = f; to = t; original = o; updated = n; occurrences = count; created = create;
        }
    }

    public static final class Plan {
        public final List<Change> changes = new ArrayList<Change>();
        public final String description;
        public File openAfter;
        Plan(String d) { description = d; }
        void add(File from, File to, String oldText, String newText, int count) {
            if (!oldText.equals(newText) || !from.equals(to))
                changes.add(new Change(from, to, oldText, newText, count, false));
        }
        void addCreated(File file, String contents) {
            changes.add(new Change(file, file, null, contents, 1, true));
        }
        public int replacements() {
            int n = 0;
            for (Change c : changes) n += c.occurrences;
            return n;
        }
        public String preview() {
            StringBuilder b = new StringBuilder(description);
            b.append("\n\n").append(changes.size()).append(" file(s) affected");
            if (replacements() > 0) b.append(", ").append(replacements()).append(" edits");
            b.append("\n\n");
            int i = 0;
            for (Change c : changes) {
                if (i++ >= 25) { b.append("… and more files\n"); break; }
                b.append(c.created ? "• NEW " : "• ").append(c.from.getAbsolutePath());
                if (!c.from.equals(c.to)) b.append(" → ").append(c.to.getAbsolutePath());
                if (c.occurrences > 0) b.append(" (").append(c.occurrences).append(" edits)");
                b.append('\n');
            }
            // Small before/after samples make potentially unsafe text edits reviewable.
            int samples=0;
            for(Change c:changes) {
                if(samples++ >= 3) { b.append("\nMore files not shown in this preview.\n"); break; }
                String old=c.created ? "" : c.original;
                String newer=c.updated;
                int p=0;
                while(p<old.length() && p<newer.length() && old.charAt(p)==newer.charAt(p)) p++;
                int oldEnd=old.length()-1, newEnd=newer.length()-1;
                while(oldEnd>=p && newEnd>=p && old.charAt(oldEnd)==newer.charAt(newEnd)) {
                    oldEnd--;newEnd--;
                }
                int from=Math.max(0,p-30);
                int uptoOld=Math.min(old.length(),Math.max(p+100,oldEnd+1+30));
                int uptoNew=Math.min(newer.length(),Math.max(p+100,newEnd+1+30));
                uptoOld=Math.min(uptoOld,from+220);
                uptoNew=Math.min(uptoNew,from+220);
                b.append("\nChange sample: ").append(c.to.getName()).append('\n');
                b.append("Before: ").append(old.substring(Math.min(from,old.length()),Math.max(Math.min(from,old.length()),uptoOld))).append('\n');
                b.append("After:  ").append(newer.substring(Math.min(from,newer.length()),Math.max(Math.min(from,newer.length()),uptoNew))).append('\n');
            }
            b.append("\nOriginal files will be backed up beside the source files as .aideui-bak-* before changes are applied.");
            return b.toString();
        }
    }

    static final class Edit {
        final String text;
        final int count;
        Edit(String t, int n) { text = t; count = n; }
    }

    public static boolean identifier(String s) {
        if (s == null || s.isEmpty() || !Character.isJavaIdentifierStart(s.charAt(0))) return false;
        for (int i = 1; i < s.length(); i++) if (!Character.isJavaIdentifierPart(s.charAt(i))) return false;
        return !JAVA_KEYWORDS.contains(s);
    }

    static final Set<String> JAVA_KEYWORDS = new HashSet<String>();
    static {
        String words = "abstract assert boolean break byte case catch char class const continue default do double "
            + "else enum extends final finally float for goto if implements import instanceof int interface long "
            + "native new package private protected public return short static strictfp super switch synchronized "
            + "this throw throws transient try void volatile while true false null";
        for (String s : words.split(" ")) JAVA_KEYWORDS.add(s);
    }

    public static boolean packageName(String value) {
        if (value == null || value.trim().length() == 0) return false;
        for (String part : value.split("\\.", -1)) if (!identifier(part)) return false;
        return true;
    }

    public static String read(File f) throws IOException {
        if (!f.isFile() || f.length() > 2L * 1024 * 1024)
            throw new IOException("Missing or too large source: " + f.getName());
        InputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] bytes = new byte[8192]; int n;
            while ((n = in.read(bytes)) >= 0) if (n > 0) out.write(bytes, 0, n);
            return out.toString("UTF-8");
        } finally { in.close(); }
    }

    static void write(File dest, String content) throws IOException {
        OutputStream out = new FileOutputStream(dest);
        try {
            byte[] bytes = content.getBytes("UTF-8");
            out.write(bytes);
            out.flush();
            ((FileOutputStream) out).getFD().sync();
        } finally { out.close(); }
    }

    static void copy(File src, File target) throws IOException {
        InputStream in = new FileInputStream(src);
        try {
            OutputStream out = new FileOutputStream(target);
            try {
                byte[] buf = new byte[16384]; int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                out.flush(); ((FileOutputStream)out).getFD().sync();
            } finally { out.close(); }
        } finally { in.close(); }
    }

    /** Replace only Java identifiers in code; never in comments, string or character literals. */
    public static Edit replaceIdentifier(String src, String oldName, String newName) {
        return replaceCode(src, oldName, newName, false);
    }

    static Edit replaceCode(String src, String oldWord, String newWord, boolean qualified) {
        boolean[] code = codeMask(src);
        StringBuilder b = new StringBuilder(src.length());
        int n = 0, prev = 0;
        for (int at = src.indexOf(oldWord); at >= 0; at = src.indexOf(oldWord, at + oldWord.length())) {
            int en = at + oldWord.length();
            boolean good = (at == 0 || !Character.isJavaIdentifierPart(src.charAt(at - 1)))
                && (en == src.length() || !Character.isJavaIdentifierPart(src.charAt(en)));
            if (qualified && at > 0 && src.charAt(at - 1) == '.') good = false;
            if (good) for (int i = at; i < en; i++) if (!code[i]) { good = false; break; }
            if (good) {
                b.append(src, prev, at).append(newWord);
                prev = en;
                n++;
            }
        }
        b.append(src, prev, src.length());
        return new Edit(b.toString(), n);
    }

    /** Code-character mask, to prevent refactor edits inside strings or comments. */
    static boolean[] codeMask(String s) {
        boolean[] good = new boolean[s.length()];
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '/') {
                int j = i + 2;
                while (j < s.length() && s.charAt(j) != '\n') j++;
                i = j;
            } else if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '*') {
                int j = s.indexOf("*/", i + 2);
                i = j < 0 ? s.length() : j + 2;
            } else if (c == '"' && s.startsWith("\"\"\"", i)) {
                int j = s.indexOf("\"\"\"", i + 3);
                i = j < 0 ? s.length() : j + 3;
            } else if (c == '"' || c == '\'') {
                char quote = c; i++;
                while (i < s.length()) {
                    if (s.charAt(i) == '\\') i = Math.min(s.length(), i + 2);
                    else if (s.charAt(i++) == quote) break;
                }
            } else { good[i] = true; i++; }
        }
        return good;
    }

    public static String symbolAt(String text, int start, int end) {
        if (text == null || start < 0 || end < start || start > text.length() || end > text.length()) return "";
        if (start == end) {
            if (start == text.length() && start > 0) start--;
            else if (start > 0 && (start == text.length() || !Character.isJavaIdentifierPart(text.charAt(start)))
                    && Character.isJavaIdentifierPart(text.charAt(start - 1))) start--;
            end = start;
            while (start > 0 && Character.isJavaIdentifierPart(text.charAt(start - 1))) start--;
            while (end < text.length() && Character.isJavaIdentifierPart(text.charAt(end))) end++;
        }
        String s = text.substring(start, end);
        if (!identifier(s)) return "";
        boolean[] code = codeMask(text);
        for (int i = start; i < end; i++) if (!code[i]) return "";
        return s;
    }

    static void collectJava(File dir, List<File> files, int depth) {
        if (depth > 35 || files.size() >= 1500) return;
        File[] kids = dir.listFiles(); if (kids == null) return;
        for (File f : kids) {
            if (files.size() >= 1500) break;
            if (f.isDirectory()) {
                String name = f.getName();
                if (!name.equals("build") && !name.equals(".git") && !name.equals(".gradle")
                        && !name.equals("node_modules") && !name.equals(".idea") && !name.equals(".aideui"))
                    collectJava(f, files, depth + 1);
            } else if (isSource(f.getName()) && f.length() <= 2L * 1024 * 1024) files.add(f);
        }
    }

    public static Plan rename(File file, File root, String oldName, String newName, boolean project) throws IOException {
        if (file == null || !file.isFile() || !isSource(file.getName()))
            throw new IOException("Open a Java or Kotlin source file first.");
        if (!identifier(oldName) || !identifier(newName)) throw new IOException("Invalid identifier.");
        if (oldName.equals(newName)) throw new IOException("Old and new names are identical.");
        Plan p = new Plan("Lexical rename: " + oldName + " → " + newName + "\nScope: "
                + (project ? "Java and Kotlin files in current project" : "current file")
                + "\n\nNote: same-named but unrelated symbols can also change. Review before applying.");
        List<File> fs = new ArrayList<File>();
        if (project) collectJava(root, fs, 0);
        else fs.add(file);
        for (File f : fs) {
            String old = read(f);
            Edit e = replaceIdentifier(old, oldName, newName);
            if (isKotlin(f)) {
                // Kotlin string templates ($name, ${name}) live inside string literals.
                Edit t = replaceKotlinTemplates(e.text, oldName, newName);
                e = new Edit(t.text, e.count + t.count);
            }
            File dest = f;
            String ext = f.getName().substring(f.getName().lastIndexOf('.'));
            if (f.getName().equals(oldName + ext))
                dest = new File(f.getParentFile(), newName + ext);
            if (dest.equals(f) && e.count == 0) continue;
            p.add(f, dest, old, e.text, e.count);
            if (file.equals(f)) p.openAfter = dest;
        }
        // Update exact class references in manifest (the path rename is how we identify a class rename).
        if (project && isSource(file.getName())
                && file.getName().equals(oldName + file.getName().substring(file.getName().lastIndexOf('.')))) {
            String pkg = declaredPackage(read(file));
            if (pkg.length() > 0) {
                List<File> manifests = new ArrayList<File>();
                collectManifests(root, manifests, 0);
                for (File manifest : manifests) {
                    if (manifest.length() > 2L * 1024 * 1024) continue;
                    String old = read(manifest);
                    String rewritten = replaceManifestClass(old, pkg + "." + oldName, pkg + "." + newName);
                    p.add(manifest, manifest, old, rewritten, old.equals(rewritten) ? 0 : 1);
                }
            }
        }
        if (p.changes.isEmpty()) throw new IOException("No matching identifier found.");
        return p;
    }

    static String declaredPackage(String s) {
        Matcher m = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)").matcher(s);
        return m.find() ? m.group(1) : "";
    }

    static File findManifest(File root) {
        List<File> candidates = new ArrayList<File>();
        collectManifests(root, candidates, 0);
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    static void collectManifests(File dir, List<File> manifests, int depth) {
        if (depth > 8 || manifests.size() > 20) return;
        File[] list = dir.listFiles(); if (list == null) return;
        for (File f : list) {
            if (f.isFile() && f.getName().equals("AndroidManifest.xml")) manifests.add(f);
            else if (f.isDirectory() && !f.getName().equals("build") && !f.getName().equals(".git"))
                collectManifests(f, manifests, depth + 1);
        }
    }

    static String replaceManifestClass(String xml, String from, String to) {
        // Only change attributes that name Java components, not arbitrary XML text.
        Pattern a = Pattern.compile("((?:android:name|android:targetActivity|android:parentActivityName|tools:context|class)\\s*=\\s*\")([^\"]+)(\")");
        Matcher m = a.matcher(xml); StringBuffer b = new StringBuffer();
        String pkg = "";
        Matcher pm = Pattern.compile("<manifest\\b[^>]*\\bpackage\\s*=\\s*\"([^\"]+)\"").matcher(xml);
        if (pm.find()) pkg = pm.group(1);
        while (m.find()) {
            String val = m.group(2), target = val;
            if (val.equals(from)) target = to;
            else if (val.equals("." + from.substring(from.lastIndexOf('.') + 1))
                    && pkg.equals(from.substring(0, from.lastIndexOf('.')))) target = to;
            m.appendReplacement(b, Matcher.quoteReplacement(m.group(1) + target + m.group(3)));
        }
        m.appendTail(b); return b.toString();
    }

    static File javaSourceRoot(File javaFile) {
        for (File dir = javaFile.getParentFile(); dir != null; dir = dir.getParentFile()) {
            File main = dir.getParentFile();
            if ((dir.getName().equals("java") || dir.getName().equals("kotlin")) && main != null && main.getName().equals("main")
                && main.getParentFile() != null && main.getParentFile().getName().equals("src")) return dir;
        }
        return null;
    }

    public static Plan move(File file, File root, String destinationPackage) throws IOException {
        if (file == null || !file.isFile() || !isSource(file.getName()))
            throw new IOException("Open a Java or Kotlin source file first.");
        if (!packageName(destinationPackage)) throw new IOException("Invalid destination package.");
        File srcRoot = javaSourceRoot(file);
        if (srcRoot == null) throw new IOException("Move requires a file inside src/main/java or src/main/kotlin.");
        String original = read(file), oldPkg = declaredPackage(original);
        if (oldPkg.length() == 0) throw new IOException("Move requires a named source package.");
        if (oldPkg.equals(destinationPackage)) throw new IOException("Already in that package.");
        final boolean kotlin = isKotlin(file);
        String name = stripExtension(file.getName());
        File dest = new File(srcRoot, destinationPackage.replace('.', File.separatorChar) + File.separator + file.getName());
        if (!within(srcRoot, dest)) throw new IOException("Invalid destination path.");
        if (dest.exists()) throw new IOException("Destination file already exists: " + dest);
        String before = oldPkg + "." + name, after = destinationPackage + "." + name;
        Plan p = new Plan("Move class " + before + " → " + after
            + "\n\nUpdates package declarations, explicit imports, qualified Java references, and component names in AndroidManifest.xml."
            + "\n\nCheck wildcard imports, reflection strings, resources, and Gradle configuration after moving.");
        Matcher packageMatcher = Pattern.compile("(?m)^(\\s*package\\s+)([\\w.]+)([ \\t]*;?)").matcher(original);
        String moved = packageMatcher.replaceFirst("$1" + Matcher.quoteReplacement(destinationPackage) + "$3");
        Edit selfReferences = replaceCode(moved, before, after, true);
        moved = selfReferences.text;
        // A class leaving its package needs imports for R/BuildConfig and other accessible
        // source types it used to see without imports. This is intentionally conservative.
        if (countCode(moved, "R") > 0 && !hasImport(moved, oldPkg + ".R"))
            moved = insertImport(moved, oldPkg + ".R", kotlin);
        if (countCode(moved, "BuildConfig") > 0 && !hasImport(moved, oldPkg + ".BuildConfig"))
            moved = insertImport(moved, oldPkg + ".BuildConfig", kotlin);
        File samePackageDir = new File(srcRoot, oldPkg.replace('.', File.separatorChar));
        File[] peers = samePackageDir.listFiles();
        if (peers != null) for (File peer : peers) {
            if (!peer.equals(file) && peer.isFile() && isSource(peer.getName())
                    && peer.length() < 2L * 1024 * 1024) {
                String type = stripExtension(peer.getName());
                if (identifier(type) && countCode(moved, type) > 0
                        && !hasImport(moved, oldPkg + "." + type))
                    moved = insertImport(moved, oldPkg + "." + type, kotlin);
            }
        }
        p.add(file, dest, original, moved, 1 + selfReferences.count);
        p.openAfter = dest;
        List<File> files = new ArrayList<File>();
        collectJava(root, files, 0);
        for (File f : files) {
            if (f.equals(file)) continue;
            String old = read(f);
            Edit e = replaceCode(old, before, after, true);
            String next = e.text;
            // Files previously in the same package could use the class without an import.
            boolean peerKotlin = isKotlin(f);
            if (declaredPackage(old).equals(oldPkg) && countCode(next, name) > 0
                    && !hasImport(next, after))
                next = insertImport(next, after, peerKotlin);
            // Wildcard import users may also need an explicit import for the relocated type.
            if (hasImport(old, oldPkg + ".*") && countCode(next, name) > 0
                    && !hasImport(next, after))
                next = insertImport(next, after, peerKotlin);
            p.add(f, f, old, next, e.count + (next.equals(e.text) ? 0 : 1));
        }
        List<File> manifests = new ArrayList<File>();
        collectManifests(root, manifests, 0);
        for (File manifest : manifests) {
            if (manifest.length() > 2L * 1024 * 1024) continue;
            String old = read(manifest);
            String next = replaceManifestClass(old, before, after);
            p.add(manifest, manifest, old, next, next.equals(old) ? 0 : 1);
        }
        return p;
    }

    static int countCode(String source, String id) { return replaceIdentifier(source, id, id + "_unused_").count; }

    static boolean hasImport(String source, String fq) {
        return Pattern.compile("(?m)^[ \\t]*import[ \\t]+" + Pattern.quote(fq) + "[ \\t]*;?[ \\t]*(?:as\\b.*)?$").matcher(source).find();
    }

    static String insertImport(String source, String fq) { return insertImport(source, fq, false); }

    static String insertImport(String source, String fq, boolean kotlin) {
        String semi = kotlin ? "" : ";";
        Matcher imported = Pattern.compile("(?m)^\\s*import\\s+(?:static\\s+)?[\\w.*]+[ \\t]*;?[^\\n]*").matcher(source);
        int after = -1;
        while (imported.find()) after = imported.end();
        if (after >= 0) return source.substring(0, after) + "\nimport " + fq + semi + source.substring(after);
        Matcher pm = Pattern.compile("(?m)^\\s*package\\s+[\\w.]+[ \\t]*;?").matcher(source);
        if (pm.find()) return source.substring(0, pm.end()) + "\n\nimport " + fq + semi + source.substring(pm.end());
        return "import " + fq + semi + "\n" + source;
    }

    /** Renames $name and ${name…} inside Kotlin string literals; code outside strings is untouched. */
    static Edit replaceKotlinTemplates(String src, String oldName, String newName) {
        boolean[] code = codeMask(src);
        Matcher m = Pattern.compile("(?<!\\\\)\\$(?:\\{([^{}\\n]*)\\}|([A-Za-z_][A-Za-z0-9_]*))").matcher(src);
        StringBuilder out = new StringBuilder(src.length());
        int prev = 0, count = 0;
        while (m.find()) {
            if (m.start() < code.length && code[m.start()]) continue;   // real code, not a template
            String replacement;
            if (m.group(1) != null) {
                Edit inner = replaceCode(m.group(1), oldName, newName, true);
                // replaceCode masks quotes; an expression like ${name} has no quotes, so it is safe here.
                if (inner.count == 0) continue;
                replacement = "${" + inner.text + "}";
                count += inner.count;
            } else {
                if (!m.group(2).equals(oldName)) continue;
                replacement = "$" + newName;
                count++;
            }
            out.append(src, prev, m.start()).append(replacement);
            prev = m.end();
        }
        out.append(src, prev, src.length());
        return new Edit(out.toString(), count);
    }

    /** Kotlin: nearest enclosing "fun" body, or -1. */
    static int findEnclosingFunBrace(String src, int offset) {
        boolean[] good = codeMask(src);
        List<Integer> stack = new ArrayList<Integer>();
        for (int i = 0; i < offset && i < src.length(); i++) if (good[i]) {
            if (src.charAt(i) == '{') stack.add(i);
            else if (src.charAt(i) == '}' && !stack.isEmpty()) stack.remove(stack.size() - 1);
        }
        for (int k = stack.size() - 1; k >= 0; k--) {
            int brace = stack.get(k);
            int from = Math.max(0, brace - 600);
            String head = src.substring(from, brace);
            int cut = Math.max(head.lastIndexOf('}'), Math.max(head.lastIndexOf('{'), head.lastIndexOf(";\n")));
            if (cut >= 0) head = head.substring(cut + 1);
            if (Pattern.compile("\\bfun\\b").matcher(head).find()
                    && !Pattern.compile("\\b(class|interface|object)\\b").matcher(head).find()) return brace;
        }
        return -1;
    }

    /** Conservative extraction of complete selected lines from inside a normal method body. */
    public static Plan extract(File file, int start, int end, String name, String parameters) throws IOException {
        if (file == null || !file.isFile() || !isSource(file.getName()))
            throw new IOException("Open a Java or Kotlin source file first.");
        final boolean kotlin = isKotlin(file);
        if (!identifier(name)) throw new IOException("Invalid method name.");
        String src = read(file);
        if (start < 0 || end <= start || end > src.length())
            throw new IOException("Select complete Java statement lines before extracting.");
        int firstLine = src.lastIndexOf('\n', start - 1) + 1;
        int lastChar = end - 1;
        while (lastChar >= start && Character.isWhitespace(src.charAt(lastChar))) lastChar--;
        if (lastChar < start) throw new IOException("Selection is empty.");
        int lastLineEnd = src.indexOf('\n', lastChar + 1);
        if (lastLineEnd < 0) lastLineEnd = src.length();
        if (!src.substring(firstLine, start).trim().isEmpty()
                || !src.substring(lastChar + 1, lastLineEnd).trim().isEmpty())
            throw new IOException("Select whole statements, beginning and ending at line boundaries.");
        String selected = src.substring(firstLine, lastLineEnd);
        String trimmed = selected.trim();
        if (!kotlin && !trimmed.endsWith(";") && !trimmed.endsWith("}"))
            throw new IOException("Selection must finish with ';' or '}'.");
        boolean[] mask = codeMask(selected);
        int brace = 0;
        for (int i = 0; i < selected.length(); i++) if (mask[i]) {
            char c = selected.charAt(i);
            if (c == '{') brace++;
            if (c == '}' && --brace < 0) throw new IOException("Selection has unmatched braces.");
        }
        if (brace != 0) throw new IOException("Selection has unmatched braces.");
        for (String keyword : kotlin ? new String[]{"return", "break", "continue"}
                : new String[]{"return", "break", "continue", "yield", "case", "default"}) {
            if (countCode(selected, keyword) > 0)
                throw new IOException("Cannot extract statements containing '" + keyword + "'.");
        }
        int methodOpen = kotlin ? findEnclosingFunBrace(src, firstLine) : findEnclosingMethodBrace(src, firstLine);
        if (methodOpen < 0) throw new IOException(kotlin ? "Selection must be inside an ordinary Kotlin function."
            : "Selection must be inside an ordinary Java method or constructor.");
        int methodEnd = matchingBrace(src, methodOpen);
        if (methodEnd <= lastLineEnd) throw new IOException("Selection must be fully inside the method.");
        String[] arguments = kotlin ? parseKotlinParameterNames(parameters) : parseParameterNames(parameters);
        StringBuilder call = new StringBuilder(name).append('(');
        for (int i = 0; i < arguments.length; i++) {
            if (i > 0) call.append(", ");
            call.append(arguments[i]);
        }
        call.append(kotlin ? ")" : ");");
        String lineIndent = indent(src, firstLine);
        String methodIndent = indent(src, src.lastIndexOf('\n', methodOpen - 1) + 1);
        String body = reindent(selected, methodIndent + "    ");
        String newline = src.contains("\r\n") ? "\r\n" : "\n";
        String replacement = lineIndent + call.toString();
        // The original newline after the final selected line is preserved.
        String withCall = src.substring(0, firstLine) + replacement + src.substring(lastLineEnd);
        int newMethodEnd = methodEnd + replacement.length() - (lastLineEnd - firstLine);
        String addition = newline + newline + methodIndent + (kotlin ? "private fun " : "private void ") + name
            + "(" + parameters.trim() + ") {" + newline + body + newline + methodIndent + "}";
        String updated = withCall.substring(0, newMethodEnd + 1) + addition + withCall.substring(newMethodEnd + 1);
        Plan p = new Plan("Extract selected statements as " + (kotlin ? "private fun " : "private void ") + name + "(…)."
            + "\n\nParameters are supplied manually. The extractor does not automatically detect local-variable dependencies."
            + " Review the result and compiler diagnostics before keeping it.");
        p.add(file, file, src, updated, 2);
        p.openAfter = file;
        return p;
    }

    /** Kotlin parameters look like "count: Int, label: String"; commas inside <> () [] are respected. */
    static String[] parseKotlinParameterNames(String parameters) throws IOException {
        String s = parameters.trim();
        if (s.isEmpty()) return new String[0];
        List<String> parts = new ArrayList<String>();
        int depth = 0, begin = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '<' || c == '(' || c == '[') depth++;
            else if (c == '>' || c == ')' || c == ']') depth--;
            else if (c == ',' && depth == 0) { parts.add(s.substring(begin, i)); begin = i + 1; }
        }
        parts.add(s.substring(begin));
        String[] names = new String[parts.size()];
        for (int i = 0; i < names.length; i++) {
            Matcher m = Pattern.compile("^\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*:\\s*\\S.*$").matcher(parts.get(i));
            if (!m.matches() || !identifier(m.group(1)))
                throw new IOException("Use Kotlin parameters such as 'count: Int, label: String'.");
            names[i] = m.group(1);
        }
        return names;
    }

    static String[] parseParameterNames(String parameters) throws IOException {
        String s = parameters.trim();
        if (s.isEmpty()) return new String[0];
        String[] parts = s.split(",");
        String[] names = new String[parts.length];
        for (int i = 0; i < parts.length; i++) {
            Matcher m = Pattern.compile("^\\s*([\\w.$<>?\\[\\]]+)\\s+([A-Za-z_$][\\w$]*)\\s*$").matcher(parts[i]);
            if (!m.matches() || !identifier(m.group(2)))
                throw new IOException("Use parameters such as 'int count, String label' (no generic commas).");
            names[i] = m.group(2);
        }
        return names;
    }

    static String indent(String src, int lineStart) {
        int j = lineStart;
        while (j < src.length() && (src.charAt(j) == ' ' || src.charAt(j) == '\t')) j++;
        return src.substring(lineStart, j);
    }

    static String reindent(String selected, String desired) {
        String[] lines = selected.replace("\r\n", "\n").split("\n", -1);
        int common = Integer.MAX_VALUE;
        for (String line : lines) if (!line.trim().isEmpty()) {
            int n = 0;
            while (n < line.length() && (line.charAt(n) == ' ' || line.charAt(n) == '\t')) n++;
            common = Math.min(common, n);
        }
        if (common == Integer.MAX_VALUE) common = 0;
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) result.append('\n');
            if (lines[i].trim().isEmpty()) continue;
            result.append(desired).append(lines[i].substring(Math.min(lines[i].length(), common)));
        }
        // Do not copy trailing blank lines from a line selection.
        return result.toString().replaceAll("\\n+$", "");
    }

    static int findEnclosingMethodBrace(String src, int offset) {
        boolean[] good = codeMask(src);
        List<Integer> stack = new ArrayList<Integer>();
        for (int i = 0; i < offset; i++) if (good[i]) {
            if (src.charAt(i) == '{') stack.add(i);
            else if (src.charAt(i) == '}' && !stack.isEmpty()) stack.remove(stack.size() - 1);
        }
        for (int k = stack.size() - 1; k >= 0; k--)
            if (isMethodBrace(src, good, stack.get(k))) return stack.get(k);
        return -1;
    }

    static boolean isMethodBrace(String src, boolean[] good, int brace) {
        int i = brace - 1;
        while (i >= 0 && Character.isWhitespace(src.charAt(i))) i--;
        if (i < 0) return false;
        // Optional throws clause.
        if (src.charAt(i) != ')') {
            int boundary = i;
            while (boundary >= 0 && src.charAt(boundary) != ')' && src.charAt(boundary) != ';'
                && src.charAt(boundary) != '{' && src.charAt(boundary) != '}') boundary--;
            if (boundary < 0 || src.charAt(boundary) != ')'
                || !src.substring(boundary + 1, i + 1).trim().startsWith("throws ")) return false;
            i = boundary;
        }
        int level = 1, close = i; i--;
        while (i >= 0 && level > 0) {
            if (good[i]) {
                if (src.charAt(i) == ')') level++;
                if (src.charAt(i) == '(') level--;
            }
            if (level != 0) i--;
        }
        if (i < 0) return false;
        int endName = i - 1;
        while (endName >= 0 && Character.isWhitespace(src.charAt(endName))) endName--;
        int startName = endName;
        while (startName >= 0 && Character.isJavaIdentifierPart(src.charAt(startName))) startName--;
        String method = src.substring(startName + 1, endName + 1);
        if (!identifier(method)) return false;
        if ("if for while switch catch synchronized try do new".contains(" " + method + " ")) return false;
        // An annotation / method invocation / lambda is not a declaration.
        if (startName >= 0 && src.charAt(startName) == '.') return false;
        int tokenStart = startName;
        while (tokenStart >= 0 && Character.isWhitespace(src.charAt(tokenStart))) tokenStart--;
        if (tokenStart >= 1 && src.charAt(tokenStart) == '>' && src.charAt(tokenStart - 1) == '-') return false;
        return close > i;
    }

    static int matchingBrace(String src, int open) {
        boolean[] good = codeMask(src); int depth = 0;
        for (int i = open; i < src.length(); i++) if (good[i]) {
            if (src.charAt(i) == '{') depth++;
            if (src.charAt(i) == '}' && --depth == 0) return i;
        }
        return -1;
    }

    static boolean within(File dir, File candidate) throws IOException {
        String root = dir.getCanonicalPath() + File.separator;
        return candidate.getCanonicalPath().startsWith(root);
    }

    /**
     * Backs up every original before touching the project. Stages all outputs first.
     * If a commit fails, attempts to recover all touched files from backups.
     */
    public static void apply(Plan plan) throws IOException {
        if (plan.changes.isEmpty()) throw new IOException("There are no changes.");
        String suffix = ".aideui-bak-" + System.currentTimeMillis();
        List<File> backups = new ArrayList<File>();
        List<File> staging = new ArrayList<File>();
        List<Change> staged = new ArrayList<Change>();
        try {
            Set<String> destinations = new HashSet<String>();
            // Validate ALL targets and files before writing any output.
            for (Change c : plan.changes) {
                if (!destinations.add(c.to.getCanonicalPath()))
                    throw new IOException("Two edits target the same file: " + c.to);
                if (c.created) {
                    if (c.to.exists()) throw new IOException("New target already exists: " + c.to);
                } else {
                    if (!c.from.isFile() || !read(c.from).equals(c.original))
                        throw new IOException("Source changed since preview: " + c.from);
                    if (!c.from.equals(c.to) && c.to.exists())
                        throw new IOException("Destination already exists: " + c.to);
                }
            }
            // Backup each existing source, then create a temp file in each target folder.
            for (Change c : plan.changes) {
                File parent = c.to.getParentFile();
                if (!parent.isDirectory() && !parent.mkdirs())
                    throw new IOException("Could not create folder: " + parent);
                File backup = null;
                if (!c.created) {
                    backup = new File(c.from.getAbsolutePath() + suffix);
                    if (backup.exists()) throw new IOException("Backup name collision: " + backup);
                    copy(c.from, backup);
                }
                backups.add(backup);
                File temp = new File(c.to.getAbsolutePath() + ".aideui-stage-" + System.currentTimeMillis() + "-" + staging.size());
                staging.add(temp);
                staged.add(c);
                write(temp, c.updated);
            }
            // Commit. New-resource files are created alongside ordinary source edits.
            for (int i=0; i<staged.size(); i++) {
                Change c=staged.get(i);
                if (!staging.get(i).renameTo(c.to)) throw new IOException("Could not save " + c.to.getName());
            }
            // After commit, remove old paths from file moves.
            for (Change c : staged) if (!c.created && !c.from.equals(c.to) && !c.from.delete())
                throw new IOException("Could not remove old path: " + c.from);
        } catch (IOException failure) {
            for (int i=staged.size()-1; i>=0; i--) {
                Change c=staged.get(i);
                File backup=backups.get(i);
                try {
                    if (c.created) {
                        if (c.to.exists()) c.to.delete();
                    } else if (backup != null && backup.exists()) {
                        if (!c.from.equals(c.to) && c.to.exists()) c.to.delete();
                        copy(backup,c.from);
                    }
                } catch (Exception ignored) { }
            }
            throw failure;
        } finally {
            for (File temp : staging) if (temp.exists()) temp.delete();
        }
    }
}
