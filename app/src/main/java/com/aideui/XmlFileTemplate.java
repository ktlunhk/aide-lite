package com.aideui;

import java.io.File;
import java.util.Locale;
import java.util.regex.Pattern;

/** Creates a valid starter XML document for the selected Android resource folder. */
public final class XmlFileTemplate {
    private XmlFileTemplate() { }

    private static final Pattern RESOURCE_NAME = Pattern.compile("[a-z][a-z0-9_]*");
    private static final Pattern GENERAL_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_.-]*");
    private static final String HEADER = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n";
    private static final String ANDROID_NS = "xmlns:android=\"http://schemas.android.com/apk/res/android\"";

    public static String normalizeName(String entered, File folder) {
        if (entered == null || folder == null) throw new IllegalArgumentException("Enter a file name");
        String name = entered.trim();
        if (name.toLowerCase(Locale.US).endsWith(".xml")) name = name.substring(0, name.length() - 4);
        if (name.length() == 0 || name.equals(".") || name.equals("..") || name.indexOf('/') >= 0
                || name.indexOf('\\') >= 0 || name.indexOf('\u0000') >= 0 || name.indexOf(':') >= 0) {
            throw new IllegalArgumentException("Enter a single XML filename without a path");
        }
        boolean resource = resourceFolderType(folder) != null;
        if (!(resource ? RESOURCE_NAME : GENERAL_NAME).matcher(name).matches()) {
            throw new IllegalArgumentException(resource
                    ? "Android resource names must use lowercase letters, digits and underscores and start with a letter"
                    : "Invalid XML filename");
        }
        return name + ".xml";
    }

    /** Returns the folder type for res/<type> and res/<type>-qualifier paths. */
    public static String resourceFolderType(File folder) {
        if (folder == null) return null;
        File parent = folder.getParentFile();
        if (parent == null || !"res".equals(parent.getName())) return null;
        String name = folder.getName();
        int hyphen = name.indexOf('-');
        return hyphen >= 0 ? name.substring(0, hyphen) : name;
    }

    public static String source(File folder, String fileName) {
        String type = resourceFolderType(folder);
        if ("values".equals(type)) return HEADER + "<resources>\n\n</resources>\n";
        if ("layout".equals(type)) return HEADER
                + "<LinearLayout " + ANDROID_NS + "\n"
                + "    android:layout_width=\"match_parent\"\n"
                + "    android:layout_height=\"match_parent\"\n"
                + "    android:orientation=\"vertical\">\n\n"
                + "</LinearLayout>\n";
        if ("menu".equals(type)) return HEADER + "<menu " + ANDROID_NS + ">\n\n</menu>\n";
        if ("drawable".equals(type)) return HEADER
                + "<shape " + ANDROID_NS + " android:shape=\"rectangle\">\n"
                + "    <solid android:color=\"#FFFFFF\" />\n</shape>\n";
        if ("color".equals(type)) return HEADER
                + "<selector " + ANDROID_NS + ">\n"
                + "    <item android:color=\"#000000\" />\n</selector>\n";
        if ("anim".equals(type)) return HEADER
                + "<alpha " + ANDROID_NS + "\n"
                + "    android:fromAlpha=\"1.0\" android:toAlpha=\"1.0\"\n"
                + "    android:duration=\"200\" />\n";
        if ("navigation".equals(type)) return HEADER
                + "<navigation " + ANDROID_NS + "\n"
                + "    android:id=\"@+id/nav_graph\">\n\n</navigation>\n";
        if ("xml".equals(type)) return HEADER + "<root " + ANDROID_NS + ">\n\n</root>\n";
        // An ordinary XML document outside known resource folders should be well formed.
        return HEADER + "<root>\n\n</root>\n";
    }
}
