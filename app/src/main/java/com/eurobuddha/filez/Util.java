package com.eurobuddha.filez;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class Util {

    public static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.ENGLISH, "%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.ENGLISH, "%.1f MB", mb);
        return String.format(Locale.ENGLISH, "%.2f GB", mb / 1024.0);
    }

    public static String formatDate(long ms) {
        if (ms <= 0) return "";
        return new SimpleDateFormat("dd MMM yyyy HH:mm", Locale.ENGLISH).format(new Date(ms));
    }

    /** File extension in caps, like the web Filez getExtension utility. */
    public static String getExtension(String name) {
        if (name == null) return "";
        int idx = name.lastIndexOf('.');
        if (idx < 0 || idx == name.length() - 1) return "FILE";
        return name.substring(idx + 1).toUpperCase(Locale.ENGLISH);
    }

    /** Keep node-side filenames tame: no separators or control chars from user input. */
    public static String safeName(String name) {
        if (name == null) return "";
        return name.replaceAll("[/\\\\\\p{Cntrl}]", "_").trim();
    }

    public static String joinPath(String dir, String name) {
        if (dir == null || dir.isEmpty() || dir.equals("/")) return "/" + name;
        return dir + "/" + name;
    }

    public static String parentOf(String path) {
        if (path == null || path.isEmpty() || path.equals("/")) return null;
        int idx = path.lastIndexOf('/');
        if (idx <= 0) return "/";
        return path.substring(0, idx);
    }
}
