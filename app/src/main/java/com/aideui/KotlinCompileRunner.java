package com.aideui;

import dalvik.system.DexClassLoader;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Runs K2JVMCompiler from the installed DEX bundle. It is process-agnostic: KotlinCompileService calls
 * it inside the isolated ":kotlinc" process, and KotlinCompiler uses it in-process only as a fallback.
 *
 * Every step records its name in stage.txt BEFORE it runs, so if the process dies (out of memory,
 * native crash) the last stage is still on disk and the failure can be reported precisely.
 */
final class KotlinCompileRunner {
    static final int OK = 0, COMPILE_ERRORS = 1, INTERNAL_ERROR = 2, PROCESS_DIED = 3;

    static final class Outcome {
        int code = INTERNAL_ERROR;
        String stage = "start";
        String detail = "";
    }

    private KotlinCompileRunner() { }

    /**
     * The compiler bundle carries its own Kotlin stdlib. AideUI's APK contains an older one (via its
     * own dependencies), and a normal parent-first loader would hand the compiler those old classes
     * (NoSuchMethodError on FunctionReferenceImpl). Platform packages stay parent-first.
     */
    static final class ChildFirstLoader extends DexClassLoader {
        private static final String[] PLATFORM = { "java.", "javax.", "android.", "dalvik.", "libcore.",
            "sun.", "org.w3c.", "org.xml.", "org.json.", "org.apache.harmony." };
        private final ClassLoader parentLoader;
        ChildFirstLoader(String dexPath, String optimizedDir, ClassLoader parent) {
            super(dexPath, optimizedDir, null, parent);
            parentLoader = parent;
        }
        private static boolean platform(String name) {
            for (String prefix : PLATFORM) if (name.startsWith(prefix)) return true;
            return false;
        }
        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (this) {
                Class<?> c = findLoadedClass(name);
                if (c == null) {
                    if (platform(name)) {
                        try { c = parentLoader.loadClass(name); }
                        catch (ClassNotFoundException notInPlatform) { c = findClass(name); }
                    } else {
                        try { c = findClass(name); }
                        catch (ClassNotFoundException notInBundle) { c = parentLoader.loadClass(name); }
                    }
                }
                if (resolve) resolveClass(c);
                return c;
            }
        }
    }

    /** Must run on a thread with a large stack: the compiler recurses deeply. */
    static Outcome run(File compilerDex, File argsFile, File logFile, File stageFile, File dexCache,
                       File propertiesFile) {
        Outcome outcome = new Outcome();
        ByteArrayOutputStream diagnostic = new ByteArrayOutputStream();
        PrintStream printer = null;
        try {
            printer = new PrintStream(diagnostic, true, "UTF-8");
            outcome.stage = mark(stageFile, "read-arguments");
            List<String> args = readLines(argsFile);

            outcome.stage = mark(stageFile, "environment");
            applyEnvironment(propertiesFile);

            outcome.stage = mark(stageFile, "load-compiler");
            ClassLoader loader = new ChildFirstLoader(compilerDex.getAbsolutePath(),
                dexCache.getAbsolutePath(), KotlinCompileRunner.class.getClassLoader());
            Class<?> main;
            try { main = Class.forName("org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", true, loader); }
            catch (Throwable err) { return fail(outcome, "Compiler class cannot load on this device", err); }

            outcome.stage = mark(stageFile, "initialize-compiler");
            Object instance;
            try { instance = main.newInstance(); }
            catch (Throwable err) { return fail(outcome, "Compiler failed to initialize", err); }

            outcome.stage = mark(stageFile, "compile");
            Object status;
            try { status = exec(main, instance, loader, printer, args.toArray(new String[0])); }
            catch (Throwable err) { return fail(outcome, "Compiler crashed while compiling", err); }

            String name = status == null ? "null" : status.toString();
            outcome.stage = mark(stageFile, "done:" + name);
            if ("OK".equals(name)) outcome.code = OK;
            else if ("COMPILATION_ERROR".equals(name)) outcome.code = COMPILE_ERRORS;
            else { outcome.code = INTERNAL_ERROR; outcome.detail = "Compiler exit status " + name; }
        } catch (Throwable t) {
            fail(outcome, "Kotlin runner error", t);
        } finally {
            if (printer != null) printer.close();
            try { writeBytes(logFile, diagnostic.toByteArray()); } catch (IOException ignored) { }
        }
        return outcome;
    }

    private static Object exec(Class<?> main, Object instance, ClassLoader loader, PrintStream printer,
                               String[] argv) throws Throwable {
        try {
            Class<?> rendererType = Class.forName(
                "org.jetbrains.kotlin.cli.common.messages.MessageRenderer", true, loader);
            Object renderer;
            try { renderer = rendererType.getField("PLAIN_FULL_PATHS").get(null); }
            catch (NoSuchFieldException e) { renderer = rendererType.getField("PLAIN_RELATIVE_PATHS").get(null); }
            Method exec = main.getMethod("exec", PrintStream.class, rendererType, String[].class);
            return invoke(exec, instance, printer, renderer, argv);
        } catch (ClassNotFoundException e) {
        } catch (NoSuchMethodException e) {
        } catch (NoSuchFieldException e) {
        }
        Method exec = main.getMethod("exec", PrintStream.class, String[].class);
        return invoke(exec, instance, printer, argv);
    }

    private static Object invoke(Method m, Object target, Object... params) throws Throwable {
        try { return m.invoke(target, params); }
        catch (InvocationTargetException e) { throw e.getCause() != null ? e.getCause() : e; }
    }

    private static Outcome fail(Outcome outcome, String what, Throwable t) {
        outcome.code = INTERNAL_ERROR;
        String hint = t instanceof OutOfMemoryError
            ? "\nOut of memory: close other apps or build fewer files at once." : "";
        outcome.detail = what + ":\n" + KotlinCompilerInstaller.chain(t) + hint;
        return outcome;
    }

    /** jvm.properties lets the user change compiler JVM settings without rebuilding AideUI. */
    private static void applyEnvironment(File propertiesFile) throws IOException {
        System.setProperty("java.awt.headless", "true");
        System.setProperty("idea.io.use.nio2", "true");
        if (propertiesFile != null && propertiesFile.isFile()) {
            Properties p = new Properties();
            FileInputStream in = new FileInputStream(propertiesFile);
            try { p.load(in); } finally { in.close(); }
            for (String key : p.stringPropertyNames()) System.setProperty(key, p.getProperty(key));
        }
    }

    static String mark(File stageFile, String stage) {
        try { writeBytes(stageFile, stage.getBytes("UTF-8")); } catch (IOException ignored) { }
        return stage;
    }

    static List<String> readLines(File f) throws IOException {
        List<String> lines = new ArrayList<String>();
        String text = new String(KotlinToolchain.readAll(new FileInputStream(f)), "UTF-8");
        for (String line : text.split("\\n")) if (line.length() > 0) lines.add(line);
        return lines;
    }

    static void writeBytes(File f, byte[] data) throws IOException {
        FileOutputStream out = new FileOutputStream(f);
        try { out.write(data); } finally { out.close(); }
    }
}
