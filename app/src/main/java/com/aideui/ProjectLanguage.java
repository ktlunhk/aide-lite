package com.aideui;

import java.io.File;

/** Decides whether "Create New Class" should produce a .kt or a .java file. */
final class ProjectLanguage {
    private ProjectLanguage() { }

    /**
     * 1. the folder you are in (only .kt files = Kotlin, only .java files = Java)
     * 2. otherwise the whole module's src tree (more .kt than .java = Kotlin)
     * 3. otherwise the module's Gradle file (Kotlin plugin = Kotlin)
     * 4. otherwise Java
     */
    static boolean isKotlin(File dir, File module) {
        int[] folder = new int[2];
        count(dir, folder, 0, false);
        if (folder[0] > 0 && folder[1] == 0) return true;
        if (folder[1] > 0 && folder[0] == 0) return false;
        if (module == null) return false;
        int[] all = new int[2];
        count(new File(module, "src"), all, 0, true);
        if (all[0] != all[1]) return all[0] > all[1];
        try {
            File gradle = MavenLibraries.gradleFile(module);
            if (gradle != null && gradle.isFile()) {
                String text = MavenLibraries.readText(gradle);
                return text.contains("org.jetbrains.kotlin") || text.contains("kotlin-android")
                    || text.contains("kotlin(\"android\")") || text.contains("kotlin-gradle-plugin");
            }
        } catch (Exception ignored) { }
        return false;
    }

    /** counts[0] = .kt files, counts[1] = .java files. */
    private static void count(File dir, int[] counts, int depth, boolean recurse) {
        if (dir == null || depth > 12 || counts[0] + counts[1] > 300) return;
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File f : kids) {
            String n = f.getName();
            if (f.isFile()) {
                if (n.endsWith(".kt")) counts[0]++;
                else if (n.endsWith(".java")) counts[1]++;
            } else if (recurse && !n.equals("build") && !n.startsWith(".")) {
                count(f, counts, depth + 1, true);
            }
        }
    }
}
