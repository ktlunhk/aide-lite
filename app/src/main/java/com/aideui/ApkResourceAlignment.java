package com.aideui;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Validates the uncompressed, 4-byte-aligned resource table required by Android. */
public final class ApkResourceAlignment {
    private ApkResourceAlignment() { }

    public static long resourceOffset(File apk) throws IOException {
        ZipFile zip = new ZipFile(apk);
        try {
            ZipEntry entry = zip.getEntry("resources.arsc");
            if (entry == null || entry.getMethod() != ZipEntry.STORED)
                throw new IOException("APK resources.arsc is missing or compressed");
        } finally { zip.close(); }

        RandomAccessFile input = new RandomAccessFile(apk, "r");
        try {
            long size = input.length();
            byte[] buffer = new byte[65536 + 256];
            long base = 0;
            while (base < size) {
                input.seek(base);
                int length = input.read(buffer, 0, (int)Math.min(buffer.length, size - base));
                if (length <= 0) break;
                for (int i = 0; i + 30 <= length; i++) {
                    if (buffer[i] != 'P' || buffer[i + 1] != 'K' ||
                        (buffer[i + 2] & 0xff) != 3 || (buffer[i + 3] & 0xff) != 4) continue;
                    int n = (buffer[i + 26] & 255) | ((buffer[i + 27] & 255) << 8);
                    int e = (buffer[i + 28] & 255) | ((buffer[i + 29] & 255) << 8);
                    if (n <= 0 || n > 256 || i + 30 + n > length) continue;
                    String filename = new String(buffer, i + 30, n, StandardCharsets.UTF_8);
                    if (!"resources.arsc".equals(filename)) continue;
                    return base + i + 30L + n + e;
                }
                // Keep an overlap so the local header cannot fall between reads.
                base += Math.min(65536, length);
            }
            throw new IOException("APK resource table local header was not found");
        } finally { input.close(); }
    }

    public static void verify(File apk) throws IOException {
        long offset = resourceOffset(apk);
        if ((offset & 3) != 0)
            throw new IOException("APK resources.arsc is not 4-byte aligned (offset " + offset + ")");
    }
}
