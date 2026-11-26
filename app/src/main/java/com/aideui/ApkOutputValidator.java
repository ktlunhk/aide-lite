package com.aideui;

import java.io.File;
import java.io.IOException;
import java.util.zip.ZipFile;

/** Checks APK output from an Android project, without depending on Android APIs. */
public final class ApkOutputValidator {
    private ApkOutputValidator() { }

    public static boolean isBuildOutput(File apk, File externalStorage) throws IOException {
        if (apk == null || externalStorage == null || !apk.isFile() || apk.length() == 0 ||
                !apk.getName().toLowerCase(java.util.Locale.US).endsWith(".apk")) return false;
        String full = apk.getCanonicalPath();
        String root = externalStorage.getCanonicalPath();
        if (!full.startsWith(root + File.separator)) return false;

        File outputDir = apk.getCanonicalFile().getParentFile();
        if (outputDir == null) return false;
        boolean supportedLayout = false;
        if ("bin".equals(outputDir.getName())) {
            // Classic AIDE/Eclipse projects: <project>/bin/<project>-debug-aideui.apk
            File project = outputDir.getParentFile();
            supportedLayout = project != null && new File(project, "AndroidManifest.xml").isFile()
                    && new File(project, "src").isDirectory();
        } else {
            // Gradle modules: <module>/build/outputs/apk/[variant]/app-debug.apk
            for (File dir = outputDir; dir != null; dir = dir.getParentFile()) {
                if (!"apk".equals(dir.getName())) continue;
                File outputs = dir.getParentFile();
                File build = outputs == null ? null : outputs.getParentFile();
                File module = build == null ? null : build.getParentFile();
                if (outputs == null || !"outputs".equals(outputs.getName()) || build == null ||
                    !"build".equals(build.getName()) || module == null) continue;
                File manifest = new File(module, "src/main/AndroidManifest.xml");
                supportedLayout = manifest.isFile() || new File(module, "build.gradle").isFile()
                        || new File(module, "build.gradle.kts").isFile();
                break;
            }
        }
        if (!supportedLayout) return false;

        // A ZIP extension alone is not proof that an APK is installable.
        ZipFile zip = null;
        try {
            zip = new ZipFile(apk);
            boolean hasEssentials = zip.getEntry("AndroidManifest.xml") != null && zip.getEntry("classes.dex") != null;
            if (!hasEssentials) return false;
            // Catch the same failure that caused MyApp's Android installer rejection.
            try { ApkResourceAlignment.verify(apk); return true; }
            catch (IOException invalidAlignment) { return false; }
        } catch (IOException invalidZip) {
            return false;
        } finally {
            if (zip != null) zip.close();
        }
    }
}
