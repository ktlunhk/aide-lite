package com.aideui;

import android.content.Context;
import dalvik.system.DexClassLoader;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.List;

/** Loads the user's AIDE-bundled Android D8/R8 bytecode without starting another app. */
public final class AndroidR8Loader {
    private AndroidR8Loader() { }
    private static volatile ClassLoader cached;

    public static synchronized ClassLoader get(Context context) throws Exception {
        if (cached != null) return cached;
        File folder = new File(context.getCodeCacheDir(), "aideui-d8-cb2f0927");
        if (!folder.isDirectory() && !folder.mkdirs()) throw new java.io.IOException("Cannot create D8 code directory");
        File original = new File(folder, "r8-android-bundle.zip");
        if (!original.isFile() || !R8DexBundle.SHA256.equals(R8DexBundle.sha256(original))) {
            File tmp = new File(folder, "r8-android-bundle.zip.tmp");
            if (tmp.exists() && !tmp.delete()) throw new java.io.IOException("Cannot replace D8 temporary file");
            InputStream src = context.getAssets().open("zeroaicy-r8-android.zip");
            FileOutputStream dst = new FileOutputStream(tmp);
            try {
                byte[] bytes = new byte[32768]; int n;
                while ((n = src.read(bytes)) != -1) dst.write(bytes, 0, n);
            } finally { try { src.close(); } finally { dst.close(); } }
            if (!R8DexBundle.SHA256.equals(R8DexBundle.sha256(tmp))) {
                tmp.delete(); throw new java.io.IOException("Bundled D8 asset failed SHA-256 verification");
            }
            if (!tmp.setReadOnly()) throw new java.io.IOException("Cannot mark D8 bundle read-only");
            if (original.exists() && !original.delete()) throw new java.io.IOException("Cannot replace cached D8 bundle");
            if (!tmp.renameTo(original)) throw new java.io.IOException("Cannot install D8 asset");
        }
        List<File> jars = R8DexBundle.prepare(original, folder);
        StringBuilder dexPath = new StringBuilder();
        for (File f : jars) {
            if (dexPath.length() > 0) dexPath.append(File.pathSeparatorChar);
            dexPath.append(f.getAbsolutePath());
        }
        ClassLoader loader = new ToolsClassLoader(dexPath.toString(),
            context.getCodeCacheDir().getAbsolutePath(), context.getClassLoader());
        Class<?> d8 = Class.forName("com.android.tools.r8.D8", false, loader);
        if (d8.getClassLoader() != loader)
            throw new java.io.IOException("Updated D8 could not load from extracted AIDE bundle");
        cached = loader;
        return loader;
    }

    /** Child-first only for isolated build-tool packages; the app SDK stays parent-first. */
    static final class ToolsClassLoader extends DexClassLoader {
        ToolsClassLoader(String dex, String optimized, ClassLoader parent) { super(dex, optimized, null, parent); }
        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith("com.android.tools.r8.") || name.startsWith("io.github.zeroaicy.r8.")
                    || name.startsWith("j$.")) {
                synchronized (this) {
                    Class<?> found = findLoadedClass(name);
                    if (found == null) {
                        try { found = findClass(name); }
                        catch (ClassNotFoundException ignored) { }
                    }
                    if (found != null) {
                        if (resolve) resolveClass(found);
                        return found;
                    }
                }
            }
            return super.loadClass(name, resolve);
        }
    }
}
