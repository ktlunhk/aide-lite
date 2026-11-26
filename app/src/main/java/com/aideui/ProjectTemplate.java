package com.aideui;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Extracts an editable Android Java starter project bundled inside app assets. */
public final class ProjectTemplate {
    private static final String ASSET = "android_java_project_template.zip";
    private ProjectTemplate() {}

    public static File createAndroidKotlin(Context context, File base, String name,
                                           String pkg, boolean sample) throws IOException {
        if (context == null) throw new IOException("No Android context available");
        InputStream in = context.getAssets().open("android_kotlin_project_template.zip");
        try { return createFromZip(in, base, name, pkg, sample, true); }
        finally { in.close(); }
    }

    // Versions follow the Android Code Studio Compose template (known-good set). The Compose BOM supplies
    // every androidx.compose.* version, which is why the module declares those artifacts without versions.
    static final String COMPOSE_BOM = "2025.10.01";
    static final String ACTIVITY_COMPOSE = "1.11.0";
    static final String CORE_KTX = "1.17.0";
    static final String LIFECYCLE_RUNTIME_KTX = "2.9.2";
    static final String ANDROID_GRADLE_PLUGIN = "8.13.0";

    /**
     * Creates a Jetpack Compose (Material 3) starter project. The Kotlin and Compose compiler plugin
     * versions equal the installed on-device compiler, because the compose plugin must match it exactly.
     */
    public static File createAndroidCompose(Context context, File base, String name, String pkg) throws IOException {
        if (context == null) throw new IOException("No Android context available");
        return createCompose(base, name, pkg, KotlinPlugins.kotlinVersion(context));
    }

    /** Package-private for self-tests. */
    static File createCompose(File base, String name, String pkg, String kotlinVersion) throws IOException {
        if (!validProjectName(name)) throw new IOException("Project name must start with a letter and contain only letters, numbers, _ or -");
        if (!validPackageName(pkg)) throw new IOException("Invalid Java package name (e.g. com.myapp.demo)");
        if (kotlinVersion == null || !kotlinVersion.matches("[0-9A-Za-z.\\-]{1,40}")) throw new IOException("Invalid Kotlin version");
        if (base == null || !base.isDirectory() || !base.canWrite()) throw new IOException("Choose a writable destination folder");
        File root = new File(base, name);
        if (root.exists() || !root.mkdir()) throw new IOException("Project folder already exists or cannot be created: " + name);
        try {
            String theme = composeThemeName(name);
            String pkgPath = "app/src/main/java/" + pkg.replace('.', '/');
            writeText(root, "settings.gradle",
                "pluginManagement {\n    repositories { google(); mavenCentral(); gradlePluginPortal() }\n}\n"
                + "rootProject.name = '" + name + "'\ninclude ':app'\n");
            writeText(root, "build.gradle",
                "plugins {\n"
                + "    id 'com.android.application' version '" + ANDROID_GRADLE_PLUGIN + "' apply false\n"
                + "    id 'org.jetbrains.kotlin.android' version '" + kotlinVersion + "' apply false\n"
                + "    id 'org.jetbrains.kotlin.plugin.compose' version '" + kotlinVersion + "' apply false\n"
                + "}\nallprojects { repositories { google(); mavenCentral() } }\n");
            writeText(root, "gradle.properties",
                "org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8\nandroid.useAndroidX=true\nkotlin.code.style=official\n");
            writeText(root, "app/build.gradle",
                "plugins {\n"
                + "    id 'com.android.application'\n"
                + "    id 'org.jetbrains.kotlin.android'\n"
                + "    id 'org.jetbrains.kotlin.plugin.compose'\n"
                + "}\n\n"
                + "android {\n"
                + "    namespace '" + pkg + "'\n"
                + "    compileSdk 36\n"
                + "    defaultConfig {\n"
                + "        applicationId '" + pkg + "'\n"
                + "        minSdk 24\n"
                + "        targetSdk 36\n"
                + "        versionCode 1\n"
                + "        versionName '1.0'\n"
                + "    }\n"
                + "    compileOptions {\n"
                + "        sourceCompatibility JavaVersion.VERSION_11\n"
                + "        targetCompatibility JavaVersion.VERSION_11\n"
                + "    }\n"
                + "    buildFeatures {\n"
                + "        compose true\n"
                + "    }\n"
                + "}\n\n"
                + "dependencies {\n"
                + "    implementation platform('androidx.compose:compose-bom:" + COMPOSE_BOM + "')\n"
                + "    implementation 'androidx.core:core-ktx:" + CORE_KTX + "'\n"
                + "    implementation 'androidx.lifecycle:lifecycle-runtime-ktx:" + LIFECYCLE_RUNTIME_KTX + "'\n"
                + "    implementation 'androidx.activity:activity-compose:" + ACTIVITY_COMPOSE + "'\n"
                + "    implementation 'androidx.compose.ui:ui'\n"
                + "    implementation 'androidx.compose.ui:ui-graphics'\n"
                + "    implementation 'androidx.compose.ui:ui-tooling-preview'\n"
                + "    implementation 'androidx.compose.material3:material3'\n"
                + "}\n");
            writeText(root, "app/src/main/AndroidManifest.xml",
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                + "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" package=\"" + pkg + "\">\n"
                + "    <application android:label=\"@string/app_name\" android:theme=\"@android:style/Theme.Material.Light.NoActionBar\">\n"
                + "        <activity android:name=\".MainActivity\" android:exported=\"true\">\n"
                + "            <intent-filter>\n"
                + "                <action android:name=\"android.intent.action.MAIN\"/>\n"
                + "                <category android:name=\"android.intent.category.LAUNCHER\"/>\n"
                + "            </intent-filter>\n"
                + "        </activity>\n"
                + "    </application>\n"
                + "</manifest>\n");
            writeText(root, "app/src/main/res/values/strings.xml",
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n    <string name=\"app_name\">"
                + xml(name) + "</string>\n</resources>\n");
            writeText(root, pkgPath + "/MainActivity.kt",
                "package " + pkg + "\n\n"
                + "import android.os.Bundle\n"
                + "import androidx.activity.ComponentActivity\n"
                + "import androidx.activity.compose.setContent\n"
                + "import androidx.activity.enableEdgeToEdge\n"
                + "import androidx.compose.foundation.layout.Arrangement\n"
                + "import androidx.compose.foundation.layout.Row\n"
                + "import androidx.compose.foundation.layout.fillMaxSize\n"
                + "import androidx.compose.foundation.layout.padding\n"
                + "import androidx.compose.material3.Scaffold\n"
                + "import androidx.compose.material3.Text\n"
                + "import androidx.compose.runtime.Composable\n"
                + "import androidx.compose.ui.Alignment\n"
                + "import androidx.compose.ui.Modifier\n"
                + "import androidx.compose.ui.tooling.preview.Preview\n"
                + "import " + pkg + ".ui.theme." + theme + "\n\n"
                + "class MainActivity : ComponentActivity() {\n"
                + "    override fun onCreate(savedInstanceState: Bundle?) {\n"
                + "        super.onCreate(savedInstanceState)\n"
                + "        enableEdgeToEdge()\n"
                + "        setContent {\n"
                + "            " + theme + " {\n"
                + "                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->\n"
                + "                    Greeting(\n"
                + "                        name = \"" + kotlinString(name) + "\",\n"
                + "                        modifier = Modifier.padding(innerPadding)\n"
                + "                    )\n"
                + "                }\n"
                + "            }\n"
                + "        }\n"
                + "    }\n"
                + "}\n\n"
                + "@Composable\n"
                + "fun Greeting(name: String, modifier: Modifier = Modifier) {\n"
                + "    Row(\n"
                + "        modifier = modifier.fillMaxSize(),\n"
                + "        horizontalArrangement = Arrangement.Center,\n"
                + "        verticalAlignment = Alignment.CenterVertically\n"
                + "    ) {\n"
                + "        Text(text = \"Hello $name!\")\n"
                + "    }\n"
                + "}\n\n"
                + "@Preview(showBackground = true)\n"
                + "@Composable\n"
                + "fun GreetingPreview() {\n"
                + "    " + theme + " {\n"
                + "        Greeting(\"Android\")\n"
                + "    }\n"
                + "}\n");
            writeText(root, pkgPath + "/ui/theme/Color.kt",
                "package " + pkg + ".ui.theme\n\n"
                + "import androidx.compose.ui.graphics.Color\n\n"
                + "val Purple80 = Color(0xFFD0BCFF)\n"
                + "val PurpleGrey80 = Color(0xFFCCC2DC)\n"
                + "val Pink80 = Color(0xFFEFB8C8)\n\n"
                + "val Purple40 = Color(0xFF6650A4)\n"
                + "val PurpleGrey40 = Color(0xFF625B71)\n"
                + "val Pink40 = Color(0xFF7D5260)\n");
            writeText(root, pkgPath + "/ui/theme/Type.kt",
                "package " + pkg + ".ui.theme\n\n"
                + "import androidx.compose.material3.Typography\n"
                + "import androidx.compose.ui.text.TextStyle\n"
                + "import androidx.compose.ui.text.font.FontFamily\n"
                + "import androidx.compose.ui.text.font.FontWeight\n"
                + "import androidx.compose.ui.unit.sp\n\n"
                + "val Typography = Typography(\n"
                + "    bodyLarge = TextStyle(\n"
                + "        fontFamily = FontFamily.Default,\n"
                + "        fontWeight = FontWeight.Normal,\n"
                + "        fontSize = 16.sp,\n"
                + "        lineHeight = 24.sp,\n"
                + "        letterSpacing = 0.5.sp\n"
                + "    )\n"
                + ")\n");
            writeText(root, pkgPath + "/ui/theme/Theme.kt",
                "package " + pkg + ".ui.theme\n\n"
                + "import android.os.Build\n"
                + "import androidx.compose.foundation.isSystemInDarkTheme\n"
                + "import androidx.compose.material3.MaterialTheme\n"
                + "import androidx.compose.material3.darkColorScheme\n"
                + "import androidx.compose.material3.dynamicDarkColorScheme\n"
                + "import androidx.compose.material3.dynamicLightColorScheme\n"
                + "import androidx.compose.material3.lightColorScheme\n"
                + "import androidx.compose.runtime.Composable\n"
                + "import androidx.compose.ui.platform.LocalContext\n\n"
                + "private val DarkColorScheme = darkColorScheme(\n"
                + "    primary = Purple80,\n    secondary = PurpleGrey80,\n    tertiary = Pink80\n)\n\n"
                + "private val LightColorScheme = lightColorScheme(\n"
                + "    primary = Purple40,\n    secondary = PurpleGrey40,\n    tertiary = Pink40\n)\n\n"
                + "@Composable\n"
                + "fun " + theme + "(\n"
                + "    darkTheme: Boolean = isSystemInDarkTheme(),\n"
                + "    dynamicColor: Boolean = true,\n"
                + "    content: @Composable () -> Unit\n"
                + ") {\n"
                + "    val colorScheme = when {\n"
                + "        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {\n"
                + "            val context = LocalContext.current\n"
                + "            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)\n"
                + "        }\n"
                + "        darkTheme -> DarkColorScheme\n"
                + "        else -> LightColorScheme\n"
                + "    }\n\n"
                + "    MaterialTheme(\n"
                + "        colorScheme = colorScheme,\n"
                + "        typography = Typography,\n"
                + "        content = content\n"
                + "    )\n"
                + "}\n");
            return root;
        } catch (IOException e) {
            deleteTree(root);
            throw e;
        } catch (RuntimeException e) {
            deleteTree(root);
            throw e;
        }
    }

    /** "My-App_2" -> "MyApp2Theme": a valid Kotlin identifier for the generated theme composable. */
    static String composeThemeName(String projectName) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < projectName.length(); i++) {
            char c = projectName.charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) b.append(c);
        }
        if (b.length() == 0) b.append("App");
        b.setCharAt(0, Character.toUpperCase(b.charAt(0)));
        return b + "Theme";
    }

    private static String kotlinString(String text) {
        // '$' would start a Kotlin string template.
        return javaString(text).replace("$", "\\$");
    }

    private static void writeText(File root, String relative, String content) throws IOException {
        File dest = new File(root, relative);
        File parent = dest.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create " + parent);
        FileOutputStream out = new FileOutputStream(dest);
        try { out.write(content.getBytes("UTF-8")); }
        finally { out.close(); }
    }

    public static boolean validProjectName(String name) {
        return name != null && name.matches("[A-Za-z][A-Za-z0-9_-]{0,49}");
    }

    public static boolean validPackageName(String pkg) {
        if (pkg == null || pkg.length() > 200 || !pkg.contains(".")) return false;
        String[] segments = pkg.split("\\.", -1);
        for (String segment : segments) {
            if (!segment.matches("[A-Za-z_][A-Za-z0-9_]*") || !JavaClassTemplate.validIdentifier(segment)) return false;
        }
        return true;
    }

    private static String xml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    private static String javaString(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    /** Opens the ZIP directly from the APK: no external storage permission is needed to read it. */
    public static File createAndroid(Context context, File base, String name,
                                     String pkg, boolean sample) throws IOException {
        if (context == null) throw new IOException("No Android context available");
        InputStream in = context.getAssets().open(ASSET);
        try { return createFromZip(in, base, name, pkg, sample, false); }
        finally { in.close(); }
    }

    /** Package-private for self-tests and for other project template sources. */
    static File createFromZip(InputStream input, File base, String name,
                              String pkg, boolean sample) throws IOException {
        return createFromZip(input, base, name, pkg, sample, false);
    }

    static File createFromZip(InputStream input, File base, String name,
                              String pkg, boolean sample, boolean kotlin) throws IOException {
        if (!validProjectName(name)) throw new IOException("Project name must start with a letter and contain only letters, numbers, _ or -");
        if (!validPackageName(pkg)) throw new IOException("Invalid Java package name (e.g. com.myapp.demo)");
        if (base == null || !base.isDirectory() || !base.canWrite()) throw new IOException("Choose a writable destination folder");
        File root = new File(base, name);
        if (root.exists() || !root.mkdir()) throw new IOException("Project folder already exists or cannot be created: " + name);
        try {
            ZipInputStream zip = new ZipInputStream(input);
            int count = 0;
            long total = 0;
            boolean manifest = false, main = false, layout = false;
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++count > 200) throw new IOException("Project template has too many files");
                String rel = entry.getName().replace("__PACKAGE_PATH__", pkg.replace('.', '/'));
                if (rel.startsWith("/") || rel.indexOf('\\') >= 0 || rel.contains("../") || rel.equals(".."))
                    throw new IOException("Unsafe project template path: " + rel);
                File dest = new File(root, rel);
                if (!dest.getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator))
                    throw new IOException("Template file escapes project folder: " + rel);
                if (entry.isDirectory()) {
                    if (!dest.isDirectory() && !dest.mkdirs()) throw new IOException("Cannot create " + dest);
                } else {
                    File parent = dest.getParentFile();
                    if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create " + parent);
                    if (dest.exists()) throw new IOException("Duplicate template file: " + rel);
                    ByteArrayOutputStream content = new ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    int n;
                    while ((n = zip.read(buffer)) != -1) {
                        total += n;
                        if (content.size() + n > 1024 * 1024 || total > 10 * 1024 * 1024)
                            throw new IOException("Project template ZIP is too large");
                        content.write(buffer, 0, n);
                    }
                    String src = new String(content.toByteArray(), "UTF-8");
                    src = src.replace("__PROJECT_NAME_XML__", xml(name))
                            .replace("__PROJECT_NAME__", name)
                            .replace("__PACKAGE_NAME__", pkg)
                            .replace("__SAMPLE_IMPORT__", sample ? (kotlin ? "import android.widget.TextView\n" : "import android.widget.TextView;\n") : "")
                            .replace("__SAMPLE_CODE__", sample
                                    ? (kotlin ? "        val message = findViewById<TextView>(R.id.message)\n"
                                    + "        message.text = \"Hello from " + javaString(name) + "!\"\n"
                                    : "        TextView message = findViewById(R.id.message);\n"
                                    + "        message.setText(\"Hello from " + javaString(name) + "!\");\n") : "");
                    FileOutputStream out = new FileOutputStream(dest);
                    try { out.write(src.getBytes("UTF-8")); }
                    finally { out.close(); }
                    if (rel.equals("app/src/main/AndroidManifest.xml")) manifest = true;
                    if (rel.equals("app/src/main/java/" + pkg.replace('.', '/')
                            + (kotlin ? "/MainActivity.kt" : "/MainActivity.java"))) main = true;
                    if (rel.equals("app/src/main/res/layout/main.xml")) layout = true;
                }
                zip.closeEntry();
            }
            if (!manifest || !main || !layout || count < 7)
                throw new IOException("Template ZIP is missing required Android project files");
            return root;
        } catch (IOException e) {
            deleteTree(root);
            throw e;
        } catch (RuntimeException e) {
            deleteTree(root);
            throw e;
        }
    }

    private static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        file.delete();
    }
}
