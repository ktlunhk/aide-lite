package com.aideui;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.UUID;

/** Read-only, temporary content URIs for already-built APKs. Never signs or executes an APK. */
public final class ApkShareProvider extends ContentProvider {
    public static final String AUTHORITY = "com.aideui.apkshare";
    static final String PREFS = "shared_apks";

    public static Uri prepareShare(Context ctx, File apk) throws IOException {
        if (!validApk(apk)) throw new IOException("Not a recognized APK build output. Expected a Gradle build/outputs/apk or classic project bin folder, with AndroidManifest.xml and classes.dex inside the APK");
        String token = UUID.randomUUID().toString();
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(token, apk.getCanonicalPath()).apply();
        return new Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(token).build();
    }

    static boolean validApk(File apk) throws IOException {
        return ApkOutputValidator.isBuildOutput(apk, Environment.getExternalStorageDirectory());
    }

    File resolve(Uri uri) throws FileNotFoundException {
        if (uri == null || !"content".equals(uri.getScheme()) || !AUTHORITY.equals(uri.getAuthority()))
            throw new FileNotFoundException("Invalid APK URI");
        String token = uri.getLastPathSegment();
        if (token == null || !token.matches("[a-f0-9-]{36}")) throw new FileNotFoundException("Invalid share token");
        String stored = getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(token, null);
        if (stored == null) throw new FileNotFoundException("Share token expired");
        File f = new File(stored);
        try {
            if (!validApk(f)) throw new FileNotFoundException("APK is no longer available");
        } catch (IOException ex) { throw new FileNotFoundException(ex.toString()); }
        return f;
    }

    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return "application/vnd.android.package-archive"; }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Read only");
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public Cursor query(Uri uri, String[] columns, String selection, String[] args, String order) {
        try {
            File f = resolve(uri);
            String[] requested = columns == null ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : columns;
            MatrixCursor cursor = new MatrixCursor(requested);
            Object[] row = new Object[requested.length];
            for (int i = 0; i < requested.length; i++) {
                if (OpenableColumns.DISPLAY_NAME.equals(requested[i])) row[i] = f.getName();
                else if (OpenableColumns.SIZE.equals(requested[i])) row[i] = f.length();
            }
            cursor.addRow(row);
            return cursor;
        } catch (Exception ex) { return null; }
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
