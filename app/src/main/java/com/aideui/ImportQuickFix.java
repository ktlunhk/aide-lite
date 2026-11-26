package com.aideui;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Explicit, previewable removal of selected AndroidX imports in legacy Android apps.
 *  This is a source edit, NOT an AndroidX dependency installer or a semantic resolver.
 */
public final class ImportQuickFix {
    private ImportQuickFix() { }

    private static final Pattern ANDROIDX_IMPORT = Pattern.compile(
        "(?m)^[ \\t]*import[ \\t]+(?:static[ \\t]+)?(androidx(?:\\.[A-Za-z_$][\\w$]*)+(?:\\.\\*)?)[ \\t]*;?[ \\t]*(?:\\r?\\n|$)");

    /** Fully-qualified names, one per import; do not infer whether the import is used. */
    public static List<String> findAndroidxImports(String source) {
        List<String> imports = new ArrayList<String>();
        Matcher matcher = ANDROIDX_IMPORT.matcher(source);
        while (matcher.find()) {
            String qualified = matcher.group(1);
            if (!imports.contains(qualified)) imports.add(qualified);
        }
        return imports;
    }

    /** Remove ONLY the import the user chose. Always preview before applying. */
    public static RefactorEngine.Plan removeImport(File file, String qualified) throws IOException {
        if (qualified == null || !qualified.startsWith("androidx."))
            throw new IOException("Choose an AndroidX import from this source file.");
        String source = RefactorEngine.read(file);
        Matcher matcher = ANDROIDX_IMPORT.matcher(source);
        int first = -1, last = -1;
        while (matcher.find()) {
            if (qualified.equals(matcher.group(1))) {
                first = matcher.start(); last = matcher.end();
                break;
            }
        }
        if (first < 0) throw new IOException("The selected import has changed. Reopen the file and try again.");
        String updated = source.substring(0, first) + source.substring(last);
        RefactorEngine.Plan plan = new RefactorEngine.Plan(
            "Remove selected import: " + qualified +
            "\n\nUse only if the source does NOT require AndroidX classes. " +
            "If AndroidX APIs are used, keep the import and add the matching " +
            "AndroidX dependencies to the project build/classpath instead.");
        plan.add(file, file, source, updated, 1);
        plan.openAfter = file;
        return plan;
    }
}
