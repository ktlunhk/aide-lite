package com.aideui;

import android.content.Context;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import org.eclipse.jdt.core.compiler.CompilationProgress;
import org.eclipse.jdt.core.compiler.batch.BatchCompiler;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import javax.security.auth.x500.X500Principal;

/**
 * In-app Java/Android debug and local-release APK builder. No dependency on or Intent to original AIDE.
 * Restricted to simple Android application modules; it does NOT run Gradle, merge AAR
 * resources/manifests, or build C/C++ code. Kotlin requires a compatible Android compiler bundle.
 *
 * Native ARM64 AAPT2 or legacy ARM32 AAPT is packaged under nativeLibraryDir (Android W^X).
 * D8 and apksig are bundled as app dependencies, and invoked inside the app process.
 */
public final class StandaloneApkBuild {
    private static File firstKotlinFile(File dir) {
        if (dir == null || !dir.isDirectory()) return null;
        File[] children = dir.listFiles();
        if (children == null) return null;
        for (File f : children) {
            if (f.isFile() && (f.getName().endsWith(".kt") || f.getName().endsWith(".kts"))) return f;
            if (f.isDirectory()) {
                File result = firstKotlinFile(f);
                if (result != null) return result;
            }
        }
        return null;
    }

    private StandaloneApkBuild() { }
    private static final String KEY_ALIAS = "aideui-self-build-debug-v1";
    private static final String RELEASE_KEY_ALIAS = "aideui-self-build-local-release-v1";
    private static final int MAX_BUILD_FILES = 3000;
    private static final long MAX_ZIP_INPUT = 128L * 1024 * 1024;

    static void stage(ProjectBuild.Progress cb, String message, int percent) throws InterruptedException {
        if (cb.isCancelled() || Thread.currentThread().isInterrupted()) throw new InterruptedException("Build cancelled");
        cb.update(message, percent);
    }

    static File mkdir(File f) throws IOException {
        if (!f.isDirectory() && !f.mkdirs()) throw new IOException("Cannot create " + f);
        return f;
    }

    static void remove(File f) throws IOException {
        if (!f.exists()) return;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children == null) throw new IOException("Cannot list " + f);
            for (File child : children) remove(child);
        }
        if (!f.delete()) throw new IOException("Cannot delete " + f);
    }

    static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] data = new byte[32768]; int n;
        while ((n = in.read(data)) >= 0) if (n > 0) out.write(data, 0, n);
    }

    static String read(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        copy(input, output);
        return output.toString("UTF-8");
    }

    /** Gradle and classic AIDE project layouts; never infer a project from a bare src directory. */
    static boolean isClassicProject(File root) {
        return root != null && new File(root, "AndroidManifest.xml").isFile()
            && new File(root, "src").isDirectory();
    }

    static boolean isGradleProject(File root) {
        return root != null && MavenLibraries.gradleFile(root).isFile()
            && new File(root, "src/main/AndroidManifest.xml").isFile();
    }

    static File findBuildRoot(File start) {
        for (File at = start; at != null; at = at.getParentFile()) {
            if (isGradleProject(at) || isClassicProject(at)) return at;
            if (new File(at, "app/src/main/AndroidManifest.xml").isFile()
                    && MavenLibraries.gradleFile(new File(at, "app")).isFile())
                return new File(at, "app");
        }
        return null;
    }

    static int manifestSdk(File manifest, String attr, int fallback) {
        if (manifest == null || !manifest.isFile()) return fallback;
        try {
            String content = MavenLibraries.readText(manifest);
            Matcher m = Pattern.compile("\\b" + Pattern.quote(attr) + "\\s*=\\s*\"(\\d+)\"").matcher(content);
            if (m.find()) return Integer.parseInt(m.group(1));
        } catch (Exception ignored) { }
        return fallback;
    }

    static File projectManifest(File root) {
        return isClassicProject(root) ? new File(root, "AndroidManifest.xml")
            : new File(root, "src/main/AndroidManifest.xml");
    }

    static File projectSourceRoot(File root) {
        return isClassicProject(root) ? new File(root, "src") : new File(root, "src/main/java");
    }

    static File projectResources(File root) {
        return isClassicProject(root) ? new File(root, "res") : new File(root, "src/main/res");
    }

    static File projectAssets(File root) {
        return isClassicProject(root) ? new File(root, "assets") : new File(root, "src/main/assets");
    }

    static File projectNativeLibs(File root) {
        return isClassicProject(root) ? new File(root, "libs") : new File(root, "src/main/jniLibs");
    }

    static int minSdk(File module) {
        try {
            String gradle = MavenLibraries.readText(MavenLibraries.gradleFile(module));
            Matcher matcher = Pattern.compile("\\bminSdk(?:Version)?\\s*(?:=\\s*)?(\\d+)").matcher(gradle);
            if (matcher.find()) return Math.max(21, Integer.parseInt(matcher.group(1)));
        } catch (Exception ignored) { }
        return manifestSdk(projectManifest(module), "android:minSdkVersion", 21);
    }

    static String modulePackage(File manifest, File module) throws IOException {
        String s = MavenLibraries.readText(manifest);
        Matcher matcher = Pattern.compile("<manifest[^>]*\\bpackage\\s*=\\s*\"([A-Za-z_][A-Za-z0-9_.]*)\"").matcher(s);
        if (matcher.find()) return matcher.group(1);
        String gradle = MavenLibraries.gradleFile(module).isFile()
            ? MavenLibraries.readText(MavenLibraries.gradleFile(module)) : "";
        matcher = Pattern.compile("\\b(?:applicationId|namespace)\\s*(?:=\\s*)?['\"]([A-Za-z_][A-Za-z0-9_.]*)['\"]").matcher(gradle);
        if (matcher.find()) return matcher.group(1);
        throw new IOException("Manifest requires package, or Gradle needs a literal applicationId");
    }

    /** The resource package is Gradle's namespace (when declared), not necessarily applicationId. */
    static String resourcePackage(File manifest, File module) throws IOException {
        if (!isClassicProject(module)) {
            String gradle = MavenLibraries.gradleFile(module).isFile()
                ? MavenLibraries.readText(MavenLibraries.gradleFile(module)) : "";
            Matcher namespace = Pattern.compile("\\bnamespace\\s*(?:=\\s*)?['\"]([A-Za-z_][A-Za-z0-9_.]*)['\"]")
                .matcher(gradle);
            if (namespace.find()) return namespace.group(1);
        }
        return modulePackage(manifest, module);
    }

    static int targetSdk(File module) {
        try {
            String gradle = MavenLibraries.readText(MavenLibraries.gradleFile(module));
            Matcher matcher = Pattern.compile("\\btargetSdk(?:Version)?\\s*(?:=\\s*)?(\\d+)").matcher(gradle);
            if (matcher.find()) return Math.max(23, Integer.parseInt(matcher.group(1)));
        } catch (Exception ignored) { }
        return manifestSdk(projectManifest(module), "android:targetSdkVersion", 28);
    }

    /** Gradle normally adds SDK declarations and replaces applicationId before AAPT links. */
    static File prepareManifest(File original, File module, File scratch) throws IOException {
        String xml = MavenLibraries.readText(original);
        if (xml.contains("<!DOCTYPE") || xml.contains("<!ENTITY"))
            throw new IOException("Unexpected DTD in project manifest");
        String name = modulePackage(original, module);
        xml = xml.replace("${applicationId}", name);
        Matcher tag = Pattern.compile("<manifest\\b[^>]*>").matcher(xml);
        if (!tag.find()) throw new IOException("Malformed AndroidManifest.xml");
        String element = tag.group();
        if (!Pattern.compile("\\bpackage\\s*=").matcher(element).find())
            element = element.substring(0, element.length() - 1) + " package=\"" + name + "\">";
        if (!element.contains("xmlns:android="))
            element = element.substring(0, element.length() - 1) +
                " xmlns:android=\"http://schemas.android.com/apk/res/android\">";
        String start = xml.substring(0, tag.start()) + element;
        String rest = xml.substring(tag.end());
        if (!Pattern.compile("<uses-sdk\\b").matcher(rest).find())
            start += "\n    <uses-sdk android:minSdkVersion=\"" + minSdk(module)
                + "\" android:targetSdkVersion=\"" + targetSdk(module) + "\"/>";
        File dest = new File(scratch, "AndroidManifest.xml");
        FileOutputStream out = new FileOutputStream(dest);
        try { out.write((start + rest).getBytes("UTF-8")); } finally { out.close(); }
        return dest;
    }

    /**
     * Features the standalone builder cannot honour. Failing here gives a clear reason instead of
     * hundreds of unresolved-reference errors from a half-supported build.
     */
    static String unsupportedFeatures(File module) {
        File gradle = MavenLibraries.gradleFile(module);
        if (gradle == null || !gradle.isFile()) return null;
        String text;
        try { text = MavenLibraries.readText(gradle); }
        catch (IOException unreadable) { return null; }
        // The flag alone is harmless: binding classes only matter when layouts exist AND code imports them.
        // Pure Jetpack Compose projects often keep a leftover "viewBinding = true" with no res/layout at all.
        if (Pattern.compile("(viewBinding|dataBinding)\\s*(=\\s*)?true").matcher(text).find()
                && usesGeneratedBindings(module))
            return "This project enables view/data binding, which generates classes the standalone builder "
                + "cannot produce. Use findViewById or build with Gradle.";
        return null;
    }

    /**
     * True when the module has layout XML files (binding classes are generated one per layout) and some
     * source file imports a generated "...databinding.*" class. Either alone means nothing would be missing.
     */
    static boolean usesGeneratedBindings(File module) {
        File res = projectResources(module);
        boolean layouts = false;
        File[] dirs = res.listFiles();
        if (dirs != null) for (File dir : dirs) {
            if (!dir.isDirectory() || !dir.getName().startsWith("layout")) continue;
            String[] names = dir.list();
            if (names != null) for (String n : names) if (n.endsWith(".xml")) { layouts = true; break; }
            if (layouts) break;
        }
        if (!layouts) return false;
        List<File> roots = new ArrayList<File>();
        roots.add(projectSourceRoot(module));
        if (!isClassicProject(module)) roots.add(new File(module, "src/main/kotlin"));
        Pattern generated = Pattern.compile("\\bimport\\s+[A-Za-z0-9_.]*\\.databinding\\.");
        int[] budget = {5000};
        for (File root : roots) if (sourceMatches(root, generated, budget)) return true;
        return false;
    }

    private static boolean sourceMatches(File dir, Pattern pattern, int[] budget) {
        File[] children = dir.listFiles();
        if (children == null) return false;
        for (File f : children) {
            if (budget[0]-- <= 0) return true;   // too large to scan: stay conservative and keep the old rejection
            if (f.isDirectory()) { if (sourceMatches(f, pattern, budget)) return true; continue; }
            String n = f.getName();
            if (!n.endsWith(".kt") && !n.endsWith(".java")) continue;
            try { if (pattern.matcher(MavenLibraries.readText(f)).find()) return true; }
            catch (IOException unreadable) { return true; }
        }
        return false;
    }

    /** Compose UI libraries ship resources that the standalone builder does not merge (warn, do not block). */
    static boolean composeLibrariesUsed(File module) {
        File gradle = MavenLibraries.gradleFile(module);
        if (gradle == null || !gradle.isFile()) return false;
        try { return MavenLibraries.readText(gradle).contains("androidx.compose."); }
        catch (IOException unreadable) { return false; }
    }

    /** Displayed errors never claim APK success when a stage is missing. */
    public static ProjectBuild.Result build(Context context, File module, String variant, ProjectBuild.Progress cb) {
        ProjectBuild.Result result = new ProjectBuild.Result();
        File scratch = null;
        try {
            stage(cb, "Checking standalone toolchain...", 1);
            if (Build.VERSION.SDK_INT < 26)
                throw new IOException("Standalone D8 currently needs Android 8+ (API 26+)");
            if (module == null || (!isGradleProject(module) && !isClassicProject(module)))
                throw new IOException("Open an Android project with AndroidManifest.xml and Java sources");
            final boolean classic = isClassicProject(module);
            File main = classic ? module : new File(module, "src/main");
            File manifest = projectManifest(module);
            File res = projectResources(module);
            File sourcesDir = projectSourceRoot(module);
            // Kotlin-only modules keep everything in src/main/kotlin and have no src/main/java at all.
            boolean hasKotlinDir = !classic && new File(main, "kotlin").isDirectory();
            if (!sourcesDir.isDirectory() && !hasKotlinDir)
                throw new IOException("Missing project source directory (expected src/main/java or src/main/kotlin)");
            String unsupported = unsupportedFeatures(module);
            if (unsupported != null) throw new IOException(unsupported);
            if (composeLibrariesUsed(module))
                result.problems.add(new ProjectBuild.Problem(null, 0, "Library resources are merged, but AAR manifests are not "
                    + "(providers, permissions, activities declared by libraries). If the app misbehaves at launch "
                    + "because a library component is missing, build with Gradle.", true));
            if (composeLibrariesUsed(module)) result.warnings++;
            if (new File(main, "cpp").exists() || new File(main, "jni").exists())
                throw new IOException("C/C++ projects require an NDK build engine");
            // Only the standard debug and release build types are currently supported.
            // Flavor-based variants require Gradle's manifest/dependency merge semantics.
            final boolean release = "release".equalsIgnoreCase(variant);
            if (!release && !"debug".equalsIgnoreCase(variant))
                throw new IOException("Standalone APK builder supports Debug and Release build types only. "
                    + "Custom product-flavor variants need Gradle's merging pipeline: " + variant);
            // ARM64-only AAPT2 toolchain. The deprecated armeabi-v7a libaapt.so
            // has been removed; do not silently attempt a 32-bit fallback.
            File nativeDir = new File(context.getApplicationInfo().nativeLibraryDir);
            File nativeAapt2 = new File(nativeDir, "libaapt2.so");
            if (!nativeAapt2.isFile())
                throw new IOException("ARM64 AAPT2 is unavailable on this device. "
                    + "This AideUI build requires an arm64-v8a Android device; "
                    + "verify libaapt2.so is installed in nativeLibraryDir.");
            // v29 first uses Android-ready R8/D8 bundled by the user-provided ZeroAicy AIDE.
            // Legacy com.android.tools:r8 remains a fallback if Android cannot load the bundle.
            try { Class.forName("com.android.apksig.ApkSigner"); }
            catch (Throwable e) { throw new IOException("APK signer not packaged in AideUI. Rebuild with the apksig dependency."); }
            File androidJar = JavaCompiler.findAndroidJar(context, true);
            if (androidJar == null) throw new IOException("Bundled android.jar unavailable");
            final String packageName = modulePackage(manifest, module);
            final String resourceNamespace = resourcePackage(manifest, module);
            final int api = minSdk(module);
            scratch = new File(context.getCacheDir(), "standalone-" + Integer.toHexString(module.getCanonicalPath().hashCode()));
            remove(scratch);
            mkdir(scratch);
            // Fail fast before expensive AAPT2 work if Kotlin cannot be compiled.
            List<File> kotlinSources = KotlinOnDeviceCompiler.collectSources(module, classic, release);
            boolean kotlinProject = !kotlinSources.isEmpty();
            if (kotlinProject) {
                stage(cb, "Checking Kotlin compiler availability...", 5);
                KotlinOnDeviceCompiler.ensureCompilerAvailable(context);
            }
            File generated = mkdir(new File(scratch, "generated"));
            File classes = mkdir(new File(scratch, "classes"));
            File dexOut = mkdir(new File(scratch, "dex"));
            File baseApk = new File(scratch, "resources.apk");
            File preparedManifest = prepareManifest(manifest, module, scratch);
            if (release) {
                // Never distribute an explicitly debuggable release APK.
                String content;
                FileInputStream manifestInput = new FileInputStream(preparedManifest);
                try { content = read(manifestInput); }
                finally { manifestInput.close(); }
                if (Pattern.compile("android:debuggable\\s*=\\s*['\"]true['\"]", Pattern.CASE_INSENSITIVE)
                        .matcher(content).find())
                    throw new IOException("Release manifest explicitly enables android:debuggable. Disable it first.");
            }
            File assets = projectAssets(module);
            stage(cb, "Compiling resources with ARM64 AAPT2...", 8);
            List<AarResources.Aar> aarLibraries = classic ? new ArrayList<AarResources.Aar>()
                    : AarResources.extract(module, scratch);
            buildAapt2(nativeAapt2, preparedManifest, res, assets, generated, scratch,
                    baseApk, androidJar, api, targetSdk(module), resourceNamespace, aarLibraries, cb);
            if (!baseApk.isFile() || baseApk.length() < 50)
                throw new IOException("Resource compilation generated no resources APK");
            if (!aarLibraries.isEmpty()) {
                int libraryRs = AarResources.generateLibraryR(aarLibraries,
                        new File(generated, resourceNamespace.replace('.', '/') + "/R.java"),
                        generated, resourceNamespace);
                stage(cb, "Merged resources of " + aarLibraries.size() + " libraries; generated "
                        + libraryRs + " library R classes", 24);
            }

            // Discover Kotlin source files before proceeding; Kotlin is never silently ignored.
            File kotlinStdlib = null;
            if (kotlinProject) {
                stage(cb, "Preparing embedded Kotlin compiler...", 25);
                kotlinStdlib = KotlinOnDeviceCompiler.extractStdlib(context);
            }
            stage(cb, "Collecting Java sources...", 27);
            List<File> sources = new ArrayList<File>();
            Set<String> seenSources = new HashSet<String>();
            ProjectBuild.collect(module, sourcesDir, sources, seenSources, MAX_BUILD_FILES);
            File variantSources = new File(module, release ? "src/release/java" : "src/debug/java");
            if (!classic) ProjectBuild.collect(module, variantSources, sources, seenSources, MAX_BUILD_FILES);
            result.sourceCount = sources.size() + kotlinSources.size();
            List<File> generatedSources = new ArrayList<File>();
            ProjectBuild.collect(generated, generated, generatedSources, new HashSet<String>(), MAX_BUILD_FILES, 64L * 1024 * 1024);
            sources.addAll(generatedSources);
            // Gradle namespace determines the package of generated R.java, but a Java
            // activity may deliberately use a different package. In that case add the
            // necessary import only to temporary compilation copies, never to user files.
            File compileSourceCopies = mkdir(new File(scratch, "compile-source-copies"));
            int fixedRImports = 0;
            for (int i = 0; i < sources.size(); i++) {
                File originalSource = sources.get(i);
                if (!originalSource.getCanonicalPath().startsWith(sourcesDir.getCanonicalPath() + File.separator)) continue;
                File preparedSource = ResourceNamespaceFix.prepareSource(originalSource, sourcesDir,
                    compileSourceCopies, resourceNamespace);
                if (!preparedSource.equals(originalSource)) {
                    sources.set(i, preparedSource);
                    fixedRImports++;
                }
            }
            if (fixedRImports > 0)
                stage(cb, "Resolved R namespace imports in " + fixedRImports + " compile-only source(s)", 29);
            // Fail with an actionable resource error instead of ECJ's opaque "R cannot be resolved".
            explainMissingResourceReferences(sourcesDir, sources, res, resourceNamespace, generatedSources);
            checkMissingLayouts(sources, res);
            if (sources.size() > MAX_BUILD_FILES) throw new IOException("Too many Java source files");
            if (sources.isEmpty() && !kotlinProject) throw new IOException("No Java or Kotlin sources found");
            // Compile Java against only real libraries. Java8 IDE-only shim is NOT a runtime dependency.
            List<String> libraries = new ArrayList<String>();
            List<String> localJars = new ArrayList<String>();
            JavaCompiler.addJars(new File(module, "libs"), localJars);
            File project = module.getParentFile();
            if (!classic && project != null) JavaCompiler.addJars(new File(project, "libs"), localJars);
            if (!classic) localJars = onlyDeclaredJars(module, localJars);
            libraries.addAll(localJars);
            if (!classic) for (File jar : MavenLibraries.classpath(module)) libraries.add(jar.getAbsolutePath());
            if (kotlinProject) {
                // AideUI compiles against and packages the Kotlin stdlib that matches its compiler. A project
                // copy (the template asks for 1.3.72) would duplicate those classes and mix runtime versions.
                for (java.util.Iterator<String> it = libraries.iterator(); it.hasNext(); ) {
                    String jarName = new File(it.next()).getName().toLowerCase(java.util.Locale.US);
                    if (jarName.contains("kotlin-stdlib")) it.remove();
                }
            }
            List<String> javaCompileLibraries = new ArrayList<String>(libraries);
            // ECJ needs a Java 8 signature shim to compile invokedynamic lambdas against the Android platform.
            // This shim is intentionally NOT passed to D8 or packaged into the target APK.
            File lambdaSignatureShim = JavaCompiler.findJava8CompilerStubs(context);
            if (lambdaSignatureShim != null) javaCompileLibraries.add(lambdaSignatureShim.getAbsolutePath());
            if (kotlinProject) {
                // Compile generated R/BuildConfig symbols first so Kotlin can resolve R.layout.*.
                if (!generatedSources.isEmpty()) {
                    List<String> symbols = new ArrayList<String>();
                    Collections.addAll(symbols, "-1.8", "-proc:none", "-encoding", "UTF-8",
                        "-d", classes.getAbsolutePath(), "-bootclasspath", androidJar.getAbsolutePath());
                    for (File symbol : generatedSources) symbols.add(symbol.getAbsolutePath());
                    if (!BatchCompiler.compile(symbols.toArray(new String[0]),
                        new PrintWriter(new StringWriter()), new PrintWriter(new StringWriter()), null))
                        throw new IOException("Cannot compile generated Android resource symbols for Kotlin");
                }
                stage(cb, "Compiling " + kotlinSources.size() + " Kotlin file(s)...", 32);
                KotlinCompiler.Request kotlinRequest = new KotlinCompiler.Request(kotlinSources, sourcesDir,
                        classes, androidJar, kotlinStdlib, libraries);
                if (KotlinPlugins.usesCompose(module)) {
                    stage(cb, "Preparing the Kotlin Compose compiler plugin...", 31);
                    KotlinPlugins.ensureInstalled(context, module, cb);
                    kotlinRequest.plugins.addAll(KotlinPlugins.installed(context, module));
                }
                try {
                    result.problems.addAll(KotlinCompiler.compile(context, kotlinRequest, cb));
                } catch (KotlinCompiler.CompileException kotlinFailure) {
                    result.problems.addAll(kotlinFailure.problems);
                    for (ProjectBuild.Problem p : kotlinFailure.problems) {
                        if (p.warning) result.warnings++; else result.errors++;
                    }
                    // Show what Kotlin actually had on its classpath: "unresolved reference" on an import usually
                    // means the library that provides it was never resolved or an older version won.
                    StringBuilder cpInfo = new StringBuilder("\n\nKotlin classpath: " + libraries.size() + " libraries.");
                    for (String lib : libraries) {
                        String n = new File(lib).getName();
                        if (n.contains("work-runtime") || n.contains("androidx.core-core") || n.contains("material-material")
                                || n.contains("activity-activity") || n.contains("drawerlayout"))
                            cpInfo.append("\n  ").append(n);
                    }
                    cpInfo.append("\n  R.java present: ").append(new File(generated,
                        resourceNamespace.replace('.', '/') + "/R.java").isFile())
                        .append(", R.class present: ").append(new File(classes,
                        resourceNamespace.replace('.', '/') + "/R.class").isFile());
                    try {
                        File syncSummary = new File(MavenLibraries.store(module), "sync-summary.txt");
                        if (syncSummary.isFile()) cpInfo.append("\n\nLast library sync:\n").append(MavenLibraries.readText(syncSummary));
                    } catch (Exception ignored) { }
                    throw new KotlinCompiler.CompileException(kotlinFailure.getMessage() + cpInfo, kotlinFailure.problems);
                }
                javaCompileLibraries.add(classes.getAbsolutePath());
                javaCompileLibraries.add(kotlinStdlib.getAbsolutePath());
            }
            File compiledLog = new File(scratch, "ecj.xml");
            List<String> options = new ArrayList<String>();
            Collections.addAll(options, "-1.8", "-proc:none", "-encoding", "UTF-8", "-d", classes.getAbsolutePath(),
                    "-bootclasspath", androidJar.getAbsolutePath(), "-log", compiledLog.getAbsolutePath());
            if (!javaCompileLibraries.isEmpty()) { options.add("-classpath"); options.add(JavaCompiler.join(javaCompileLibraries)); }
            for (File source : sources) options.add(source.getAbsolutePath());
            stage(cb, "Compiling " + sources.size() + " Java files...", 35);
            StringWriter ecjOut = new StringWriter(), ecjErr = new StringWriter();
            final ProjectBuild.Progress callback = cb;
            boolean success = sources.isEmpty() || BatchCompiler.compile(options.toArray(new String[0]), new PrintWriter(ecjOut),
                    new PrintWriter(ecjErr), new CompilationProgress() {
                        public void begin(int remaining) { }
                        public void done() { }
                        public boolean isCanceled() { return callback.isCancelled() || Thread.currentThread().isInterrupted(); }
                        public void setTaskName(String name) { }
                        public void worked(int increment, int remaining) { }
                    });
            result.problems.addAll(ProjectBuild.parseProblems(compiledLog));
            for (ProjectBuild.Problem p : result.problems) {
                if (p.warning) result.warnings++; else result.errors++;
            }
            if ((!success || result.errors > 0) && !sources.isEmpty()) {
                String details = ecjErr.toString() + " " + ecjOut.toString();
                if (details.contains("R cannot be resolved") || details.contains("R cannot be resolved to a variable")) {
                    File expectedR = new File(generated, resourceNamespace.replace('.', '/') + "/R.java");
                    throw new IOException("Resource symbol compilation failed. Expected R.java: "
                        + expectedR.getAbsolutePath() + " (exists=" + expectedR.isFile() + "). "
                        + "Resource namespace=" + resourceNamespace + ". "
                        + "If an Activity is declared in a different Java package, import "
                        + resourceNamespace + ".R. Build details: " + first(details, 700));
                }
                throw new IOException("Java compilation failed: " + first(details, 700));
            }
            stage(cb, "Preparing D8 runtime dependencies...", 53);
            DexLibrarySelection.Choice dexLibraries = DexLibrarySelection.choose(classes, module, libraries);
            if (kotlinProject && kotlinStdlib != null) {
                // Keep Kotlin runtime in the DEX even if class analysis misses reflective usages.
                List<String> withStdlib = new ArrayList<String>(dexLibraries.programs);
                boolean present = false;
                for (String lib : withStdlib) if (lib.equals(kotlinStdlib.getAbsolutePath())) present = true;
                if (!present) withStdlib.add(kotlinStdlib.getAbsolutePath());
                dexLibraries = new DexLibrarySelection.Choice(withStdlib, dexLibraries.skipped,
                    dexLibraries.explanation + "; bundled Kotlin stdlib included");
            }
            stage(cb, dexLibraries.explanation, 54);
            File programJar = new File(scratch, "program.jar");
            makeClassJar(classes, programJar);
            stage(cb, "Checking D8 libraries for duplicate classes...", 55);
            DexDuplicateGuard.Result safeDex = DexDuplicateGuard.resolve(programJar, dexLibraries.programs);
            stage(cb, safeDex.explanation, 56);
            stage(cb, "Generating classes.dex with Android-ready D8...", 57);
            compileDex(context, programJar, androidJar, safeDex.programs, dexOut, api, cb);
            File dex = new File(dexOut, "classes.dex");
            if (!dex.isFile() || dex.length() < 50)
                throw new IOException("D8 produced no classes.dex");
            stage(cb, "Packaging Android APK...", 79);
            File unsignedApk = new File(scratch, "unsigned.apk");
            mergeApk(baseApk, dexOut, projectNativeLibs(module), unsignedApk);
            ApkResourceAlignment.verify(unsignedApk);
            stage(cb, release ? "Signing APK with local release key..." : "Signing APK with local debug key...", 90);
            String buildType = release ? "release" : "debug";
            File outputDir = mkdir(classic ? new File(module, "bin")
                : new File(module, "build/outputs/apk/" + buildType));
            File signed = new File(outputDir, classic ? module.getName() + "-" + buildType + "-aideui.apk"
                : "app-" + buildType + "-aideui.apk");
            File temporarySigned = new File(outputDir, "app-" + buildType + "-aideui.tmp.apk");
            if (temporarySigned.exists() && !temporarySigned.delete()) throw new IOException("Cannot replace old temp APK");
            sign(context, unsignedApk, temporarySigned, release, packageName);
            if (!temporarySigned.isFile() || temporarySigned.length() < 1000)
                throw new IOException("APK signing did not produce an output file");
            // Signing must not disturb APK resource-table alignment.
            ApkResourceAlignment.verify(temporarySigned);
            stage(cb, "Finalizing APK...", 98);
            if (signed.exists() && !signed.delete()) throw new IOException("Cannot overwrite " + signed);
            if (!temporarySigned.renameTo(signed)) throw new IOException("Cannot finalize signed APK");
            // Let the live editor reuse exactly the R.java that built successfully.
            // Failure to update the optional editor cache must not invalidate the APK.
            try { JavaCompiler.retainBuiltResourceR(context, module, generated, resourceNamespace); }
            catch (Exception ignored) { }
            result.apk = signed;
            result.passed = true;
            result.status = (release ? "Locally signed Release APK ready: " : "Debug APK ready: ") + signed.getAbsolutePath();
            stage(cb, "Build succeeded — signed APK ready", 100);
        } catch (InterruptedException interrupted) {
            result.cancelled = true;
            result.status = "Build stopped";
        } catch (Throwable error) {
            result.passed = false;
            saveBuildError(context, error);
            result.status = "In-app APK build failed: " + summarize(error)
                + " (details: AideUI private files/last-build-error.txt)";
            result.diagnostics = diagnostic(error);
        } finally {
            if (scratch != null) try { remove(scratch); } catch (Exception ignored) { }
        }
        return result;
    }

    /**
     * AAPT2 replaces the old `aapt package` command with `compile` and `link`.
     * No Gradle or external AIDE process is launched. Inputs stay within the
     * selected application's res/ and assets/ directories.
     */
    /**
     * Gradle only uses jars that the build file names (implementation(files("libs/x.jar"))) or picks up with
     * fileTree. A jar that merely sits in libs/ is not part of the build; adding it would duplicate classes
     * that Maven already provides (for example libs/jadx-core-1.5.6.jar next to io.github.skylot:jadx-core).
     * When the build file lists files(...) entries and no fileTree, keep only the listed jars.
     */
    static List<String> onlyDeclaredJars(File module, List<String> jars) {
        try {
            File gradle = MavenLibraries.gradleFile(module);
            if (!gradle.isFile()) return jars;
            String text = MavenLibraries.readText(gradle);
            if (text.contains("fileTree")) return jars;
            Matcher calls = Pattern.compile("\\bfiles\\s*\\(([^)]*)\\)").matcher(text);
            Set<String> declared = new HashSet<String>();
            boolean any = false;
            while (calls.find()) {
                Matcher q = Pattern.compile("[\"']([^\"']+\\.jar)[\"']").matcher(calls.group(1));
                while (q.find()) { any = true; declared.add(new File(q.group(1)).getName()); }
            }
            if (!any) return jars;
            List<String> kept = new ArrayList<String>();
            for (String jar : jars) if (declared.contains(new File(jar).getName())) kept.add(jar);
            return kept;
        } catch (Exception e) {
            return jars;
        }
    }

    static void buildAapt2(File aapt2, File manifest, File res, File assets,
                           File generated, File scratch, File outputApk,
                           File androidJar, int minSdk, int targetSdk,
                           String resourceNamespace, ProjectBuild.Progress cb) throws Exception {
        buildAapt2(aapt2, manifest, res, assets, generated, scratch, outputApk, androidJar, minSdk,
                targetSdk, resourceNamespace, Collections.<AarResources.Aar>emptyList(), cb);
    }

    /** Same as above, additionally compiling and linking the resources of AAR libraries
     *  (lowest priority first). The app's own resources override library resources. */
    static void buildAapt2(File aapt2, File manifest, File res, File assets,
                           File generated, File scratch, File outputApk,
                           File androidJar, int minSdk, int targetSdk,
                           String resourceNamespace, List<AarResources.Aar> aars,
                           ProjectBuild.Progress cb) throws Exception {
        if (!aapt2.isFile() || aapt2.length() < 1000)
            throw new IOException("ARM64 AAPT2 executable was not installed");
        File compiled = new File(scratch, "compiled-resources.zip");
        boolean hasRes = res.isDirectory() && hasFiles(res);
        // Compile every AAR's res/ separately so a problem names the library that caused it.
        List<File> libraryArchives = new ArrayList<File>();
        File libraryOut = new File(scratch, "aar-compiled");
        for (int i = 0; i < aars.size(); i++) {
            AarResources.Aar aar = aars.get(i);
            if (aar.resDir == null || !hasFiles(aar.resDir)) continue;
            stage(cb, "Compiling library resources: " + aar.name + " (" + (i + 1) + "/" + aars.size() + ")",
                    8 + (int) (8L * i / Math.max(1, aars.size())));
            if (!libraryOut.isDirectory() && !libraryOut.mkdirs()) throw new IOException("Cannot create " + libraryOut);
            File archive = new File(libraryOut, aar.name + ".zip");
            List<String> libCompile = new ArrayList<String>();
            Collections.addAll(libCompile, aapt2.getAbsolutePath(), "compile", "--dir",
                    aar.resDir.getAbsolutePath(), "-o", archive.getAbsolutePath());
            try { runTool(libCompile, cb, 6000); }
            catch (IOException failure) {
                throw new IOException("AAPT2 compile failed for library " + aar.name + ": " + failure.getMessage(), failure);
            }
            if (archive.isFile() && archive.length() > 0) libraryArchives.add(archive);
        }
        if (hasRes) {
            List<String> compile = new ArrayList<String>();
            Collections.addAll(compile, aapt2.getAbsolutePath(), "compile", "--dir",
                    res.getAbsolutePath(), "-o", compiled.getAbsolutePath());
            try { runTool(compile, cb, 20000); }
            catch (IOException failure) {
                throw new IOException("AAPT2 compile failed: " + failure.getMessage(), failure);
            }
            if (!compiled.isFile() || compiled.length() == 0)
                throw new IOException("AAPT2 compile returned no resource archive");
        }
        stage(cb, "Linking resources and generating R.java with AAPT2...", 17);
        List<String> link = new ArrayList<String>();
        Collections.addAll(link, aapt2.getAbsolutePath(), "link", "-o", outputApk.getAbsolutePath(),
                "-I", androidJar.getAbsolutePath(), "--manifest", manifest.getAbsolutePath(),
                "--java", generated.getAbsolutePath(), "--custom-package", resourceNamespace,
                "--min-sdk-version", Integer.toString(minSdk),
                "--target-sdk-version", Integer.toString(targetSdk));
        if (assets.isDirectory()) { link.add("-A"); link.add(assets.getAbsolutePath()); }
        if (libraryArchives.isEmpty()) {
            if (hasRes) link.add(compiled.getAbsolutePath());
        } else {
            // Gradle priority: app > direct libraries > transitive libraries. AAPT2 lets each later
            // overlay (-R) override earlier input, so list the lowest priority first and the app last.
            link.add("--auto-add-overlay");
            link.add(libraryArchives.get(0).getAbsolutePath());
            for (int i = 1; i < libraryArchives.size(); i++) {
                link.add("-R"); link.add(libraryArchives.get(i).getAbsolutePath());
            }
            if (hasRes) { link.add("-R"); link.add(compiled.getAbsolutePath()); }
            // Library bytecode refers to <library package>.R, generated afterwards from the final table.
        }
        try { runTool(link, cb, 20000); }
        catch (IOException failure) {
            String msg = String.valueOf(failure.getMessage());
            StringBuilder hint = new StringBuilder();
            if (msg.contains("attr/drawerLayoutStyle"))
                hint.append("\nMissing library resources: add implementation(\"androidx.drawerlayout:drawerlayout:1.2.0\") to the app's build.gradle, then Sync.");
            else if (msg.contains("not found") && msg.contains("attr/"))
                hint.append("\nA library that defines this attribute is not on the classpath; add it to the app's dependencies and Sync.");
            if (hint.length() > 0) hint.append("\nLinked libraries: ").append(AarResources.names(aars));
            throw new IOException("AAPT2 link failed: " + msg + hint, failure);
        }
    }

    /** Only report missing R resources, never silently manufacture entries in the user's project. */
    static void explainMissingResourceReferences(File sourceRoot, List<File> sourceFiles,
                                                  File resources, String namespace,
                                                  List<File> generatedSources) throws IOException {
        boolean hasResourceClass = false;
        for (File generated : generatedSources) {
            if (!"R.java".equals(generated.getName())) continue;
            try {
                if (MavenLibraries.readLargeText(generated).contains("package " + namespace + ";")) {
                    hasResourceClass = true;
                    break;
                }
            } catch (Exception ignored) { }
        }
        if (!hasResourceClass) {
            for (File source : sourceFiles) {
                if (!source.equals(new File(sourceRoot, "R.java")) && source.getName().equals("R.java")) {
                    hasResourceClass = true;
                    break;
                }
            }
        }
        if (!hasResourceClass) {
            for (File source : sourceFiles) {
                if (!source.getName().endsWith(".java") || source.length() > 2L * 1024 * 1024) continue;
                String javaSource = MavenLibraries.readText(source);
                Matcher reference = Pattern.compile("(?<![A-Za-z0-9_$.])R\\.([A-Za-z_][A-Za-z_0-9]*)\\.([A-Za-z_][A-Za-z_0-9]*)")
                    .matcher(maskNonCodeJava(javaSource));
                if (reference.find()) throw new IOException(
                    "AAPT2 did not generate R.java for namespace " + namespace
                    + ". Your code uses R." + reference.group(1) + "." + reference.group(2)
                    + ". Check that the corresponding resource exists in " + resources.getAbsolutePath()
                    + " and that the manifest package/Gradle namespace matches the Java package.");
            }
        }
    }

    /** Hide Java comments and string/character literals without altering line positions.
     *  Template generators often include snippets such as "R.layout.main" in strings;
     *  those are NOT references in the currently compiled app. */
    static String maskNonCodeJava(String source) {
        StringBuilder masked = new StringBuilder(source.length());
        int state = 0; // 0 code, 1 line comment, 2 block comment, 3 string, 4 char, 5 text block
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            char next = i + 1 < source.length() ? source.charAt(i + 1) : 0;
            char third = i + 2 < source.length() ? source.charAt(i + 2) : 0;
            if (state == 0) {
                if (c == '/' && next == '/') { masked.append("  "); i++; state = 1; }
                else if (c == '/' && next == '*') { masked.append("  "); i++; state = 2; }
                else if (c == '"' && next == '"' && third == '"') {
                    masked.append("   "); i += 2; state = 5;
                } else if (c == '"') { masked.append(' '); state = 3; }
                else if (c == '\'') { masked.append(' '); state = 4; }
                else masked.append(c);
            } else if (state == 1) {
                if (c == '\n' || c == '\r') { masked.append(c); state = 0; }
                else masked.append(' ');
            } else if (state == 2) {
                if (c == '*' && next == '/') { masked.append("  "); i++; state = 0; }
                else masked.append(c == '\n' || c == '\r' ? c : ' ');
            } else if (state == 5) {
                if (c == '"' && next == '"' && third == '"') {
                    masked.append("   "); i += 2; state = 0;
                } else if (c == '\\' && i + 1 < source.length()) {
                    masked.append(' '); masked.append(next == '\n' || next == '\r' ? next : ' '); i++;
                } else masked.append(c == '\n' || c == '\r' ? c : ' ');
            } else {
                if (c == '\\' && i + 1 < source.length()) {
                    masked.append(' '); masked.append(next == '\n' || next == '\r' ? next : ' '); i++;
                } else if ((state == 3 && c == '"') || (state == 4 && c == '\'')) {
                    masked.append(' '); state = 0;
                } else masked.append(c == '\n' || c == '\r' ? c : ' ');
            }
        }
        return masked.toString();
    }

    /** AAPT2 can generate an R class even when a referenced app layout does not exist.
     *  Android framework layouts use android.R.layout.* and must not be checked against
     *  the project res/layout tree. Only unqualified application R.layout.* is checked. */
    static void checkMissingLayouts(List<File> sources, File res) throws IOException {
        Pattern layoutReference = Pattern.compile("(?<![A-Za-z0-9_$.])R\\.layout\\.([a-zA-Z_][a-zA-Z_0-9]*)\\b");
        for (File source : sources) {
            if (!source.getName().endsWith(".java") || source.length() > 2L * 1024 * 1024) continue;
            String[] lines = maskNonCodeJava(MavenLibraries.readText(source)).split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                Matcher matcher = layoutReference.matcher(lines[i]);
                while (matcher.find()) {
                    String layoutName = matcher.group(1);
                    if (!layoutFileExists(res, layoutName))
                        throw new IOException("Missing layout: R.layout." + layoutName + " at "
                            + source.getName() + ":" + (i + 1) + ". Create "
                            + new File(res, "layout/" + layoutName + ".xml").getAbsolutePath()
                            + " or use an existing layout name. Resource files are never generated silently.");
                }
            }
        }
    }

    static boolean layoutFileExists(File res, String name) {
        File[] folders = res.listFiles();
        if (folders == null) return false;
        for (File folder : folders)
            if (folder.isDirectory() && (folder.getName().equals("layout")
                    || folder.getName().startsWith("layout-"))
                    && new File(folder, name + ".xml").isFile()) return true;
        return false;
    }

    static boolean hasFiles(File root) {
        File[] items = root.listFiles();
        if (items == null) return false;
        for (File f : items) if (f.isFile() || f.isDirectory() && hasFiles(f)) return true;
        return false;
    }

    static String first(String s, int max) {
        if (s == null) return "";
        s = s.trim();
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    static String runTool(List<String> command, ProjectBuild.Progress progress, int maxChars) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        final Process run = process;
        final ByteArrayOutputStream result = log;
        Thread output = new Thread(new Runnable() {
            public void run() {
                try {
                    InputStream in = run.getInputStream();
                    byte[] buf = new byte[8192]; int n;
                    while ((n = in.read(buf)) > 0) if (result.size() < 1024 * 1024) result.write(buf, 0, n);
                    in.close();
                } catch (IOException ignored) { }
            }
        }, "aideui-aapt-output");
        output.start();
        int code;
        while (true) {
            if (progress.isCancelled() || Thread.currentThread().isInterrupted()) {
                process.destroy(); output.join(1000); throw new InterruptedException("Build stopped");
            }
            try { code = process.exitValue(); break; }
            catch (IllegalThreadStateException running) { Thread.sleep(150); }
        }
        output.join(1000);
        String text = first(log.toString("UTF-8"), maxChars);
        if (code != 0) throw new IOException("Resource packager exited " + code + ": " + text);
        return text;
    }

    static void makeClassJar(File classes, File jar) throws Exception {
        ZipOutputStream out = new ZipOutputStream(new FileOutputStream(jar));
        try { zipFiles(classes, classes, out, "", new HashSet<String>()); }
        finally { out.close(); }
    }

    static void zipFiles(File root, File dir, ZipOutputStream out, String prefix, Set<String> names) throws Exception {
        File[] entries = dir.listFiles();
        if (entries == null) return;
        for (File entry : entries) {
            if (entry.isDirectory()) zipFiles(root, entry, out, prefix, names);
            else if (entry.isFile()) {
                String name = prefix + entry.getCanonicalPath().substring(root.getCanonicalPath().length() + 1).replace(File.separatorChar, '/');
                if (!names.add(name)) throw new IOException("Duplicate ZIP entry " + name);
                ZipEntry item = new ZipEntry(name); out.putNextEntry(item);
                FileInputStream in = new FileInputStream(entry);
                try { copy(in, out); } finally { in.close(); }
                out.closeEntry();
            }
        }
    }

    /**
     * Prefer the standalone Android-compatible D8 copied from the user's ZeroAicy AIDE APK.
     * Fall back to the older Gradle-resolved D8 only when the first tool fails.
     * Never claim success if either tool fails to create classes.dex.
     */
    static void compileDex(Context context, File codeJar, File platform, List<String> libraries,
                           File output, int minApi, ProjectBuild.Progress progress) throws Exception {
        Throwable primary = null;
        try {
            ClassLoader updated = AndroidR8Loader.get(context);
            dexWith(context, updated, codeJar, platform, libraries, output, minApi, progress);
            if (!new File(output, "classes.dex").isFile())
                throw new IOException("Updated D8 returned without classes.dex");
            return;
        } catch (Throwable fail) {
            primary = unwrap(fail);
            // The legacy engine would need the same memory: report it instead of crashing the app on retry.
            if (primary instanceof OutOfMemoryError)
                throw new IOException("Out of memory while generating DEX: " + summarize(primary), primary);
            if (primary instanceof IOException && String.valueOf(primary.getMessage()).startsWith("Out of memory while dexing"))
                throw (IOException) primary;
        }
        stage(progress, "Updated D8 failed; trying legacy D8...", 65);
        cleanDexOutput(output);
        try {
            dexWith(context, StandaloneApkBuild.class.getClassLoader(), codeJar, platform, libraries, output, minApi, progress);
            if (!new File(output, "classes.dex").isFile())
                throw new IOException("Legacy D8 returned without classes.dex");
            return;
        } catch (Throwable secondary) {
            Throwable oldFailure = unwrap(secondary);
            IOException explanation = new IOException("D8 failed in both engines. "
                + "Updated bundled D8: " + summarize(primary)
                + "; legacy D8: " + summarize(oldFailure), oldFailure);
            if (primary != null) explanation.addSuppressed(primary);
            throw explanation;
        }
    }

    /** Large library sets are dexed group by group (and cached); small ones in a single D8 run. */
    static void dexWith(Context context, ClassLoader loader, File codeJar, File platform, List<String> libraries,
                        File output, int minApi, ProjectBuild.Progress progress) throws Exception {
        if (IncrementalDex.worthIt(libraries)) {
            try {
                IncrementalDex.run(context.getCacheDir(), loader, codeJar, platform, libraries, output, minApi, progress);
                return;
            } catch (IncrementalDex.NoDexApi unsupported) {
                cleanDexOutput(output);   // this D8 build cannot take a classpath: use one run instead
            }
        }
        runD8(loader, codeJar, platform, libraries, output, minApi);
    }

    static void cleanDexOutput(File dir) throws IOException {
        File[] files = dir.listFiles();
        if (files != null) for (File file : files) {
            if (!file.isFile() || !file.getName().matches("classes(\\d*)\\.dex")) continue;
            if (!file.delete()) throw new IOException("Cannot clear incomplete D8 output " + file);
        }
    }

    /** Uses public D8Command.Builder APIs through the selected classloader. */
    static void runD8(ClassLoader loader, File codeJar, File platform, List<String> libraries,
                      File output, int minApi) throws Exception {
        List<File> programFiles = new ArrayList<File>();
        programFiles.add(codeJar);
        for (String library : libraries) programFiles.add(new File(library));
        runD8(loader, programFiles, Collections.<File>emptyList(), platform, output, minApi);
    }

    /** programFiles are compiled to DEX; classpathFiles are only read (desugaring), platform is android.jar. */
    static void runD8(ClassLoader loader, List<File> programFiles, List<File> classpathFiles, File platform,
                      File output, int minApi) throws Exception {
        Class<?> commandType = Class.forName("com.android.tools.r8.D8Command", true, loader);
        Object builder = commandType.getMethod("builder").invoke(null);
        Class<?> builderType = builder.getClass();
        Class<?> outputMode = Class.forName("com.android.tools.r8.OutputMode", true, loader);
        // The AIDE R8 bundle is Android-desugared: its D8 API accepts j$.nio.file.Path,
        // not java.nio.file.Path. Detect the builder signature rather than guessing.
        Method outputSetter = null;
        for (Method candidate : builderType.getMethods()) {
            Class<?>[] parameters = candidate.getParameterTypes();
            if (candidate.getName().equals("setOutput") && parameters.length == 2
                    && parameters[1].equals(outputMode)) { outputSetter = candidate; break; }
        }
        if (outputSetter == null) throw new NoSuchMethodException("D8 builder setOutput(Path, OutputMode)");
        Class<?> pathType = outputSetter.getParameterTypes()[0];
        List<Object> programs = new ArrayList<Object>();
        for (File program : programFiles) programs.add(d8Path(loader, pathType, program));
        builderType.getMethod("addProgramFiles", java.util.Collection.class).invoke(builder, programs);
        if (!classpathFiles.isEmpty()) {
            List<Object> classpath = new ArrayList<Object>();
            for (File item : classpathFiles) classpath.add(d8Path(loader, pathType, item));
            builderType.getMethod("addClasspathFiles", java.util.Collection.class).invoke(builder, classpath);
        }
        try {
            // Each D8 worker thread holds method code in memory; two keep the 512 MB heap safe.
            builderType.getMethod("setThreadCount", int.class).invoke(builder,
                Math.max(1, Math.min(2, Runtime.getRuntime().availableProcessors())));
        } catch (NoSuchMethodException olderD8) { }
        builderType.getMethod("addLibraryFiles", java.util.Collection.class)
            .invoke(builder, Collections.singletonList(d8Path(loader, pathType, platform)));
        builderType.getMethod("setMinApiLevel", int.class).invoke(builder, minApi);
        @SuppressWarnings("unchecked") Object mode = Enum.valueOf((Class<Enum>) outputMode, "DexIndexed");
        outputSetter.invoke(builder, d8Path(loader, pathType, output), mode);
        Object command = builderType.getMethod("build").invoke(builder);
        Class.forName("com.android.tools.r8.D8", true, loader)
            .getMethod("run", commandType).invoke(null, command);
    }

    static Object d8Path(ClassLoader loader, Class<?> type, File file) throws Exception {
        if (type.equals(Path.class)) return file.toPath();
        if ("j$.nio.file.Path".equals(type.getName())) {
            // DEX from the uploaded ZeroAicy AIDE APK includes j$ desugared NIO classes.
            Class<?> paths = Class.forName("j$.nio.file.Paths", true, loader);
            return paths.getMethod("get", String.class, String[].class)
                .invoke(null, file.getAbsolutePath(), new String[0]);
        }
        throw new IOException("Unsupported D8 Path API: " + type.getName());
    }

    static Throwable unwrap(Throwable error) {
        Throwable current = error;
        for (int i = 0; i < 8 && current != null; i++) {
            if (current instanceof InvocationTargetException && ((InvocationTargetException) current).getTargetException() != null)
                current = ((InvocationTargetException) current).getTargetException();
            else if (current instanceof java.lang.ExceptionInInitializerError
                    && ((java.lang.ExceptionInInitializerError)current).getException() != null)
                current = ((java.lang.ExceptionInInitializerError)current).getException();
            else break;
        }
        return current == null ? error : current;
    }

    static String summarize(Throwable t) {
        if (t == null) return "unknown";
        String msg = t.getMessage() == null ? "" : t.getMessage();
        StackTraceElement[] trace = t.getStackTrace();
        return first(t.getClass().getSimpleName() + (msg.length() == 0 ? "" : ": " + msg)
            + (trace.length > 0 ? " at " + trace[0] : ""), 700);
    }

    static String diagnostic(Throwable error) {
        StringWriter out = new StringWriter();
        PrintWriter writer = new PrintWriter(out);
        error.printStackTrace(writer);
        writer.flush();
        return first(out.toString(), 18000);
    }

    static void saveBuildError(Context context, Throwable error) {
        try {
            File report = new File(context.getFilesDir(), "last-build-error.txt");
            FileOutputStream stream = new FileOutputStream(report);
            try { stream.write(diagnostic(error).getBytes("UTF-8")); }
            finally { stream.close(); }
        } catch (Exception ignored) { }
    }

    /** Counts raw ZIP bytes so resources.arsc can be aligned before APK signing. */
    static final class ZipByteCounter extends OutputStream {
        final OutputStream delegate;
        long count;
        ZipByteCounter(OutputStream delegate) { this.delegate = delegate; }
        @Override public void write(int value) throws IOException { delegate.write(value); count++; }
        @Override public void write(byte[] bytes, int off, int len) throws IOException {
            delegate.write(bytes, off, len);
            count += len;
        }
        @Override public void flush() throws IOException { delegate.flush(); }
        @Override public void close() throws IOException { delegate.close(); }
    }

    /**
     * resources.arsc must be uncompressed AND start on a four-byte boundary.
     * The ZIP local file header is 30 bytes + UTF-8 name + extra field.
     * Extra fields must have at least a four-byte field header.
     */
    static byte[] resourceAlignmentExtra(long localHeaderOffset, String filename, int attempt) {
        int nameLength = filename.getBytes(StandardCharsets.UTF_8).length;
        int remainder = (int) ((localHeaderOffset + 30 + nameLength) % 4);
        int length = remainder == 0 ? 0 : 4 + ((4 - remainder) % 4);
        // Some Android ZIP implementations insert additional local-header fields.
        // Try every residue class, inspecting the *actual* output after each write.
        if (attempt != 0) {
            length = length == 0 ? 4 + attempt : 4 + ((length - 4 + attempt) % 4);
        }
        if (length == 0) return new byte[0];
        byte[] extra = new byte[length];
        // Private padding extra field: id 0xA11E, payload length = length - 4.
        extra[0] = (byte) 0x1e;
        extra[1] = (byte) 0xa1;
        extra[2] = (byte) (length - 4);
        extra[3] = 0;
        return extra;
    }

    /** Align by checking the actual ZIP bytes, not only a predicted entry size. */
    static void mergeApk(File base, File dexFolder, File soFolder, File outFile) throws Exception {
        IOException lastAlignment = null;
        for (int attempt = 0; attempt < 4; attempt++) {
            mergeApkAttempt(base, dexFolder, soFolder, outFile, attempt);
            try {
                ApkResourceAlignment.verify(outFile);
                return;
            } catch (IOException invalid) {
                // Retry only the alignment problem. Missing/compressed resources are real failures.
                if (invalid.getMessage() == null ||
                        !invalid.getMessage().contains("not 4-byte aligned")) throw invalid;
                lastAlignment = invalid;
            }
        }
        throw new IOException("Unable to align resources.arsc after four ZIP padding attempts: "
                + (lastAlignment == null ? "unknown" : lastAlignment.getMessage()), lastAlignment);
    }

    static void mergeApkAttempt(File base, File dexFolder, File soFolder, File outFile,
                                int attempt) throws Exception {
        ZipFile zip = new ZipFile(base);
        ZipByteCounter counter = new ZipByteCounter(new FileOutputStream(outFile));
        ZipOutputStream output = new ZipOutputStream(counter);
        Set<String> names = new HashSet<String>();
        try {
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry item = entries.nextElement();
                if (item.isDirectory() || item.getName().startsWith("META-INF/")) continue;
                if (!names.add(item.getName())) throw new IOException("Duplicate resource " + item.getName());
                ZipEntry dst = new ZipEntry(item.getName());
                if ("resources.arsc".equals(item.getName())) {
                    if (item.getSize() < 0 || item.getSize() > MAX_ZIP_INPUT)
                        throw new IOException("Unsupported resource table size");
                    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                    InputStream in = zip.getInputStream(item);
                    try { copy(in, buffer); } finally { in.close(); }
                    byte[] data = buffer.toByteArray();
                    CRC32 crc = new CRC32(); crc.update(data);
                    dst.setMethod(ZipEntry.STORED);
                    dst.setSize(data.length); dst.setCompressedSize(data.length); dst.setCrc(crc.getValue());
                    // Avoid ZIP extended timestamp fields changing the local-header size.
                    dst.setTime(315532800000L); // 1980-01-01, DOS ZIP epoch
                    byte[] padding = resourceAlignmentExtra(counter.count, dst.getName(), attempt);
                    if (padding.length != 0) dst.setExtra(padding);
                    long expectedDataOffset = counter.count + 30
                            + dst.getName().getBytes(StandardCharsets.UTF_8).length + padding.length;
                    // The predicted offset is informative; only the final ZIP's *measured*
                    // offset determines correctness. Android may add header extensions.
                    output.putNextEntry(dst);
                    output.write(data);
                } else {
                    output.putNextEntry(dst);
                    InputStream in = zip.getInputStream(item);
                    try { copy(in, output); } finally { in.close(); }
                }
                output.closeEntry();
            }
            // For Android 11+, the package table MUST be stored, not compressed.
            if (!names.contains("resources.arsc")) throw new IOException("AAPT did not emit resources.arsc");
            File[] dex = dexFolder.listFiles();
            if (dex == null) throw new IOException("No DEX directory");
            Arrays.sort(dex);
            for (File file : dex) if (file.getName().matches("classes(\\d*)\\.dex")) {
                ZipEntry entry = new ZipEntry(file.getName());
                if (!names.add(entry.getName())) throw new IOException("Duplicate dex name");
                output.putNextEntry(entry);
                FileInputStream in = new FileInputStream(file);
                try { copy(in, output); } finally { in.close(); }
                output.closeEntry();
            }
            if (soFolder.isDirectory()) zipNativeLibraries(soFolder, output, names);
        } finally { try { output.close(); } finally { zip.close(); } }
    }

    /** Classic AIDE libs/ contains JARs as well as ABI-specific native libraries.
     * Only copy .so files located inside standard Android ABI directories. */
    static void zipNativeLibraries(File nativeRoot, ZipOutputStream output, Set<String> names) throws Exception {
        String[] abis = {"armeabi", "armeabi-v7a", "arm64-v8a", "x86", "x86_64", "mips", "mips64"};
        for (String abi : abis) {
            File folder = new File(nativeRoot, abi);
            if (!folder.isDirectory()) continue;
            File[] entries = folder.listFiles();
            if (entries == null) continue;
            for (File file : entries) {
                if (!file.isFile() || !file.getName().endsWith(".so")) continue;
                String name = "lib/" + abi + "/" + file.getName();
                if (!names.add(name)) throw new IOException("Duplicate native library " + name);
                ZipEntry e = new ZipEntry(name);
                output.putNextEntry(e);
                FileInputStream in = new FileInputStream(file);
                try { copy(in, output); } finally { in.close(); }
                output.closeEntry();
            }
        }
    }

    /**
     * AndroidKeyStore keeps independent Debug and local Release identities.
     * A local Release key is non-exportable and is removed if the app is uninstalled
     * or Android Keystore is reset. This is suitable for local testing/distribution,
     * NOT as a portable Google Play publishing key. Neither the debug nor the release
     * identity is imported from or mixed with the other one.
     */
    static void sign(Context context, File unsigned, File signed, boolean release, String packageName) throws Exception {
        // Stable, separate signing identities for different release app packages.
        String alias = release ? RELEASE_KEY_ALIAS + "-" + Integer.toHexString(packageName.hashCode()) : KEY_ALIAS;
        String label = release ? "AideUI Local Release" : "AideUI Debug";
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(alias)) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA", "AndroidKeyStore");
            long now = System.currentTimeMillis();
            KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(alias,
                    KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                .setKeySize(2048)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                .setCertificateSubject(new X500Principal("CN=" + label))
                .setCertificateSerialNumber(BigInteger.valueOf(now))
                .setCertificateNotBefore(new Date(now - 86400000L))
                .setCertificateNotAfter(new Date(now + 20L * 365 * 24 * 3600000L))
                .build();
            generator.initialize(spec);
            generator.generateKeyPair();
            store.load(null);
        }
        PrivateKey key = (PrivateKey) store.getKey(alias, null);
        Certificate certificate = store.getCertificate(alias);
        if (key == null || !(certificate instanceof X509Certificate))
            throw new IOException(label + " certificate unavailable");
        Class<?> configBuilder = Class.forName("com.android.apksig.ApkSigner$SignerConfig$Builder");
        Object signerConfig = configBuilder.getConstructor(String.class, PrivateKey.class, List.class)
            .newInstance(label, key, Collections.singletonList((X509Certificate) certificate));
        Object config = configBuilder.getMethod("build").invoke(signerConfig);
        Class<?> signerBuilder = Class.forName("com.android.apksig.ApkSigner$Builder");
        Object builder = signerBuilder.getConstructor(List.class).newInstance(Collections.singletonList(config));
        signerBuilder.getMethod("setInputApk", File.class).invoke(builder, unsigned);
        signerBuilder.getMethod("setOutputApk", File.class).invoke(builder, signed);
        signerBuilder.getMethod("setV1SigningEnabled", boolean.class).invoke(builder, true);
        signerBuilder.getMethod("setV2SigningEnabled", boolean.class).invoke(builder, true);
        Object signer = signerBuilder.getMethod("build").invoke(builder);
        try { signer.getClass().getMethod("sign").invoke(signer); }
        catch (InvocationTargetException e) {
            throw new IOException("APK signing: " + e.getTargetException(), e.getTargetException());
        }
    }

}
