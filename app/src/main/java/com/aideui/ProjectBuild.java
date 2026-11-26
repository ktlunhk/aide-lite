package com.aideui;

import android.content.Context;
import android.util.Xml;

import org.eclipse.jdt.core.compiler.CompilationProgress;
import org.eclipse.jdt.core.compiler.batch.BatchCompiler;
import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Real project-wide ECJ Java diagnostic pass for the Build button.
 * It checks the selected Gradle module's Java sources, but DOES NOT run AAPT,
 * D8/Dex, Gradle, APK signing, installation or application launch.
 */
public final class ProjectBuild {
    private ProjectBuild() { }

    public static final class Problem {
        public final String path, message;
        public final int line;
        public final boolean warning;
        Problem(String path, int line, String message, boolean warning) {
            this.path = path; this.line = line; this.message = message; this.warning = warning;
        }
        public String toString() {
            String name = path == null ? "Project" : new File(path).getName();
            return (warning ? "Warning" : "Error") + " · " + name + ":" + line + " · " + message;
        }
    }

    public static final class Result {
        public final List<Problem> problems = new ArrayList<Problem>();
        public String status = "";
        public int sourceCount, errors, warnings;
        public boolean cancelled, passed;
        public File apk;
        public String diagnostics = "";
        public String display() {
            StringBuilder s = new StringBuilder();
            s.append(status).append("\n\n");
            s.append(sourceCount).append(" Java file(s); ").append(errors).append(" error(s); ")
                .append(warnings).append(" warning(s).\n\n");
            if (apk != null) s.append("Signed debug APK: ").append(apk.getAbsolutePath()).append("\n");
            else s.append("No installable APK was produced.\n");
            if (diagnostics.length() > 0) s.append("\nBuild failure details:\n").append(diagnostics).append("\n");
            int cap = Math.min(60, problems.size());
            for (int i = 0; i < cap; i++) s.append("\n").append(problems.get(i));
            if (problems.size() > cap) s.append("\n... ").append(problems.size() - cap).append(" more");
            return s.toString();
        }
    }

    public interface Progress {
        void update(String status, int percent);
        boolean isCancelled();
    }

    static void checkStopped(Progress progress) throws InterruptedException {
        if (progress.isCancelled() || Thread.currentThread().isInterrupted())
            throw new InterruptedException("Build stopped");
    }

    /** Rejects paths outside module and source trees. Does not follow symbolic directory links. */
    static void collect(File root, File dir, List<File> out, Set<String> seen, int max) throws Exception {
        collect(root, dir, out, seen, max, 1024L * 1024L);
    }

    /** maxBytes lets generated sources (R.java of a Material/Compose app is several MB) through. */
    static void collect(File root, File dir, List<File> out, Set<String> seen, int max, long maxBytes) throws Exception {
        if (!dir.isDirectory() || out.size() >= max) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (out.size() >= max) return;
            String canonical = f.getCanonicalPath();
            if (!canonical.startsWith(root.getCanonicalPath() + File.separator)) continue;
            if (!seen.add(canonical)) continue;
            if (f.isDirectory()) {
                if (!f.getName().startsWith(".")) collect(root, f, out, seen, max, maxBytes);
            } else if (f.getName().endsWith(".java") && f.length() < maxBytes) out.add(f);
        }
    }

    public static List<File> sources(File module, String variant) throws Exception {
        List<File> files = new ArrayList<File>();
        Set<String> seen = new HashSet<String>();
        List<String> roots = sourceRoots(module, variant);
        for (String relative : roots) collect(module, new File(module, relative), files, seen, 3000);
        Collections.sort(files, new Comparator<File>() {
            public int compare(File a, File b) { return a.getPath().compareTo(b.getPath()); }
        });
        return files;
    }

    static List<String> sourceRoots(File module, String variant) {
        ArrayList<String> roots = new ArrayList<String>();
        roots.add("src/main/java");
        String selected = variant == null || !variant.matches("[A-Za-z0-9_]{1,70}") ? "debug" : variant;
        roots.add("src/" + selected + "/java");
        roots.add("build/generated/source/r/" + selected);
        roots.add("build/generated/source/buildConfig/" + selected);
        roots.add("build/gen");
        roots.add("gen");
        try {
            for (ProjectActions.Variant choice : ProjectActions.variants(module)) {
                if (!choice.name.equals(selected)) continue;
                roots.add("src/" + choice.buildType + "/java");
                if (choice.flavor.length() > 0) roots.add("src/" + choice.flavor + "/java");
            }
        } catch (Exception ignored) { }
        return roots;
    }

    static List<Problem> parseProblems(File log) throws Exception {
        ArrayList<Problem> problems = new ArrayList<Problem>();
        if (!log.isFile() || log.length() == 0) return problems;
        FileInputStream in = new FileInputStream(log);
        InputStreamReader reader = new InputStreamReader(in, "UTF-8");
        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(reader);
            String path = null, msg = null;
            int line = 1;
            boolean warning = false, inside = false;
            for (int event = parser.getEventType(); event != XmlPullParser.END_DOCUMENT; event = parser.next()) {
                if (event == XmlPullParser.START_TAG) {
                    String tag = parser.getName();
                    if (tag.equals("source")) path = parser.getAttributeValue(null, "path");
                    else if (tag.equals("problem")) {
                        inside = true; msg = null;
                        try { line = Integer.parseInt(parser.getAttributeValue(null, "line")); }
                        catch (Exception ignored) { line = 1; }
                        String severity = parser.getAttributeValue(null, "severity");
                        warning = severity != null && !severity.equalsIgnoreCase("error");
                    } else if (inside && tag.equals("message")) msg = parser.getAttributeValue(null, "value");
                } else if (event == XmlPullParser.END_TAG) {
                    if (parser.getName().equals("problem")) {
                        if (msg != null && problems.size() < 2000)
                            problems.add(new Problem(path, line, msg, warning));
                        inside = false;
                    } else if (parser.getName().equals("source")) path = null;
                }
            }
        } finally { reader.close(); }
        return problems;
    }

    public static Result check(final Context ctx, File module, String variant, final Progress progress) {
        Result result = new Result();
        File log = null;
        try {
            if (module == null || !MavenLibraries.gradleFile(module).isFile())
                throw new IllegalArgumentException("Open a Gradle application module first.");
            progress.update("Preparing Java sources...", 8);
            checkStopped(progress);
            final List<File> src = sources(module, variant);
            result.sourceCount = src.size();
            if (src.size() == 0) throw new IllegalStateException("No Java source files found in the selected module.");
            if (src.size() >= 3000) throw new IllegalStateException("Project exceeds the 3000-file Java check limit; use original AIDE or Gradle for large builds.");
            if (!JavaCompiler.hasEcj()) throw new IllegalStateException("ECJ compiler is not included in this AideUI installation.");
            File androidJar = JavaCompiler.findAndroidJar(ctx, true);
            if (androidJar == null) throw new IllegalStateException("Android platform android.jar is missing.");
            checkStopped(progress);
            progress.update("Resolving local libraries...", 18);
            List<String> classpath = new ArrayList<String>();
            File stubs = JavaCompiler.findJava8CompilerStubs(ctx);
            if (stubs != null) classpath.add(stubs.getAbsolutePath());
            JavaCompiler.addJars(new File(module, "libs"), classpath);
            File projectRoot = module.getParentFile();
            if (projectRoot != null) JavaCompiler.addJars(new File(projectRoot, "libs"), classpath);
            for (File jar : MavenLibraries.classpath(module)) classpath.add(jar.getAbsolutePath());
            checkStopped(progress);
            progress.update("Compiling Java (" + src.size() + " files)...", 25);

            log = File.createTempFile("aideui-project-build-", ".xml", ctx.getCacheDir());
            List<String> options = new ArrayList<String>();
            options.add("-1.8"); options.add("-proc:none");
            options.add("-encoding"); options.add("UTF-8");
            options.add("-d"); options.add("none");
            options.add("-bootclasspath"); options.add(androidJar.getAbsolutePath());
            if (!classpath.isEmpty()) {
                options.add("-classpath"); options.add(JavaCompiler.join(classpath));
            }
            List<String> roots = sourceRoots(module, variant);
            List<String> existingRoots = new ArrayList<String>();
            for (String relative : roots) {
                File folder = new File(module, relative);
                if (folder.isDirectory()) existingRoots.add(folder.getAbsolutePath());
            }
            if (!existingRoots.isEmpty()) {
                options.add("-sourcepath"); options.add(JavaCompiler.join(existingRoots));
            }
            options.add("-log"); options.add(log.getAbsolutePath());
            for (File javaFile : src) options.add(javaFile.getAbsolutePath());
            final int[] lastPercent = {25};
            CompilationProgress cp = new CompilationProgress() {
                public void begin(int remaining) { progress.update("Compiling Java...", 26); }
                public void done() { progress.update("Reading compiler diagnostics...", 90); }
                public boolean isCanceled() { return progress.isCancelled() || Thread.currentThread().isInterrupted(); }
                public void setTaskName(String name) { /* task string is not stable across ECJ versions */ }
                public void worked(int increment, int remaining) {
                    int p = Math.min(88, lastPercent[0] + Math.max(0, increment));
                    if (p > lastPercent[0]) { lastPercent[0] = p; progress.update("Compiling Java...", p); }
                }
            };
            StringWriter out = new StringWriter(), err = new StringWriter();
            boolean compiled = BatchCompiler.compile(options.toArray(new String[0]), new PrintWriter(out), new PrintWriter(err), cp);
            checkStopped(progress);
            result.problems.addAll(parseProblems(log));
            for (Problem problem : result.problems) { if (problem.warning) result.warnings++; else result.errors++; }
            if (!compiled || result.errors > 0) {
                result.status = "Java compilation reported errors. No APK was produced.";
                if (result.errors == 0 && result.problems.isEmpty()) {
                    String diagnostic = err.toString().trim() + " " + out.toString().trim();
                    if (diagnostic.trim().length() > 0) {
                        result.problems.add(new Problem(null, 0, diagnostic.trim(), false));
                        result.errors++;
                    }
                }
            } else {
                result.passed = true;
                result.status = "Java compilation check passed. No APK was produced.";
            }
            progress.update(result.passed ? "Java check passed (no APK generated)" : "Java check reported errors", 100);
        } catch (InterruptedException stop) {
            result.cancelled = true;
            result.status = "Java check stopped by user. No APK was produced.";
        } catch (Throwable failure) {
            if (progress.isCancelled() || Thread.currentThread().isInterrupted()) {
                result.cancelled = true;
                result.status = "Java check stopped by user. No APK was produced.";
            } else {
                result.status = "Java check failed: " + (failure.getMessage() == null ? failure.toString() : failure.getMessage());
            }
        } finally {
            if (log != null) log.delete();
        }
        return result;
    }
}
