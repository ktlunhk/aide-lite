package com.aideui;

/** Pure sizing rules for the editor / file-browser divider, independent of Android UI. */
public final class PaneSplit {
    private PaneSplit() { }

    /** A downward drag collapses the file panel when it reaches the bottom edge. */
    public static boolean shouldCollapse(float editorHeight, int availableHeight, int thresholdPx) {
        return availableHeight > 0 && editorHeight >= availableHeight - Math.max(0, thresholdPx);
    }

    /** Clamp a split to keep a usable amount of each pane on ordinary drags. */
    public static float fraction(float requested, int available, int minEditor, int minBrowser) {
        if (available <= 0) return 0.5f;
        float editorMin = Math.min(Math.max(0, minEditor), available * 0.45f);
        float browserMin = Math.min(Math.max(0, minBrowser), available * 0.45f);
        float clamped = Math.max(editorMin, Math.min(available - browserMin, requested));
        return clamped / available;
    }
}
