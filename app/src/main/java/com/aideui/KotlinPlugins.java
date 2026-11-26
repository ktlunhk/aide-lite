package com.aideui;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

/**
 * Kotlin compiler plugins for the on-device compiler (currently the Compose compiler plugin).
 *
 * Why this is more than "pass -Xplugin": the compiler runs on ART, where a plain plugin .jar cannot be
 * loaded (ART cannot define classes from .class files). The plugin is therefore converted to DEX once
 * with D8, stored as a read-only "<plugin>.dex.jar" (classes.dex + META-INF/services), and that file is
 * what -Xplugin points at. Its version must equal the installed compiler's Kotlin version.
 */
final class KotlinPlugins {
    private KotlinPlugins() { }

    static final String COMPOSE_CLASS = "androidx/compose/compiler/plugins/kotlin/ComposePluginRegistrar.class";
    private static final Pattern COMPOSE_TRUE = Pattern.compile("compose\\s*(=\\s*)?true");
    private static final Pattern ALIAS = Pattern.compile("alias\\s*\\(\\s*libs\\.plugins\\.([A-Za-z0-9_.\\-]+)\\s*\\)");

    static File dir(Context context) { return new File(KotlinToolchain.dir(context), "plugins"); }
    static File composeDex(Context context) { return new File(dir(context), "compose-compiler-plugin.dex.jar"); }
    private static File composeVersion(Context context) { return new File(dir(context), "compose-compiler-plugin.version"); }

    static String kotlinVersion(Context context) {
        String v = KotlinToolchain.version(context);
        return v != null ? v : KotlinCompilerInstaller.KOTLIN_VERSION;
    }

    // ---------- detection ----------

    /** True when the module's Gradle configuration applies the Compose compiler plugin / enables Compose. */
    static boolean usesCompose(File module) {
        if (module == null) return false;
        List<File> gradle = new ArrayList<File>();
        addBuildFiles(module, gradle);
        File parent = module.getParentFile();
        if (parent != null) addBuildFiles(parent, gradle);
        String catalog = readCatalog(module);
        for (File g : gradle) {
            String text = read(g);
            if (text.contains("org.jetbrains.kotlin.plugin.compose") || text.contains("kotlin-compose")
                    || text.contains("plugins.kotlin.compose") || COMPOSE_TRUE.matcher(text).find()) {
                // The root file often only declares the plugin "apply false"; only the module's own file counts then.
                if (g.getParentFile().equals(module) || !text.contains("apply false")) return true;
            }
            Matcher m = ALIAS.matcher(text);
            while (m.find() && catalog.length() > 0) {
                String wanted = normalize(m.group(1));
                for (String line : catalog.split("\\n")) {
                    int eq = line.indexOf('=');
                    if (eq < 0 || !line.contains("plugin.compose")) continue;
                    if (normalize(line.substring(0, eq)).equals(wanted) && g.getParentFile().equals(module)) return true;
                }
            }
        }
        return false;
    }

    private static void addBuildFiles(File dir, List<File> out) {
        for (String n : new String[]{"build.gradle", "build.gradle.kts"}) {
            File f = new File(dir, n);
            if (f.isFile()) out.add(f);
        }
    }

    private static String readCatalog(File module) {
        File dir = module;
        for (int i = 0; i < 3 && dir != null; i++, dir = dir.getParentFile()) {
            File toml = new File(dir, "gradle/libs.versions.toml");
            if (toml.isFile()) return read(toml);
        }
        return "";
    }

    private static String normalize(String s) { return s.trim().replaceAll("[\\-_.]", "").toLowerCase(java.util.Locale.US); }

    private static String read(File f) {
        try { return new String(KotlinToolchain.readAll(new FileInputStream(f)), "UTF-8"); }
        catch (IOException e) { return ""; }
    }

    // ---------- installed plugins ----------

    static boolean composeInstalled(Context context) {
        File dex = composeDex(context);
        if (!dex.isFile() || dex.length() == 0) return false;
        return kotlinVersion(context).equals(read(composeVersion(context)).trim());   // stale after a compiler change
    }

    /** Plugin DEX jars to pass as -Xplugin for this module (empty when none is needed or ready). */
    static List<File> installed(Context context, File module) {
        List<File> list = new ArrayList<File>();
        if (usesCompose(module) && composeInstalled(context)) list.add(composeDex(context));
        return list;
    }

    /** Installs whatever the module needs; no-op when already installed. */
    static void ensureInstalled(Context context, File module, final ProjectBuild.Progress progress) throws Exception {
        if (!usesCompose(module) || composeInstalled(context)) return;
        installCompose(context, new KotlinCompilerInstaller.Log() {
            public void update(String message) { progress.update(message, 31); }
        });
    }

    /**
     * Gets kotlin-compose-compiler-plugin (same version as the compiler) from the offline folder or
     * Maven Central, converts it to DEX and stores it as the read-only plugin bundle.
     */
    static void installCompose(Context context, KotlinCompilerInstaller.Log log) throws Exception {
        File compilerFile = KotlinToolchain.compiler(context);
        if (!compilerFile.isFile())
            throw new IOException("The Kotlin compiler is not installed (AideUI bundles it in assets/kotlin-compiler-android.dex.jar).");
        String version = kotlinVersion(context);
        // The compiler reports e.g. "2.3.21-release-298"; Maven Central publishes the plugin as "2.3.21".
        String mavenVersion = version.replaceFirst("-release-\\d+$", "");
        File root = new File(KotlinToolchain.dir(context), "build/compose-plugin");
        KotlinCompilerInstaller.deleteTree(root);
        File module = new File(root, "module");
        if (!module.mkdirs()) throw new IOException("Cannot create " + module);

        File jar = findLocalPlugin();
        if (jar == null) {
            log.update("Downloading the Compose compiler plugin " + mavenVersion + "…");
            FileOutputStream g = new FileOutputStream(new File(module, "build.gradle"));
            try {
                g.write(("dependencies {\n    implementation 'org.jetbrains.kotlin:kotlin-compose-compiler-plugin:"
                    + mavenVersion + "'\n}\n").getBytes("UTF-8"));
            } finally { g.close(); }
            MavenLibraries.Summary summary = MavenLibraries.sync(module, new MavenLibraries.Progress() {
                public void update(String m) { }
            }, true);
            for (File candidate : summary.jars)
                if (KotlinCompilerInstaller.hasEntry(candidate, COMPOSE_CLASS)) { jar = candidate; break; }
            if (jar == null)
                throw new IOException("Could not download org.jetbrains.kotlin:kotlin-compose-compiler-plugin:" + mavenVersion
                    + "\n" + summary.message(module)
                    + "\nOffline alternative: put kotlin-compose-compiler-plugin-" + mavenVersion + ".jar into "
                    + KotlinCompilerInstaller.jarFolder() + " and build again.");
        }

        File platform = JavaCompiler.findAndroidJar(context, true);
        if (platform == null) throw new IOException("Bundled android.jar unavailable");
        File dexOut = new File(root, "dex");
        if (!dexOut.mkdirs()) throw new IOException("Cannot create " + dexOut);
        List<File> jars = new ArrayList<File>();
        jars.add(jar);
        log.update("Converting the Compose compiler plugin to DEX (one time)…");
        KotlinCompilerInstaller.convertAll(context, jars, KotlinToolchain.cachedCompilerJars(context), platform, dexOut, log);
        File bundle = new File(root, "compose-compiler-plugin.dex.jar");
        KotlinCompilerInstaller.packageBundle(dexOut, jars, bundle);
        ZipFile check = new ZipFile(bundle);
        try {
            if (check.getEntry("classes.dex") == null) throw new IOException("Plugin conversion produced no classes.dex");
            if (check.getEntry("META-INF/services/org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar") == null)
                throw new IOException("Plugin bundle lost its compiler plugin registration");
        } finally { check.close(); }

        File target = composeDex(context);
        File folder = dir(context);
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("Cannot create " + folder);
        if (target.exists() && !target.delete()) throw new IOException("Cannot replace " + target);
        // Android 14+ refuses to load writable code files.
        if (!bundle.setReadOnly()) throw new IOException("Cannot mark the plugin read-only");
        if (!bundle.renameTo(target)) throw new IOException("Cannot install the Compose plugin bundle");
        FileOutputStream v = new FileOutputStream(composeVersion(context));
        try { v.write((version + "\n").getBytes("UTF-8")); } finally { v.close(); }
        KotlinCompilerInstaller.deleteTree(root);
    }

    private static File findLocalPlugin() {
        File[] files = KotlinCompilerInstaller.jarFolder().listFiles();
        if (files == null) return null;
        for (File f : files) {
            String n = f.getName().toLowerCase(java.util.Locale.US);
            if (f.isFile() && n.endsWith(".jar") && n.contains("compose-compiler-plugin")
                    && !n.contains("embeddable") && KotlinCompilerInstaller.hasEntry(f, COMPOSE_CLASS)) return f;
        }
        return null;
    }
}
