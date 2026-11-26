package com.aideui;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.provider.Settings;
import android.content.ActivityNotFoundException;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.HorizontalScrollView;
import android.text.Editable;
import android.os.Handler;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.Spannable;
import android.text.TextWatcher;
import android.text.style.BackgroundColorSpan;
import android.view.inputmethod.InputMethodManager;

import android.widget.PopupWindow;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.widget.ImageButton;
import android.widget.FrameLayout;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

public class MainActivity extends Activity {

    static final int REQ_STORAGE = 1, REQ_PICK = 2, REQ_ALL_FILES = 3, REQ_KOTLIN_COMPILER = 84;
    boolean storagePromptShown = false;
    static final int UP = 0, NEW_PROJECT = 1, CLONE = 2, DIR = 3, FILE = 4,
            ERROR_ITEM = 5, SEARCH_ITEM = 6, PANEL_MESSAGE = 7, OPEN_TAB = 8, ERROR_FILE_HEADER = 9, NEW_XML = 10;
    static final int MODE_FILES = 0, MODE_ERRORS = 1, MODE_SEARCH = 2,
            MODE_DEBUGGER = 3, MODE_LOGCAT = 4, MODE_FILE_LIST = 5, MODE_TERMINAL = 6;

    static class Entry {
        int type; String label; File file; int line; boolean warning;
        Entry(int t, String l, File f) { this(t, l, f, 0); }
        Entry(int t, String l, File f, int ln) { type = t; label = l; file = f; line = ln; }
        Entry(int t, String l, File f, int ln, boolean w) { this(t, l, f, ln); warning = w; }
    }

    File currentDir;
    // The active project is independent of the currently displayed folder and open tabs.
    File activeProject;
    int bottomMode = MODE_FILES;
    final List<Entry> entries = new ArrayList<Entry>();
    final List<String> searchResultLabels = new ArrayList<String>();
    final List<File> searchResultFiles = new ArrayList<File>();
    final List<Integer> searchResultLines = new ArrayList<Integer>();
    String lastSearchQuery = "";
    ArrayAdapter<Entry> adapter;
    TextView pathText, emptyText, titleText, lineNumbers, errorCount, fileNameText, filePathText;
    HorizontalScrollView breadcrumbScroll;
    LinearLayout breadcrumbItems;
    ImageView bottomHeaderIcon;
    ScaleGestureDetector editorScaleDetector;
    boolean editorPinching = false;
    float editorTextSp = 14f;
    static final float MIN_EDITOR_SP = 9f, MAX_EDITOR_SP = 30f;
    ImageButton btnUndo, btnRedo;
    TextView btnToggleBottom;
    CodeView editorText;
    File currentFile;
    ListView fileList;
    LinearLayout tabsBar, bottomPanel, symbolBar;
    final List<File> openTabs = new ArrayList<File>();
    boolean bottomCollapsed = false;
    // Shares of remaining vertical space (not raw pixel heights) survive keyboard resizing.
    float editorPaneFraction = 0.5f;
    String lastTabsSignature = "";
    boolean blockGuidesEnabled = true;
    PopupWindow completionPopup;
    ListView completionList;
    boolean completionIndexRunning = false;
    String indexedModule = null;
    int completionIndexedClasses = 0;
    ArrayAdapter<String> completionAdapter;
    final List<CodeActions.Completion> activeCompletions = new ArrayList<CodeActions.Completion>();
    int lastChangeStart = -1, lastChangeBefore = 0, lastChangeCount = 0;
    View findBar; EditText findInput; TextView findCount, findCase;
    final List<int[]> findMatches = new ArrayList<int[]>();
    final List<Object> findSpans = new ArrayList<Object>();
    Object curSpan, lineSpan;
    int findIndex = -1;
    boolean caseSensitive = false;
    String compilerStatus = "";
    int compilerSequence = 0;
    View errorBox, fileHeader, fileHeaderLine;
    List<JavaAnalyzer.Err> currentErrors = new ArrayList<JavaAnalyzer.Err>();
    ScrollView editorScroll;
    final Handler editorHandler = new Handler();
    boolean editorLoading = false, editorDirty = false;
    String editorSaved = "";
    Runnable pendingHighlight;
    Runnable pendingLiveCompile;
    static final long LIVE_COMPILE_DELAY_MS = 750;
    /** Kotlin checks start the real compiler, so they wait for a pause in typing. */
    static final long KOTLIN_CHECK_DELAY_MS = 4000;
    boolean kotlinCheckRunning, kotlinCheckAgain;
    boolean applyingFormatEdit = false;
    boolean librarySyncing = false;
    final java.util.Set<String> libraryOffered = new java.util.HashSet<String>();
    final java.util.Map<String, String> autoLibraryAttempted = new java.util.HashMap<String, String>();
    TextView libraryStatusText;
    boolean libraryPolling = false;
    boolean libraryCheckRunning = false;
    static final long LIBRARY_POLL_MS = 3000;
    final Runnable autoLibraryPoll = new Runnable() {
        public void run() {
            if (!libraryPolling) return;
            checkAutomaticLibrarySync();
            editorHandler.postDelayed(this, LIBRARY_POLL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        StartupLog.mark("MainActivity.onCreate begin");
        CrashReporter.install(getApplicationContext());
        StartupLog.mark("MainActivity.setContentView begin");
        setContentView(R.layout.activity_main);
        StartupLog.mark("MainActivity.setContentView complete");
        pathText = (TextView) findViewById(R.id.pathText);
        breadcrumbScroll = (HorizontalScrollView) findViewById(R.id.breadcrumbScroll);
        breadcrumbItems = (LinearLayout) findViewById(R.id.breadcrumbItems);
        // Existing header icon is reused for all lower windows (no new layout ID).
        LinearLayout header = (LinearLayout) ((LinearLayout) findViewById(R.id.bottomPanel)).getChildAt(0);
        bottomHeaderIcon = (ImageView) header.getChildAt(0);
        emptyText = (TextView) findViewById(R.id.emptyText);
        editorText = (CodeView) findViewById(R.id.editorText);
        blockGuidesEnabled = getPreferences(MODE_PRIVATE).getBoolean("block_guides", true);
        editorTextSp = Math.max(MIN_EDITOR_SP, Math.min(MAX_EDITOR_SP,
                getPreferences(MODE_PRIVATE).getFloat("editor_zoom_sp", 14f)));
        editorScaleDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScaleBegin(ScaleGestureDetector detector) { return true; }
            @Override public boolean onScale(ScaleGestureDetector detector) {
                float factor = detector.getScaleFactor();
                if (Float.isNaN(factor) || Float.isInfinite(factor)) return false;
                float proposed = Math.max(MIN_EDITOR_SP, Math.min(MAX_EDITOR_SP, editorTextSp * factor));
                if (Math.abs(proposed - editorTextSp) > 0.02f) {
                    editorTextSp = proposed;
                    applyEditorZoom();
                }
                return true;
            }
            @Override public void onScaleEnd(ScaleGestureDetector detector) {
                getPreferences(MODE_PRIVATE).edit().putFloat("editor_zoom_sp", editorTextSp).apply();
            }
        });
        editorText.configureGuides(blockGuidesEnabled, false, 4);
        errorCount = (TextView) findViewById(R.id.errorCount);
        fileNameText = (TextView) findViewById(R.id.fileNameText);
        filePathText = (TextView) findViewById(R.id.filePathText);
        errorBox = findViewById(R.id.errorBox);
        fileHeader = findViewById(R.id.fileHeader);
        fileHeaderLine = findViewById(R.id.fileHeaderLine);
        errorBox.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { setBottomMode(MODE_ERRORS); }
        });
        titleText = (TextView) findViewById(R.id.titleText);
        libraryStatusText = (TextView) findViewById(R.id.libraryStatusText);
        libraryStatusText.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showLibraryStatus(); }
        });
        lineNumbers = (TextView) findViewById(R.id.lineNumbers);
        applyEditorZoom();
        editorScroll = (ScrollView) findViewById(R.id.editorScroll);
        fileList = (ListView) findViewById(R.id.fileList);
        setupTerminalPanel();
        tabsBar = (LinearLayout) findViewById(R.id.tabsBar);
        bottomPanel = (LinearLayout) findViewById(R.id.bottomPanel);
        symbolBar = (LinearLayout) findViewById(R.id.symbolBar);
        btnUndo = (ImageButton) findViewById(R.id.btnUndo);
        btnRedo = (ImageButton) findViewById(R.id.btnRedo);
        btnToggleBottom = (TextView) findViewById(R.id.btnToggleBottom);

        StartupLog.mark("MainActivity initialize folder");
        currentDir = new File(Environment.getExternalStorageDirectory(), "AppProjects");

        adapter = new ArrayAdapter<Entry>(this, 0, entries) {
            @Override
            public View getView(int pos, View convert, ViewGroup parent) {
                Entry e = getItem(pos);
                if (e.type == PANEL_MESSAGE) {
                    TextView msg = new TextView(MainActivity.this);
                    msg.setText(e.label);
                    msg.setTextSize(bottomMode == MODE_LOGCAT ? 12 : 14);
                    msg.setTextColor(Color.parseColor("#777777"));
                    if (bottomMode == MODE_LOGCAT) msg.setTypeface(Typeface.MONOSPACE);
                    msg.setPadding(dp(12), dp(6), dp(10), dp(6));
                    msg.setMinHeight(dp(bottomMode == MODE_DEBUGGER ? 44 : 36));
                    return msg;
                }
                LinearLayout row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(10), 0, dp(6), 0);
                row.setMinimumHeight(dp(e.type == ERROR_ITEM || e.type == SEARCH_ITEM ? 48 : 42));
                ImageView icon = new ImageView(MainActivity.this);
                int res;
                switch (e.type) {
                    case UP: res = R.drawable.ic_folder_open; break;
                    case NEW_PROJECT: res = R.drawable.ic_new_project; break;
                    case NEW_XML: res = R.drawable.ic_new_project; break;
                    case CLONE: res = R.drawable.ic_git; break;
                    case DIR: res = R.drawable.ic_folder; break;
                    case ERROR_ITEM: res = e.warning ? R.drawable.ic_warning : R.drawable.ic_error; break;
                    case ERROR_FILE_HEADER: res = R.drawable.ic_java; break;
                    default: res = (e.label.endsWith(".java") || e.label.endsWith(".kt")) ? R.drawable.ic_java : R.drawable.ic_file;
                }
                icon.setImageResource(res);
                row.addView(icon, new LinearLayout.LayoutParams(dp(28), dp(28)));
                TextView t = new TextView(MainActivity.this);
                t.setText(e.label);
                t.setTextSize(e.type == SEARCH_ITEM || e.type == ERROR_ITEM ? 13 : 15);
                if (e.type == ERROR_FILE_HEADER) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                t.setTextColor(Color.parseColor("#222222"));
                t.setPadding(dp(8), 0, 0, 0);
                if (e.type == SEARCH_ITEM || e.type == ERROR_ITEM) {
                    t.setMaxLines(3);
                    t.setPadding(dp(8), dp(4), dp(6), dp(4));
                }
                row.addView(t);
                return row;
            }
        };
        fileList.setAdapter(adapter);
        fileList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                onEntryClicked(entries.get(pos));
            }
        });
        fileList.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                Entry e = entries.get(position);
                if (bottomMode == MODE_FILES && (e.type == DIR || e.type == FILE)) {
                    showEntryPopup(view, e);
                    return true;
                }
                if (bottomMode == MODE_FILE_LIST && e.type == OPEN_TAB && e.file != null) {
                    PopupMenu pm = new PopupMenu(MainActivity.this, view);
                    pm.getMenu().add(0, 1, 0, "Reload from Disk");
                    pm.getMenu().add(0, 2, 1, "Close File");
                    pm.getMenu().add(0, 3, 2, "Copy Path");
                    final File file = e.file;
                    pm.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
                        public boolean onMenuItemClick(android.view.MenuItem item) {
                            if (item.getItemId() == 1) reloadOpenFileFromDisk(file);
                            else if (item.getItemId() == 2) closeOpenTab(file);
                            else if (item.getItemId() == 3) copyPath(file);
                            return true;
                        }
                    });
                    pm.show();
                    return true;
                }
                return false;
            }
        });

        btnUndo.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { if (editorText.undo()) afterProgrammaticEdit(); }
        });
        btnRedo.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { if (editorText.redo()) afterProgrammaticEdit(); }
        });
        btnToggleBottom.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { toggleBottomPanel(); }
        });
        StartupLog.mark("MainActivity initialize editor widgets");
        initSymbolBar();
        initCompletionPopup();
        refreshCompletionCatalog(false);

        findViewById(R.id.btnPlay).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { onAction("run"); }
        });
        findViewById(R.id.btnMainMenu).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showMainMenu(v); }
        });
        findViewById(R.id.btnFolderMenu).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showFolderMenu(v); }
        });

        initFindBar();
        initCodeEditor();
        initLowerHeaderSwipe();
        initSplitter();
        updateUndoRedoButtons();
        StartupLog.mark("MainActivity widgets ready; checking storage access");
        if (hasProjectStorageAccess()) {
            StartupLog.mark("Storage granted; loading browser");
            prepareProjectDirectory();
            refresh();
        } else {
            StartupLog.mark("Storage not granted; showing permission dialog");
            showStorageAccessRequired();
        }
        StartupLog.mark("MainActivity.onCreate complete");
        CrashReporter.showPreviousCrash(this);
    }

    /** Pinch on the editor, including blank and scrolled areas of the code pane.
     *  Single-finger scrolling, text selection, and lower-pane dragging are unchanged. */
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_POINTER_DOWN && event.getPointerCount() >= 2) {
            View frame = findViewById(R.id.editorFrame);
            if (frame != null && frame.getVisibility() == View.VISIBLE && editorScroll != null
                    && editorScroll.getVisibility() == View.VISIBLE) {
                int[] pos = new int[2];
                frame.getLocationOnScreen(pos);
                float cx = (event.getX(0) + event.getX(1)) * 0.5f;
                float cy = (event.getY(0) + event.getY(1)) * 0.5f;
                int[] decor = new int[2];
                getWindow().getDecorView().getLocationOnScreen(decor);
                float screenX = cx + decor[0], screenY = cy + decor[1];
                if (screenX >= pos[0] && screenX <= pos[0] + frame.getWidth()
                        && screenY >= pos[1] && screenY <= pos[1] + frame.getHeight()) {
                    // End the preceding one-finger editor/scroll gesture before taking ownership.
                    MotionEvent cancel = MotionEvent.obtain(event);
                    cancel.setAction(MotionEvent.ACTION_CANCEL);
                    super.dispatchTouchEvent(cancel);
                    cancel.recycle();
                    editorPinching = true;
                }
            }
        }
        if (editorPinching) {
            editorScaleDetector.onTouchEvent(event);
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                editorPinching = false;
            }
            return true;
        }
        return super.dispatchTouchEvent(event);
    }

    void applyEditorZoom() {
        if (editorText == null || lineNumbers == null) return;
        editorText.setTextSize(TypedValue.COMPLEX_UNIT_SP, editorTextSp);
        lineNumbers.setTextSize(TypedValue.COMPLEX_UNIT_SP, editorTextSp);
    }

    @Override
    protected void onResume() {
        super.onResume();
        StartupLog.mark("MainActivity.onResume storage=" + hasProjectStorageAccess());
        if (fileList != null) {
            if (hasProjectStorageAccess()) {
                prepareProjectDirectory();
                refresh();
            } else if (!storagePromptShown) {
                // The permission prompt is also invoked during onCreate.
                showStorageAccessRequired();
            }
        }
        libraryPolling = hasProjectStorageAccess();
        editorHandler.removeCallbacks(autoLibraryPoll);
        editorHandler.post(autoLibraryPoll);
    }

    @Override
    protected void onPause() {
        if (completionPopup != null) completionPopup.dismiss();
        libraryPolling = false;
        editorHandler.removeCallbacks(autoLibraryPoll);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (buildSession != null && buildSession.running) {
            buildSession.cancel.set(true);
            if (buildSession.worker != null) buildSession.worker.interrupt();
        }
        if (buildDialog != null && buildDialog.isShowing()) buildDialog.dismiss();
        if (shell != null) shell.destroy();
        if (termSession != null) termSession.finishIfRunning();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code != REQ_STORAGE) return;
        if (hasProjectStorageAccess()) {
            prepareProjectDirectory();
            refresh();
            editorHandler.post(new Runnable() { public void run() { checkAutomaticLibrarySync(); } });
        } else {
            showStorageDeniedMessage();
        }
    }

    /** The source is compiled with android.jar API 28 for classic AIDE compatibility.
     *  Use reflection for the Android 11 isExternalStorageManager() API. */
    boolean hasProjectStorageAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                Object granted = Environment.class.getMethod("isExternalStorageManager").invoke(null);
                return Boolean.TRUE.equals(granted);
            } catch (Exception ignored) {
                return false;
            }
        }
        return Build.VERSION.SDK_INT < 23 ||
                checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    void prepareProjectDirectory() {
        File projects = new File(Environment.getExternalStorageDirectory(), "AppProjects");
        if (!projects.isDirectory() && !projects.mkdirs()) {
            Toast.makeText(this, "Cannot create AppProjects. Please check file access.", Toast.LENGTH_LONG).show();
        }
    }

    void showStorageAccessRequired() {
        StartupLog.mark("showStorageAccessRequired shown=" + storagePromptShown + " finishing=" + isFinishing());
        if (storagePromptShown || isFinishing()) return;
        storagePromptShown = true;
        if (Build.VERSION.SDK_INT >= 30) {
            new android.app.AlertDialog.Builder(this)
                .setTitle("File access required")
                .setMessage("AideUI needs All files access to read and save Android project source files in AppProjects. "
                    + "Tap Open Settings and enable Allow access to manage all files for AideUI. "
                    + "Return to the app afterward.")
                .setPositiveButton("Open Settings", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int which) {
                        openAllFilesPermissionSettings();
                    }
                })
                .setNegativeButton("Not now", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int which) { showStorageDeniedMessage(); }
                }).show();
        } else if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
        }
    }

    void openAllFilesPermissionSettings() {
        StartupLog.mark("Opening Android all-files permission settings");
        // Android 11+ Settings actions are expressed as strings so compileSdk 28 works.
        try {
            Intent intent = new Intent("android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION");
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, REQ_ALL_FILES);
        } catch (ActivityNotFoundException ex) {
            try {
                startActivityForResult(new Intent("android.settings.MANAGE_ALL_FILES_ACCESS_PERMISSION"), REQ_ALL_FILES);
            } catch (Exception second) {
                Toast.makeText(this, "Open Android Settings > Apps > AideUI > Special access > All files access.", Toast.LENGTH_LONG).show();
            }
        } catch (SecurityException denied) {
            showStorageDeniedMessage();
        }
    }

    void showStorageDeniedMessage() {
        Toast.makeText(this, "File access is disabled. Enable All files access in Settings and reopen AideUI.", Toast.LENGTH_LONG).show();
    }

    @Override
    public void onBackPressed() {
        if (completionPopup != null && completionPopup.isShowing()) { completionPopup.dismiss(); return; }
        if (!editorDirty || currentFile == null) { super.onBackPressed(); return; }
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
            .setTitle("Unsaved code")
            .setMessage("Save before exiting?")
            .setPositiveButton("Save and exit", new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int w) {
                    if (saveEditor(false)) MainActivity.super.onBackPressed();
                }
            })
            .setNeutralButton("Discard", new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int w) {
                    MainActivity.super.onBackPressed();
                }
            }).setNegativeButton("Cancel", null).show();
    }

    // ---------- file browser / lower panels ----------

    /** Refreshes whichever lower window is currently selected from View…. */
    void refresh() {
        if (terminalPanel != null) {
            boolean terminal = bottomMode == MODE_TERMINAL;
            terminalPanel.setVisibility(terminal ? View.VISIBLE : View.GONE);
            fileList.setVisibility(terminal ? View.GONE : View.VISIBLE);
        }
        switch (bottomMode) {
            case MODE_TERMINAL: refreshTerminalPanel(); break;
            case MODE_ERRORS: refreshErrorsPanel(); break;
            case MODE_SEARCH: refreshSearchPanel(); break;
            case MODE_DEBUGGER: refreshDebuggerPanel(); break;
            case MODE_LOGCAT: refreshLogcatPanel(); break;
            case MODE_FILE_LIST: refreshOpenTabsPanel(); break;
            default: refreshFileBrowser(); break;
        }
    }

    /** The Files panel contains only entries returned by Android's filesystem APIs. */
    void setLowerHeader(String label, int iconId) {
        if (pathText != null) {
            pathText.setText(label);
            pathText.setVisibility(bottomMode == MODE_FILES ? View.GONE : View.VISIBLE);
        }
        if (breadcrumbScroll != null) {
            breadcrumbScroll.setVisibility(bottomMode == MODE_FILES ? View.VISIBLE : View.GONE);
        }
        if (bottomMode == MODE_FILES) updateBreadcrumbs();
        if (bottomHeaderIcon != null) bottomHeaderIcon.setImageResource(iconId);
        ImageButton menu = (ImageButton) findViewById(R.id.btnFolderMenu);
        if (menu != null) menu.setVisibility(bottomMode == MODE_FILES ? View.VISIBLE : View.GONE);
    }

    /** Real filesystem ancestors, clickable without changing the active project. */
    void updateBreadcrumbs() {
        if (breadcrumbItems == null || currentDir == null) return;
        breadcrumbItems.removeAllViews();
        ArrayList<File> ancestors = new ArrayList<File>();
        for (File part = currentDir.getAbsoluteFile(); part != null; part = part.getParentFile()) {
            ancestors.add(0, part);
        }
        for (int i = 0; i < ancestors.size(); i++) {
            final File part = ancestors.get(i);
            boolean last = i == ancestors.size() - 1;
            if (i > 0) {
                TextView sep = new TextView(this);
                sep.setText(" › ");
                sep.setTextSize(15);
                sep.setTextColor(Color.GRAY);
                breadcrumbItems.addView(sep);
            }
            TextView segment = new TextView(this);
            String label = part.getName();
            segment.setText(label.length() == 0 ? "/" : label);
            segment.setTextSize(15);
            segment.setSingleLine(true);
            segment.setGravity(Gravity.CENTER_VERTICAL);
            segment.setTextColor(last ? Color.parseColor("#222222") : Color.parseColor("#007FB4"));
            segment.setPadding(dp(6), 0, dp(6), 0);
            segment.setMinimumHeight(dp(44));
            segment.setBackgroundResource(android.R.drawable.list_selector_background);
            segment.setContentDescription("Go to " + part.getAbsolutePath());
            segment.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { navigateToDirectory(part); }
            });
            breadcrumbItems.addView(segment);
        }
        // Reveal the current folder by default. The user may scroll left for ancestors.
        if (breadcrumbScroll != null) breadcrumbScroll.post(new Runnable() {
            public void run() { breadcrumbScroll.fullScroll(View.FOCUS_RIGHT); }
        });
    }

    void refreshFileBrowser() {
        entries.clear();
        if (!hasProjectStorageAccess()) {
            entries.add(new Entry(PANEL_MESSAGE, "File access required. Reopen AideUI to grant All files access.", null));
            setLowerHeader("Files — permission required", R.drawable.ic_folder_open);
            adapter.notifyDataSetChanged();
            return;
        }
        if (currentDir == null) currentDir = new File(Environment.getExternalStorageDirectory(), "AppProjects");
        setLowerHeader(currentDir.getAbsolutePath(), R.drawable.ic_folder_open);
        File parent = currentDir.getParentFile();
        if (parent != null) entries.add(new Entry(UP, "..", parent));
        // Project creation and Git clone belong to the outer browser, not inside a project.
        if (owningProject(currentDir) == null) {
            entries.add(new Entry(NEW_PROJECT, "Create New Project…", null));
            entries.add(new Entry(CLONE, "Clone Git repository…", null));
        }

        if (XmlFileTemplate.resourceFolderType(currentDir) != null) {
            entries.add(new Entry(NEW_XML, "Add New XML File…", null));
        }
        File[] files = currentDir.listFiles();
        if (files == null) {
            if (currentDir.exists() && currentDir.isDirectory()) {
                Toast.makeText(this, "Android restricts access to " + currentDir.getAbsolutePath()
                        + ". Use the folder menu to return to AppProjects.", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "Cannot open " + currentDir.getAbsolutePath(), Toast.LENGTH_LONG).show();
            }
        } else {
            Arrays.sort(files, new Comparator<File>() {
                public int compare(File a, File b) {
                    if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                    return a.getName().compareToIgnoreCase(b.getName());
                }
            });
            for (File f : files) {
                if (f != null) entries.add(new Entry(f.isDirectory() ? DIR : FILE, f.getName(), f));
            }
        }
        adapter.notifyDataSetChanged();
    }

    void refreshErrorsPanel() {
        entries.clear();
        int errors = countErrors();
        int warnings = countWarnings();
        String summary = errors + (errors == 1 ? " error" : " errors")
                + " · " + warnings + (warnings == 1 ? " warning" : " warnings");
        // AIDE-style green check for a clean compilation, red X for errors,
        // and yellow exclamation icon for a warning-only compilation.
        int icon = errors > 0 ? R.drawable.ic_error
                : warnings > 0 ? R.drawable.ic_warning : R.drawable.ic_success;
        setLowerHeader(summary, icon);
        if (!currentErrors.isEmpty()) {
            if (currentFile != null) entries.add(new Entry(ERROR_FILE_HEADER, currentFile.getName(), currentFile));
            for (JavaAnalyzer.Err e : currentErrors) {
                String prefix = e.warning ? "Warning, line " : "Error, line ";
                entries.add(new Entry(ERROR_ITEM, prefix + e.line + ": " + e.msg, currentFile, e.line, e.warning));
            }
        }
        adapter.notifyDataSetChanged();
    }

    void refreshSearchPanel() {
        entries.clear();
        setLowerHeader("Search Results", android.R.drawable.ic_menu_search);
        for (int i = 0; i < searchResultLabels.size(); i++) {
            File f = i < searchResultFiles.size() ? searchResultFiles.get(i) : null;
            int line = i < searchResultLines.size() ? searchResultLines.get(i).intValue() : 0;
            entries.add(new Entry(SEARCH_ITEM, searchResultLabels.get(i), f, line));
        }
        adapter.notifyDataSetChanged();
    }

    // ---------- Terminal panel (Android's own shell; see ShellSession) ----------

    LinearLayout terminalPanel;
    ScrollView terminalScroll;
    TextView terminalOutput;
    EditText terminalInput;
    ShellSession shell;
    String shellDirectory = "";
    final java.util.ArrayList<String> shellHistory = new java.util.ArrayList<String>();
    int shellHistoryPosition = 0;
    static final int TERMINAL_MAX_CHARS = 120000, TERMINAL_KEEP_CHARS = 80000;

    void setupTerminalPanel() {
        terminalPanel = (LinearLayout) findViewById(R.id.terminalPanel);
        if (terminalPanel == null) return;
        terminalScroll = (ScrollView) findViewById(R.id.terminalScroll);
        terminalOutput = (TextView) findViewById(R.id.terminalOutput);
        terminalInput = (EditText) findViewById(R.id.terminalInput);
        findViewById(R.id.terminalRun).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { runTerminalCommand(); }
        });
        findViewById(R.id.terminalHistoryUp).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { recallTerminalHistory(-1); }
        });
        findViewById(R.id.terminalHistoryDown).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { recallTerminalHistory(1); }
        });
        findViewById(R.id.terminalMore).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showTerminalMenu(); }
        });
        terminalInput.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            public boolean onEditorAction(TextView view, int actionId, android.view.KeyEvent event) {
                runTerminalCommand();
                return true;
            }
        });
    }

    void refreshTerminalPanel() {
        entries.clear();
        adapter.notifyDataSetChanged();
        boolean full = fullTerminalAvailable();
        View host = findViewById(R.id.terminalHost);
        View keys = findViewById(R.id.terminalKeys);
        View simple = findViewById(R.id.terminalSimpleInput);
        if (host != null) host.setVisibility(full ? View.VISIBLE : View.GONE);
        if (keys != null) keys.setVisibility(full ? View.VISIBLE : View.GONE);
        if (terminalScroll != null) terminalScroll.setVisibility(full ? View.GONE : View.VISIBLE);
        if (simple != null) simple.setVisibility(full ? View.GONE : View.VISIBLE);
        if (full) {
            setLowerHeader("Terminal" + (termTitle.length() == 0 ? "" : " · " + termTitle), android.R.drawable.ic_menu_edit);
            showFullTerminal();
            return;
        }
        String where = shellDirectory.length() == 0 ? "" : " · " + shellDirectory;
        setLowerHeader("Terminal" + where, android.R.drawable.ic_menu_edit);
        if (shell == null || !shell.isAlive()) {
            if (fullTerminalError != null && terminalOutput != null && terminalOutput.length() == 0)
                appendTerminal("[full terminal unavailable, using the simple console: " + fullTerminalError + "]\n");
            startShell();
        }
    }

    // ---------- Full terminal (Termux terminal-emulator + terminal-view, pty from libtermux.so) ----------

    com.termux.view.TerminalView termView;
    com.termux.terminal.TerminalSession termSession;
    boolean termCtrl, termAlt, termFinished;
    String termTitle = "";
    int termTextSizePx;
    android.widget.Button termCtrlButton, termAltButton;
    int fullTerminalState;            // 0 unknown, 1 available, 2 unavailable
    String fullTerminalError;

    boolean fullTerminalAvailable() {
        if (fullTerminalState == 0) {
            try {
                Class.forName("com.termux.terminal.JNI");   // loads libtermux.so
                fullTerminalState = 1;
            } catch (Throwable t) {
                fullTerminalState = 2;
                fullTerminalError = String.valueOf(t.getMessage() != null ? t.getMessage() : t);
            }
        }
        return fullTerminalState == 1;
    }

    void showFullTerminal() {
        android.widget.FrameLayout host = (android.widget.FrameLayout) findViewById(R.id.terminalHost);
        if (host == null) return;
        if (termView == null) {
            termView = new com.termux.view.TerminalView(this, null);
            // Termux sets these in its XML layout. Without them the view cannot take focus,
            // and Android will not show the soft keyboard for it.
            termView.setFocusable(true);
            termView.setFocusableInTouchMode(true);
            termView.setDefaultFocusHighlightEnabled(false);
            termTextSizePx = Math.round(14f * getResources().getDisplayMetrics().scaledDensity);
            termView.setTextSize(termTextSizePx);
            termView.setTerminalViewClient(termViewClient);
            host.addView(termView, new android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT));
            buildTerminalKeys();
        }
        if (termSession == null) startTermSession();
    }

    void startTermSession() {
        File cwd = shellStartDirectory();
        java.util.ArrayList<String> env = new java.util.ArrayList<String>();
        java.util.Map<String, String> base = new java.util.HashMap<String, String>(System.getenv());
        base.put("TERM", "xterm-256color");
        base.put("COLORTERM", "truecolor");
        base.put("HOME", getFilesDir().getAbsolutePath());
        base.put("TMPDIR", getCacheDir().getAbsolutePath());
        base.put("LANG", "en_US.UTF-8");
        // Android's own /system/etc/mkshrc builds the prompt with the last exit code ("127|...").
        // After a failed command that prompt leaves the cursor on the next line, so load it for
        // aliases/settings and then replace the prompt with a plain one.
        try {
            File rc = new File(getFilesDir(), "aideui_mkshrc");
            String script = "[ -r /system/etc/mkshrc ] && . /system/etc/mkshrc\n"
                          + "PS1='${PWD:-?} $ '\n";
            java.io.FileOutputStream out = new java.io.FileOutputStream(rc);
            try { out.write(script.getBytes("UTF-8")); } finally { out.close(); }
            base.put("ENV", rc.getAbsolutePath());
        } catch (java.io.IOException e) {
            if (new File("/system/etc/mkshrc").isFile()) base.put("ENV", "/system/etc/mkshrc");
        }
        for (java.util.Map.Entry<String, String> e : base.entrySet()) env.add(e.getKey() + "=" + e.getValue());
        termSession = new com.termux.terminal.TerminalSession("/system/bin/sh", cwd.getAbsolutePath(),
                new String[]{"sh"}, env.toArray(new String[0]), 2000, termSessionClient);
        termFinished = false;
        termView.attachSession(termSession);
        termView.requestFocus();
    }

    void restartTermSession() {
        com.termux.terminal.TerminalSession old = termSession;
        termSession = null;
        termTitle = "";
        termFinished = false;
        if (old != null) old.finishIfRunning();   // its late exit callback is ignored: s != termSession
        startTermSession();
        // The tap that triggered the restart must still bring up the keyboard.
        if (termView != null) termView.post(new Runnable() { public void run() { showTermKeyboard(); } });
    }

    final com.termux.terminal.TerminalSessionClient termSessionClient = new com.termux.terminal.TerminalSessionClient() {
        public void onTextChanged(com.termux.terminal.TerminalSession s) { if (termView != null && s == termSession) termView.onScreenUpdated(); }
        public void onTitleChanged(com.termux.terminal.TerminalSession s) {
            if (s != termSession) return;
            termTitle = s.getTitle() == null ? "" : s.getTitle();
            if (bottomMode == MODE_TERMINAL) setLowerHeader("Terminal" + (termTitle.length() == 0 ? "" : " · " + termTitle), android.R.drawable.ic_menu_edit);
        }
        public void onSessionFinished(com.termux.terminal.TerminalSession s) {
            if (s != termSession) return;   // an old session we killed ourselves (e.g. on restart)
            termFinished = true;
            Toast.makeText(MainActivity.this, "Shell exited (" + s.getExitStatus() + "). Tap the terminal to restart.", Toast.LENGTH_LONG).show();
        }
        public void onCopyTextToClipboard(com.termux.terminal.TerminalSession s, String text) {
            android.content.ClipboardManager clip = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            clip.setPrimaryClip(android.content.ClipData.newPlainText("terminal", text));
        }
        public void onPasteTextFromClipboard(com.termux.terminal.TerminalSession s) { pasteIntoTerminal(); }
        public void onBell(com.termux.terminal.TerminalSession s) { }
        public void onColorsChanged(com.termux.terminal.TerminalSession s) { if (termView != null) termView.invalidate(); }
        public void onTerminalCursorStateChange(boolean state) { }
        public Integer getTerminalCursorStyle() { return null; }
        public void logError(String tag, String message) { android.util.Log.e(tag, message); }
        public void logWarn(String tag, String message) { android.util.Log.w(tag, message); }
        public void logInfo(String tag, String message) { android.util.Log.i(tag, message); }
        public void logDebug(String tag, String message) { android.util.Log.d(tag, message); }
        public void logVerbose(String tag, String message) { android.util.Log.v(tag, message); }
        public void logStackTraceWithMessage(String tag, String message, Exception e) { android.util.Log.e(tag, message, e); }
        public void logStackTrace(String tag, Exception e) { android.util.Log.e(tag, "error", e); }
    };

    final com.termux.view.TerminalViewClient termViewClient = new com.termux.view.TerminalViewClient() {
        public float onScale(float scale) {
            if (scale < 0.9f || scale > 1.1f) {
                float dp = getResources().getDisplayMetrics().scaledDensity;
                termTextSizePx = Math.max(Math.round(8 * dp), Math.min(Math.round(32 * dp),
                        termTextSizePx + (scale > 1f ? Math.round(dp) : -Math.round(dp))));
                termView.setTextSize(termTextSizePx);
                return 1.0f;
            }
            return scale;
        }
        public void onSingleTapUp(android.view.MotionEvent e) {
            if (termSession == null || !termSession.isRunning()) { restartTermSession(); return; }
            termFinished = false;
            showTermKeyboard();
        }
        public boolean shouldBackButtonBeMappedToEscape() { return false; }
        public boolean shouldEnforceCharBasedInput() { return true; }
        public boolean shouldUseCtrlSpaceWorkaround() { return false; }
        public boolean isTerminalViewSelected() { return true; }
        public void copyModeChanged(boolean copyMode) { }
        public boolean onKeyDown(int keyCode, android.view.KeyEvent e, com.termux.terminal.TerminalSession session) {
            if (keyCode == android.view.KeyEvent.KEYCODE_ENTER && session != null && !session.isRunning()) {
                restartTermSession();
                return true;
            }
            return false;
        }
        public boolean onKeyUp(int keyCode, android.view.KeyEvent e) { clearTerminalToggles(); return false; }
        public boolean onLongPress(android.view.MotionEvent event) { return false; }
        public boolean readControlKey() { return termCtrl; }
        public boolean readAltKey() { return termAlt; }
        public boolean readShiftKey() { return false; }
        public boolean readFnKey() { return false; }
        public boolean onCodePoint(int codePoint, boolean ctrlDown, com.termux.terminal.TerminalSession session) {
            if ((codePoint == '\r' || codePoint == '\n') && session != null && !session.isRunning()) {
                restartTermSession();
                return true;
            }
            clearTerminalToggles();
            return false;
        }
        public void onEmulatorSet() { }
        public void logError(String tag, String message) { android.util.Log.e(tag, message); }
        public void logWarn(String tag, String message) { android.util.Log.w(tag, message); }
        public void logInfo(String tag, String message) { android.util.Log.i(tag, message); }
        public void logDebug(String tag, String message) { android.util.Log.d(tag, message); }
        public void logVerbose(String tag, String message) { android.util.Log.v(tag, message); }
        public void logStackTraceWithMessage(String tag, String message, Exception e) { android.util.Log.e(tag, message, e); }
        public void logStackTrace(String tag, Exception e) { android.util.Log.e(tag, "error", e); }
    };

    void clearTerminalToggles() {
        if (!termCtrl && !termAlt) return;
        termCtrl = false;
        termAlt = false;
        runOnUiThread(new Runnable() { public void run() { updateToggleButtons(); } });
    }

    void updateToggleButtons() {
        if (termCtrlButton != null) termCtrlButton.setTextColor(termCtrl ? 0xFF4FC3F7 : 0xFFFFFFFF);
        if (termAltButton != null) termAltButton.setTextColor(termAlt ? 0xFF4FC3F7 : 0xFFFFFFFF);
    }

    android.widget.Button keyButton(String label, android.view.View.OnClickListener click) {
        android.widget.Button b = new android.widget.Button(this, null, android.R.attr.borderlessButtonStyle);
        b.setText(label);
        b.setTextColor(0xFFFFFFFF);
        b.setTextSize(13);
        b.setAllCaps(false);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        int pad = Math.round(10 * getResources().getDisplayMetrics().density);
        b.setPadding(pad, 0, pad, 0);
        b.setOnClickListener(click);
        return b;
    }

    void buildTerminalKeys() {
        android.widget.LinearLayout row = (android.widget.LinearLayout) findViewById(R.id.terminalKeysRow);
        if (row == null) return;
        row.removeAllViews();
        row.addView(keyButton("\u2328", new View.OnClickListener() { public void onClick(View v) { showTermKeyboard(); } }));
        row.addView(keyButton("ESC", new View.OnClickListener() { public void onClick(View v) { sendTermKey(android.view.KeyEvent.KEYCODE_ESCAPE); } }));
        row.addView(keyButton("TAB", new View.OnClickListener() { public void onClick(View v) { sendTermKey(android.view.KeyEvent.KEYCODE_TAB); } }));
        termCtrlButton = keyButton("CTRL", new View.OnClickListener() { public void onClick(View v) { termCtrl = !termCtrl; updateToggleButtons(); } });
        row.addView(termCtrlButton);
        termAltButton = keyButton("ALT", new View.OnClickListener() { public void onClick(View v) { termAlt = !termAlt; updateToggleButtons(); } });
        row.addView(termAltButton);
        row.addView(keyButton("^C", new View.OnClickListener() { public void onClick(View v) { sendTermText("\u0003"); } }));
        row.addView(keyButton("\u2190", new View.OnClickListener() { public void onClick(View v) { sendTermKey(android.view.KeyEvent.KEYCODE_DPAD_LEFT); } }));
        row.addView(keyButton("\u2191", new View.OnClickListener() { public void onClick(View v) { sendTermKey(android.view.KeyEvent.KEYCODE_DPAD_UP); } }));
        row.addView(keyButton("\u2193", new View.OnClickListener() { public void onClick(View v) { sendTermKey(android.view.KeyEvent.KEYCODE_DPAD_DOWN); } }));
        row.addView(keyButton("\u2192", new View.OnClickListener() { public void onClick(View v) { sendTermKey(android.view.KeyEvent.KEYCODE_DPAD_RIGHT); } }));
        row.addView(keyButton("HOME", new View.OnClickListener() { public void onClick(View v) { sendTermKey(android.view.KeyEvent.KEYCODE_MOVE_HOME); } }));
        row.addView(keyButton("END", new View.OnClickListener() { public void onClick(View v) { sendTermKey(android.view.KeyEvent.KEYCODE_MOVE_END); } }));
        row.addView(keyButton("PGUP", new View.OnClickListener() { public void onClick(View v) { sendTermKey(android.view.KeyEvent.KEYCODE_PAGE_UP); } }));
        row.addView(keyButton("PGDN", new View.OnClickListener() { public void onClick(View v) { sendTermKey(android.view.KeyEvent.KEYCODE_PAGE_DOWN); } }));
        String[] chars = {"-", "/", "|", "~", "\\"};
        for (final String c : chars) {
            row.addView(keyButton(c, new View.OnClickListener() { public void onClick(View v) { sendTermText(c); } }));
        }
        row.addView(keyButton("\u22EE", new View.OnClickListener() { public void onClick(View v) { showFullTerminalMenu(); } }));
    }

    void showTermKeyboard() {
        if (termView == null) return;
        termView.setFocusable(true);
        termView.setFocusableInTouchMode(true);
        termView.requestFocus();
        android.view.inputmethod.InputMethodManager imm =
            (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.restartInput(termView);   // re-bind the IME, e.g. after a dialog or a restarted session
            imm.showSoftInput(termView, 0);
        }
    }

    void sendTermKey(int keyCode) {
        if (termView == null) return;
        long now = android.os.SystemClock.uptimeMillis();
        termView.dispatchKeyEvent(new android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_DOWN, keyCode, 0));
        termView.dispatchKeyEvent(new android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_UP, keyCode, 0));
        termView.requestFocus();
    }

    void sendTermText(String text) {
        if (termSession == null || !termSession.isRunning()) return;
        try {
            byte[] bytes = text.getBytes("UTF-8");
            termSession.write(bytes, 0, bytes.length);
        } catch (java.io.UnsupportedEncodingException ignored) { }
    }

    void pasteIntoTerminal() {
        android.content.ClipboardManager clip = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clip == null || !clip.hasPrimaryClip() || termSession == null || termSession.getEmulator() == null) return;
        CharSequence text = clip.getPrimaryClip().getItemAt(0).coerceToText(this);
        if (text != null && text.length() > 0) termSession.getEmulator().paste(text.toString());
    }

    void showFullTerminalMenu() {
        final CharSequence[] items = {"Paste", "Copy all output", "Restart shell", "Go to project folder", "Go to app data (Android/data)", "Go to app data (/data/user/0)", "Larger text", "Smaller text", "About this terminal"};
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
            .setItems(items, new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int which) {
                    float dp = getResources().getDisplayMetrics().scaledDensity;
                    if (which == 0) pasteIntoTerminal();
                    else if (which == 1) {
                        if (termSession != null && termSession.getEmulator() != null) {
                            android.content.ClipboardManager clip = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                            clip.setPrimaryClip(android.content.ClipData.newPlainText("terminal",
                                termSession.getEmulator().getScreen().getTranscriptText()));
                            Toast.makeText(MainActivity.this, "Copied", Toast.LENGTH_SHORT).show();
                        }
                    }
                    else if (which == 2) restartTermSession();
                    else if (which == 3) sendTermText("cd '" + shellStartDirectory().getAbsolutePath().replace("'", "'\\''") + "'\r");
                    else if (which == 4) {
                        // This app's own folder under Android/data. Only the exact path works: listing
                        // Android/data itself is denied by Android, but the app's own folder is allowed.
                        File ext = getExternalFilesDir(null);   // also creates it
                        File dir = ext == null ? null : ext.getParentFile();
                        if (dir == null) Toast.makeText(MainActivity.this, "Android/data folder unavailable", Toast.LENGTH_LONG).show();
                        else sendTermText("cd '" + dir.getAbsolutePath().replace("'", "'\\''") + "'\r");
                    }
                    else if (which == 5) {
                        String data = getApplicationInfo().dataDir;   // /data/user/0/com.aideui
                        if (data != null) sendTermText("cd '" + data.replace("'", "'\\''") + "'\r");
                    }
                    else if (which == 6) { termTextSizePx = Math.min(Math.round(32 * dp), termTextSizePx + Math.round(dp)); termView.setTextSize(termTextSizePx); }
                    else if (which == 7) { termTextSizePx = Math.max(Math.round(8 * dp), termTextSizePx - Math.round(dp)); termView.setTextSize(termTextSizePx); }
                    else showFullTerminalAbout();
                }
            }).show();
    }

    void showFullTerminalAbout() {
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
            .setTitle("About this terminal")
            .setMessage("A real terminal (pseudo-terminal, colours, Ctrl-C, full-screen programs) running Android's own shell as AideUI. "
                + "Android's built-in tools work (ls, cat, cp, ps, getprop, ping, vi if your device has it, …).\n\n"
                + "It is not Termux: Termux's packages (git, gradle, python…) cannot run from another app.\n\n"
                + "Tap the screen for the keyboard; long-press to select text. Pinch to change the text size. "
                + "CTRL and ALT apply to the next key. Terminal engine: Termux terminal-emulator and terminal-view (Apache License 2.0).")
            .setPositiveButton("OK", null).show();
    }

    File shellStartDirectory() {
        if (activeProject != null && activeProject.isDirectory()) return activeProject;
        if (currentDir != null && currentDir.isDirectory()) return currentDir;
        return getFilesDir();
    }

    void startShell() {
        if (shell != null) shell.destroy();
        shell = new ShellSession(shellStartDirectory(), getFilesDir(), new ShellSession.Listener() {
            public void onOutput(String text) { appendTerminal(text); }
            public void onDirectory(String directory) {
                shellDirectory = directory;
                if (bottomMode == MODE_TERMINAL) {
                    setLowerHeader("Terminal · " + directory, android.R.drawable.ic_menu_edit);
                }
            }
            public void onExit(int code) {
                appendTerminal("\n[shell exited with code " + code + "]\n");
            }
        });
        try {
            shell.start();
        } catch (java.io.IOException e) {
            appendTerminal("Cannot start the shell: " + e.getMessage() + "\n");
        }
    }

    void appendTerminal(String text) {
        if (terminalOutput == null) return;
        terminalOutput.append(text);
        CharSequence all = terminalOutput.getText();
        if (all instanceof android.text.Editable && all.length() > TERMINAL_MAX_CHARS) {
            ((android.text.Editable) all).delete(0, all.length() - TERMINAL_KEEP_CHARS);
        }
        terminalScroll.post(new Runnable() {
            public void run() { terminalScroll.fullScroll(View.FOCUS_DOWN); }
        });
    }

    void runTerminalCommand() {
        if (terminalInput == null) return;
        String command = terminalInput.getText().toString().trim();
        if (command.length() == 0) return;
        terminalInput.setText("");
        if (shellHistory.isEmpty() || !shellHistory.get(shellHistory.size() - 1).equals(command)) shellHistory.add(command);
        shellHistoryPosition = shellHistory.size();
        if (command.equals("clear")) { terminalOutput.setText(""); return; }
        if (shell == null || !shell.isAlive()) startShell();
        appendTerminal("$ " + command + "\n");
        try {
            shell.send(command);
        } catch (java.io.IOException e) {
            appendTerminal("Shell is not running: " + e.getMessage() + "\n");
        }
    }

    void recallTerminalHistory(int step) {
        if (shellHistory.isEmpty() || terminalInput == null) return;
        shellHistoryPosition = Math.max(0, Math.min(shellHistory.size(), shellHistoryPosition + step));
        String text = shellHistoryPosition >= shellHistory.size() ? "" : shellHistory.get(shellHistoryPosition);
        terminalInput.setText(text);
        terminalInput.setSelection(text.length());
    }

    void showTerminalMenu() {
        final CharSequence[] items = {"Clear output", "Stop / restart shell", "Go to project folder", "Copy all output", "About this terminal"};
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
            .setItems(items, new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int which) {
                    if (which == 0) terminalOutput.setText("");
                    else if (which == 1) { appendTerminal("[restarting shell]\n"); startShell(); }
                    else if (which == 2) sendTerminalDirect("cd '" + shellStartDirectory().getAbsolutePath().replace("'", "'\\''") + "'");
                    else if (which == 3) {
                        android.content.ClipboardManager clip =
                            (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                        clip.setPrimaryClip(android.content.ClipData.newPlainText("terminal", terminalOutput.getText()));
                        Toast.makeText(MainActivity.this, "Copied", Toast.LENGTH_SHORT).show();
                    } else showTerminalAbout();
                }
            }).show();
    }

    void sendTerminalDirect(String command) {
        if (shell == null || !shell.isAlive()) startShell();
        appendTerminal("$ " + command + "\n");
        try { shell.send(command); }
        catch (java.io.IOException e) { appendTerminal("Shell is not running: " + e.getMessage() + "\n"); }
    }

    void showTerminalAbout() {
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
            .setTitle("About this terminal")
            .setMessage("A shell running as AideUI, with Android's built-in tools (ls, cat, cp, ps, getprop, ping, …) "
                + "and access to your storage folders.\n\n"
                + "It is not Termux: Termux's programs (git, gradle, python…) cannot run from another app. "
                + "There is no pseudo-terminal, so full-screen programs (nano, vim, top) and colours do not work. "
                + "A command that never ends can be stopped with ⋮ > Stop / restart shell.\n\n"
                + "Type clear to empty the screen. The ↑ and ↓ buttons recall earlier commands.")
            .setPositiveButton("OK", null).show();
    }

    void refreshDebuggerPanel() {
        entries.clear();
        setLowerHeader("Not running", android.R.drawable.ic_media_pause);
        entries.add(new Entry(PANEL_MESSAGE, "Breakpoint execution is not available in this build.", null));
        adapter.notifyDataSetChanged();
    }

    void refreshLogcatPanel() {
        entries.clear();
        setLowerHeader("LogCat", android.R.drawable.ic_menu_info_details);
        entries.add(new Entry(PANEL_MESSAGE, "1  <run the app to see its log output>", null));
        adapter.notifyDataSetChanged();
    }

    void refreshOpenTabsPanel() {
        entries.clear();
        setLowerHeader("File List", android.R.drawable.ic_menu_sort_by_size);
        if (openTabs.isEmpty()) {
            entries.add(new Entry(PANEL_MESSAGE, "No open files", null));
        } else {
            for (File f : openTabs) entries.add(new Entry(OPEN_TAB, f.getName(), f));
        }
        adapter.notifyDataSetChanged();
    }

    void setBottomMode(int mode) {
        if (bottomMode == MODE_TERMINAL && mode != MODE_TERMINAL && termView != null) {
            android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(termView.getWindowToken(), 0);
        }
        bottomMode = mode;
        if (bottomCollapsed) setBottomCollapsed(false);
        refresh();
    }

    void navigateToDirectory(File directory) {
        if (directory == null) return;
        if (!directory.isDirectory()) {
            Toast.makeText(this, "Not a directory: " + directory.getAbsolutePath(), Toast.LENGTH_LONG).show();
            return;
        }
        // Avoid entering locations Android refuses to enumerate. An empty array is a
        // readable empty directory; null signals an error or blocked traversal.
        if (directory.listFiles() == null) {
            Toast.makeText(this, "Cannot list this folder. Android storage access may be restricted: "
                    + directory.getAbsolutePath(), Toast.LENGTH_LONG).show();
            return;
        }
        currentDir = directory;
        bottomMode = MODE_FILES;
        refresh();
    }

    /** Recognize a project at its root, not every Gradle module or source subfolder. */
    static boolean isProjectDirectory(File d) {
        if (d == null || !d.isDirectory()) return false;
        return new File(d, "settings.gradle").isFile()
                || new File(d, "settings.gradle.kts").isFile()
                || (new File(d, "build.gradle").isFile() && new File(d, "app").isDirectory())
                || (new File(d, "AndroidManifest.xml").isFile() && new File(d, "src").isDirectory());
    }

    static File owningProject(File target) {
        if (target == null) return null;
        for (File d = target.isDirectory() ? target : target.getParentFile();
                d != null; d = d.getParentFile()) {
            if (isProjectDirectory(d)) return d;
        }
        return null;
    }

    static boolean sameProject(File a, File b) {
        if (a == null || b == null) return a == b;
        try { return a.getCanonicalFile().equals(b.getCanonicalFile()); }
        catch (java.io.IOException ex) { return a.getAbsolutePath().equals(b.getAbsolutePath()); }
    }

    /** Save/discard/cancel once for the project as a whole; all its tabs are closed on switch. */
    void requestProjectSwitch(final File nextProject, final Runnable action) {
        if (nextProject == null || activeProject == null || sameProject(nextProject, activeProject)) {
            if (nextProject != null) activeProject = nextProject;
            action.run();
            return;
        }
        android.app.AlertDialog.Builder prompt = new android.app.AlertDialog.Builder(
                this, android.R.style.Theme_Material_Light_Dialog_Alert)
            .setTitle("Open another project?")
            .setMessage("Close " + activeProject.getName() + " and all its open files, then open "
                    + nextProject.getName() + "?")
            .setNegativeButton("Cancel", null);
        if (editorDirty && currentFile != null) {
            prompt.setMessage("Close " + activeProject.getName() + " and all its open files to open "
                            + nextProject.getName() + "?\n\nYou have unsaved changes to "
                            + currentFile.getName() + ".")
                .setPositiveButton("Save & Open", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int which) {
                        if (saveEditor(false)) finishProjectSwitch(nextProject, action);
                    }
                })
                .setNeutralButton("Discard & Open", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int which) {
                        finishProjectSwitch(nextProject, action);
                    }
                });
        } else {
            prompt.setPositiveButton("Close & Open", new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int which) {
                    finishProjectSwitch(nextProject, action);
                }
            });
        }
        prompt.show();
    }

    void finishProjectSwitch(File nextProject, Runnable action) {
        closeProjectNow();
        activeProject = nextProject;
        action.run();
    }

    void openProjectFolder(final File folder) {
        final File target = folder;
        requestProjectSwitch(folder, new Runnable() {
            public void run() { navigateToDirectory(target); }
        });
    }

    void onEntryClicked(Entry e) {
        switch (e.type) {
            case UP: navigateToDirectory(e.file); break;
            case NEW_PROJECT: showCreateProjectDialog(); break;
            case NEW_XML: createXmlFile(); break;
            case CLONE: onAction("clone_git"); break;
            case DIR:
                if (isProjectDirectory(e.file)) openProjectFolder(e.file);
                else navigateToDirectory(e.file);
                break;
            case FILE: openFileSafely(e.file); break;
            case ERROR_ITEM:
                showQuickFixForError(e);
                break;
            case SEARCH_ITEM:
                if (e.file != null && e.line > 0) openFileAt(e.file, e.line);
                break;
            case OPEN_TAB:
            case ERROR_FILE_HEADER:
                if (e.file != null) openFileSafely(e.file);
                break;
        }
    }

    /** Context-sensitive Quick Fix for unresolved Java types in the lower Errors panel. */
    void showQuickFixForError(final Entry e) {
        if (e.file == null || e.line < 1) return;
        if (currentFile == null || !currentFile.equals(e.file)) {
            openFileAt(e.file, e.line);
            return;
        }
        if (!e.file.getName().endsWith(".java")) { goToLine(e.line); return; }
        final String source = editorText.getText().toString();
        final String qualified = JavaQuickFix.importForError(e.label, source);
        final boolean listOf = e.label.contains("List cannot be resolved")
                && JavaQuickFix.hasListOfOnLine(source, e.line);
        final java.util.List<String> actions = new java.util.ArrayList<String>();
        actions.add("Go to line " + e.line);
        if (qualified != null) actions.add("Quick Fix: Import " + qualified);
        if (listOf) actions.add("Java 8: Replace List.of with Arrays.asList");
        if (actions.size() == 1) { goToLine(e.line); return; }
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
            .setTitle("Quick Fix · line " + e.line)
            .setItems(actions.toArray(new CharSequence[actions.size()]),
                new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface dialog, int index) {
                        String selected = actions.get(index);
                        if (index == 0) { goToLine(e.line); return; }
                        if (selected.startsWith("Quick Fix: Import ")) {
                            String updated = JavaQuickFix.addImport(source, qualified);
                            applyQuickFixCode(source, updated, "Imported " + qualified);
                        } else if (selected.startsWith("Java 8:")) {
                            final String changed = JavaQuickFix.replaceListOfOnLine(source, e.line);
                            if (changed == null) return;
                            new android.app.AlertDialog.Builder(MainActivity.this,
                                android.R.style.Theme_Material_Light_Dialog_Alert)
                                .setTitle("Java 8 compatibility")
                                .setMessage("Arrays.asList(...) returns a fixed-size, mutable-element list. " +
                                    "List.of(...) returns an unmodifiable list and rejects null elements. " +
                                    "Apply the conversion and import java.util.Arrays?")
                                .setPositiveButton("Apply", new android.content.DialogInterface.OnClickListener() {
                                    public void onClick(android.content.DialogInterface d, int which) {
                                        String fixed = JavaQuickFix.addImport(changed, "java.util.Arrays");
                                        if (JavaQuickFix.importForError("List cannot be resolved to a type", fixed) != null)
                                            fixed = JavaQuickFix.addImport(fixed, "java.util.List");
                                        applyQuickFixCode(source, fixed, "Converted List.of to Arrays.asList");
                                    }
                                }).setNegativeButton("Cancel", null).show();
                        }
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    void applyQuickFixCode(String before, String after, String label) {
        if (before.equals(after)) return;
        int caret = Math.max(0, editorText.getSelectionStart());
        // Inserting an import above the caret should not jump the cursor to a new token.
        int prefix = 0;
        while (prefix < before.length() && prefix < after.length() &&
                before.charAt(prefix) == after.charAt(prefix)) prefix++;
        if (caret >= prefix) caret += after.length() - before.length();
        caret = Math.max(0, Math.min(caret, after.length()));
        if (completionPopup != null && completionPopup.isShowing()) completionPopup.dismiss();
        applyingFormatEdit = true;
        try { editorText.getText().replace(0, editorText.length(), after); }
        finally { applyingFormatEdit = false; }
        editorText.setSelection(caret);
        updateUndoRedoButtons();
        Toast.makeText(this, label + ". Undo available; Save then rebuild to verify.", Toast.LENGTH_LONG).show();
    }

    // ---------- file loading and editor ----------

    void openFile(File f) {
        ++compilerSequence;
        if (pendingLiveCompile != null) editorHandler.removeCallbacks(pendingLiveCompile);
        File previous = currentFile;
        if (completionPopup != null) completionPopup.dismiss();
        try {
            currentFile = f;
            refreshCompletionCatalog(false);
            rememberOpenTab(f);
            showText(f.getName(), f.getAbsolutePath(), readAll(new FileInputStream(f)));
            if (f.getName().endsWith(".java")) {
                runCompiler(f);
                maybeOfferAndroidx(f);
            } else if (f.getName().endsWith(".kt")) {
                scheduleLiveKotlinCheck();
            }
            editorHandler.post(new Runnable() { public void run() { checkAutomaticLibrarySync(); } });
        } catch (Exception ex) {
            currentFile = previous;
            Toast.makeText(this, "Cannot open: " + ex.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    void rememberOpenTab(File file) {
        if (file == null) return;
        // Keep tabs in a stable order when switching among already open files.
        if (!openTabs.contains(file)) {
            openTabs.add(file);
            while (openTabs.size() > 8) openTabs.remove(0);
        }
        updateOpenedTabs();
        if (bottomMode == MODE_FILE_LIST) refreshOpenTabsPanel();
    }

    /** AIDE-style compact horizontal file tabs, directly below the main toolbar. */
    void updateOpenedTabs() {
        if (tabsBar == null) return;
        StringBuilder sig = new StringBuilder();
        sig.append(currentFile == null ? "" : currentFile.getAbsolutePath()).append('|').append(editorDirty);
        for (File f : openTabs) sig.append('|').append(f.getAbsolutePath());
        if (lastTabsSignature.equals(sig.toString())) return;
        lastTabsSignature = sig.toString();
        tabsBar.removeAllViews();
        tabsBar.setVisibility(openTabs.isEmpty() ? View.GONE : View.VISIBLE);
        View selectedTab = null;
        for (int i = 0; i < openTabs.size(); i++) {
            final File file = openTabs.get(i);
            boolean active = currentFile != null && currentFile.equals(file);
            LinearLayout tab = new LinearLayout(this);
            tab.setOrientation(LinearLayout.VERTICAL);
            tab.setBackgroundColor(Color.WHITE);
            // Width and two-line labels match the original AIDE's scrollable file tabs.
            // Thin separator between files, like the original AIDE tab strip.
            if (i > 0) {
                View sep = new View(this);
                sep.setBackgroundColor(Color.parseColor("#E4E4E4"));
                LinearLayout.LayoutParams sepLp = new LinearLayout.LayoutParams(dp(1), dp(40));
                sepLp.gravity = Gravity.CENTER_VERTICAL;
                tabsBar.addView(sep, sepLp);
            }
            LinearLayout.LayoutParams tabLp = new LinearLayout.LayoutParams(dp(150), dp(56));
            tabsBar.addView(tab, tabLp);
            LinearLayout tabContents = new LinearLayout(this);
            tabContents.setOrientation(LinearLayout.HORIZONTAL);
            tabContents.setGravity(Gravity.CENTER_VERTICAL);
            tab.addView(tabContents, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            TextView label = new TextView(this);
            label.setText((file.getName() + (active && editorDirty ? " *" : "")).toUpperCase(java.util.Locale.US));
            label.setTextSize(14);
            label.setTextColor(Color.parseColor(active ? "#171717" : "#626262"));
            label.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
            label.setPadding(dp(10), dp(2), dp(0), dp(2));
            label.setMaxLines(2);
            label.setEllipsize(android.text.TextUtils.TruncateAt.END);
            tabContents.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            TextView close = new TextView(this);
            close.setText("×");
            close.setTextSize(23);
            close.setTextColor(Color.parseColor("#666666"));
            close.setGravity(Gravity.CENTER);
            close.setContentDescription("Close " + file.getName());
            tabContents.addView(close, new LinearLayout.LayoutParams(dp(40), ViewGroup.LayoutParams.MATCH_PARENT));
            close.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { closeOpenTab(file); }
            });
            label.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (currentFile == null || !currentFile.equals(file)) openFileSafely(file);
                    else showOpenTabActions(v, file);
                }
            });
            label.setOnLongClickListener(new View.OnLongClickListener() {
                public boolean onLongClick(View v) { showOpenTabActions(v, file); return true; }
            });
            View underline = new View(this);
            underline.setBackgroundColor(active ? Color.parseColor("#29B6F6") : Color.TRANSPARENT);
            tab.addView(underline, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));
            tab.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (currentFile == null || !currentFile.equals(file)) openFileSafely(file);
                    else showOpenTabActions(v, file);
                }
            });
            tab.setOnLongClickListener(new View.OnLongClickListener() {
                public boolean onLongClick(View v) { showOpenTabActions(v, file); return true; }
            });
            if (active) selectedTab = tab;
        }
        final View tabToReveal = selectedTab;
        final HorizontalScrollView scroll = (HorizontalScrollView) findViewById(R.id.tabsScroll);
        if (scroll != null && tabToReveal != null) {
            scroll.post(new Runnable() {
                public void run() {
                    int left = tabToReveal.getLeft();
                    int right = tabToReveal.getRight();
                    int viewLeft = scroll.getScrollX();
                    if (left < viewLeft) scroll.smoothScrollTo(left, 0);
                    else if (right > viewLeft + scroll.getWidth())
                        scroll.smoothScrollTo(right - scroll.getWidth(), 0);
                }
            });
        }
    }

    /** Quick actions for an open tab. Reload always reads current disk contents. */
    void showOpenTabActions(View anchor, final File file) {
        PopupMenu pm = new PopupMenu(this, anchor);
        pm.getMenu().add(0, 1, 0, "Reload from Disk");
        pm.getMenu().add(0, 2, 1, "Close File");
        pm.getMenu().add(0, 3, 2, "Copy Path");
        pm.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            public boolean onMenuItemClick(android.view.MenuItem item) {
                if (item.getItemId() == 1) reloadOpenFileFromDisk(file);
                else if (item.getItemId() == 2) closeOpenTab(file);
                else if (item.getItemId() == 3) copyPath(file);
                return true;
            }
        });
        pm.show();
    }

    void reloadCurrentFileFromDisk() {
        if (currentFile == null) {
            Toast.makeText(this, "No file open", Toast.LENGTH_SHORT).show();
            return;
        }
        reloadOpenFileFromDisk(currentFile);
    }

    /** Never replace unsaved editor text without explicit permission. */
    void reloadOpenFileFromDisk(final File file) {
        if (file == null || !file.isFile() || !file.canRead()) {
            Toast.makeText(this, "File unavailable on disk: " + (file == null ? "" : file.getAbsolutePath()),
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (currentFile != null && currentFile.equals(file) && editorDirty) {
            new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
                .setTitle("Reload from Disk")
                .setMessage("Discard your unsaved changes to " + file.getName()
                        + " and load the file currently on disk?")
                .setPositiveButton("Discard and Reload", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int which) {
                        editorDirty = false;
                        openFile(file);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
            return;
        }
        if (currentFile != null && !currentFile.equals(file)) {
            // Switching files can itself have unsaved edits: retain the existing guard.
            openFileSafely(file);
            return;
        }
        openFile(file);
    }

    void closeOpenTab(final File file) {
        if (currentFile != null && currentFile.equals(file) && editorDirty) {
            new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
                .setTitle("Unsaved changes")
                .setMessage("Save changes to " + file.getName() + " before closing?")
                .setPositiveButton("Save", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        if (saveEditor(false)) closeOpenTab(file);
                    }
                }).setNeutralButton("Discard", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        editorDirty = false;
                        closeOpenTab(file);
                    }
                }).setNegativeButton("Cancel", null).show();
            return;
        }
        openTabs.remove(file);
        if (currentFile != null && currentFile.equals(file)) {
            currentFile = null;
            if (completionPopup != null) completionPopup.dismiss();
            fileHeader.setVisibility(View.GONE);
            fileHeaderLine.setVisibility(View.GONE);
            editorScroll.setVisibility(View.GONE);
            emptyText.setVisibility(View.VISIBLE);
            currentErrors.clear();
            updateErrorBadge();
            if (bottomMode == MODE_ERRORS) refreshErrorsPanel();
            titleText.setText("");
            // Return to the most recently opened remaining tab, if any.
            if (!openTabs.isEmpty()) {
                openFile(openTabs.get(openTabs.size() - 1));
            }
        }
        updateOpenedTabs();
        if (bottomMode == MODE_FILE_LIST) refreshOpenTabsPanel();
    }

    String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n, total = 0;
        while ((n = in.read(buf)) > 0 && total < 1024 * 1024) { out.write(buf, 0, n); total += n; }
        in.close();
        return out.toString("UTF-8");
    }

    void showText(String name, String path, String text) {
        if (completionPopup != null) completionPopup.dismiss();
        editorLoading = true;
        editorSaved = text;
        editorDirty = false;
        titleText.setText(name);
        closeFindBar();
        fileNameText.setText(name);
        filePathText.setText(path);
        String lowerName = name.toLowerCase(java.util.Locale.US);
        boolean markup = lowerName.endsWith(".xml") || lowerName.endsWith(".html")
                || lowerName.endsWith(".htm") || lowerName.endsWith(".xhtml");
        editorText.configureGuides(blockGuidesEnabled, markup, 4);
        // Filename is already in the tab. Remove duplicate title/path header to give code more room.
        fileHeader.setVisibility(View.GONE);
        fileHeaderLine.setVisibility(View.GONE);
        if (name.endsWith(".java")) {
            JavaAnalyzer.Result r = JavaAnalyzer.analyze(text, currentFile);
            editorText.setText(r.text, TextView.BufferType.SPANNABLE);
            currentErrors = r.errors;
        } else {
            editorText.setText(lowerName.endsWith(".xml") ? XmlSyntaxHighlighter.highlight(text)
                    : lowerName.endsWith(".kt") || lowerName.endsWith(".kts") ? KotlinSyntaxHighlighter.highlight(text) : text,
                    TextView.BufferType.SPANNABLE);
            currentErrors = lowerName.endsWith(".xml")
                    ? XmlAnalyzer.analyze(text) : new ArrayList<JavaAnalyzer.Err>();
        }
        editorText.setErrors(currentErrors);
        editorText.forgetRefactorSelection();
        editorText.setSelection(0);
        editorText.clearHistoryToCurrent();
        editorLoading = false;
        updateErrorBadge();
        if (bottomMode == MODE_ERRORS) refreshErrorsPanel();
        updateOpenedTabs();
        updateUndoRedoButtons();
        int lines = 1;
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') lines++;
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= lines; i++) { sb.append(i); if (i < lines) sb.append('\n'); }
        lineNumbers.setText(sb);
        emptyText.setVisibility(View.GONE);
        editorScroll.setVisibility(View.VISIBLE);
        editorScroll.scrollTo(0, 0);
    }

    int countErrors() {
        int n = 0;
        for (JavaAnalyzer.Err e : currentErrors) if (!e.warning) n++;
        return n;
    }

    int countWarnings() {
        int n = 0;
        for (JavaAnalyzer.Err e : currentErrors) if (e.warning) n++;
        return n;
    }

    void updateErrorBadge() {
        int errors = countErrors();
        int warnings = countWarnings();
        // No empty red X: show warning-only state with a yellow ! icon.
        errorBox.setVisibility(errors == 0 && warnings == 0 ? View.GONE : View.VISIBLE);
        ImageView icon = (ImageView) ((LinearLayout) errorBox).getChildAt(0);
        icon.setImageResource(errors > 0 ? R.drawable.ic_error : R.drawable.ic_warning);
        errorCount.setText(errors > 0 ? String.valueOf(errors) : String.valueOf(warnings));
        errorCount.setContentDescription(errors + " errors, " + warnings + " warnings. Open Errors panel");
        if (bottomMode == MODE_ERRORS && adapter != null) refreshErrorsPanel();
    }

    /** ECJ checks the actual editor buffer, including changes not saved to disk. */
    void runCompiler(final File f) {
        if (f == null || !f.getName().endsWith(".java")) return;
        final String snapshot = currentFile != null && currentFile.equals(f)
                ? editorText.getText().toString() : null;
        compilerStatus = "Checking Java…";
        final int sequence = ++compilerSequence;
        File mod = MavenLibraries.moduleFromSource(f);
        String variant = mod == null ? "debug" : selectedBuildVariant(mod);
        JavaCompiler.compileAsync(this, f, variant, snapshot, new JavaCompiler.Callback() {
            public void onDone(List<JavaAnalyzer.Err> errs, String status) {
                if (sequence != compilerSequence || currentFile == null
                        || !currentFile.equals(f)
                        || !editorText.getText().toString().equals(snapshot)) return;
                if (errs == null) {
                    compilerStatus = status == null ? "ECJ unavailable" : status;
                    // Keep lightweight diagnostics when ECJ cannot run.
                    return;
                }
                compilerStatus = "Compiled current editor text with ECJ";
                currentErrors = errs;
                editorText.setErrors(currentErrors);
                updateErrorBadge();
            }
        });
    }

    /** XML checks use the unsaved editor buffer with the same debounce as Java.
     * The generation check prevents stale results from a previous file or edit. */
    void scheduleLiveXmlCheck() {
        if (pendingLiveCompile != null) editorHandler.removeCallbacks(pendingLiveCompile);
        if (currentFile == null || !currentFile.getName().toLowerCase(java.util.Locale.US).endsWith(".xml")) return;
        final int sequence = ++compilerSequence;
        final File target = currentFile;
        pendingLiveCompile = new Runnable() {
            public void run() {
                pendingLiveCompile = null;
                if (currentFile == null || !currentFile.equals(target)) return;
                final String snapshot = editorText.getText().toString();
                new Thread(new Runnable() {
                    public void run() {
                        final List<JavaAnalyzer.Err> result = XmlAnalyzer.analyze(snapshot);
                        editorHandler.post(new Runnable() {
                            public void run() {
                                if (sequence != compilerSequence || currentFile == null
                                        || !currentFile.equals(target)
                                        || !editorText.getText().toString().equals(snapshot)) return;
                                currentErrors = result;
                                editorText.setErrors(currentErrors);
                                updateErrorBadge();
                            }
                        });
                    }
                }, "aideui-live-xml").start();
            }
        };
        editorHandler.postDelayed(pendingLiveCompile, LIVE_COMPILE_DELAY_MS);
    }

    void scheduleLiveCompile() {
        if (pendingLiveCompile != null) editorHandler.removeCallbacks(pendingLiveCompile);
        if (currentFile == null || !currentFile.getName().endsWith(".java")) return;
        // Invalidate any older result immediately, even before the debounce fires.
        ++compilerSequence;
        final File target = currentFile;
        pendingLiveCompile = new Runnable() {
            public void run() {
                pendingLiveCompile = null;
                if (currentFile != null && currentFile.equals(target)) runCompiler(target);
            }
        };
        editorHandler.postDelayed(pendingLiveCompile, LIVE_COMPILE_DELAY_MS);
    }

    void scheduleLiveKotlinCheck() {
        if (pendingLiveCompile != null) editorHandler.removeCallbacks(pendingLiveCompile);
        if (currentFile == null || !currentFile.getName().endsWith(".kt")) return;
        ++compilerSequence;   // invalidate any older result immediately
        final File target = currentFile;
        pendingLiveCompile = new Runnable() {
            public void run() {
                pendingLiveCompile = null;
                if (currentFile != null && currentFile.equals(target)) runKotlinCheck(target);
            }
        };
        editorHandler.postDelayed(pendingLiveCompile, KOTLIN_CHECK_DELAY_MS);
    }

    /** Kotlin counterpart of runCompiler: the real Kotlin compiler checks the unsaved editor text. */
    void runKotlinCheck(final File f) {
        if (f == null || !f.getName().endsWith(".kt") || currentFile == null || !currentFile.equals(f)) return;
        if (!KotlinToolchain.compiler(this).isFile()) {
            compilerStatus = "Kotlin checks are off: install the Kotlin compiler (More… > Libraries…)";
            return;
        }
        if (buildSession != null && buildSession.running) return;
        final File module = MavenLibraries.moduleFromSource(f);
        if (module == null) { compilerStatus = "Kotlin checks need a src/main/kotlin or src/main/java layout"; return; }
        if (kotlinCheckRunning) { kotlinCheckAgain = true; return; }
        final String snapshot = editorText.getText().toString();
        kotlinCheckRunning = true;
        compilerStatus = "Checking Kotlin…";
        final int sequence = ++compilerSequence;
        final String variant = selectedBuildVariant(module);
        new Thread(new Runnable() {
            public void run() {
                List<KotlinCompiler.Diagnostic> found = null;
                final String[] status = {null};
                try { found = KotlinCompiler.check(MainActivity.this, module, f, snapshot, variant, status); }
                catch (Throwable t) { status[0] = "Kotlin check failed: " + t; }
                final List<KotlinCompiler.Diagnostic> result = found;
                runOnUiThread(new Runnable() {
                    public void run() {
                        kotlinCheckRunning = false;
                        boolean current = sequence == compilerSequence && currentFile != null
                                && currentFile.equals(f) && editorText.getText().toString().equals(snapshot);
                        if (current) {
                            if (result == null) {
                                compilerStatus = status[0] == null ? "Kotlin check unavailable" : status[0];
                            } else {
                                compilerStatus = "Checked with the Kotlin compiler";
                                currentErrors = kotlinErrors(snapshot, result);
                                editorText.setErrors(currentErrors);
                                updateErrorBadge();
                            }
                        }
                        if (kotlinCheckAgain) {
                            kotlinCheckAgain = false;
                            scheduleLiveKotlinCheck();
                        }
                    }
                });
            }
        }, "kotlin-check").start();
    }

    /** Converts line/column diagnostics to editor ranges (the identifier at the position, or the rest of the line). */
    static List<JavaAnalyzer.Err> kotlinErrors(String text, List<KotlinCompiler.Diagnostic> diagnostics) {
        List<JavaAnalyzer.Err> list = new ArrayList<JavaAnalyzer.Err>();
        for (KotlinCompiler.Diagnostic d : diagnostics) {
            int lineStart = 0;
            for (int n = 1; n < d.line && lineStart >= 0; n++) {
                int nl = text.indexOf('\n', lineStart);
                lineStart = nl < 0 ? -1 : nl + 1;
            }
            if (lineStart < 0) continue;
            int lineEnd = text.indexOf('\n', lineStart);
            if (lineEnd < 0) lineEnd = text.length();
            int start = Math.min(lineEnd, lineStart + Math.max(0, d.column - 1));
            int end = start;
            while (end < lineEnd && Character.isJavaIdentifierPart(text.charAt(end))) end++;
            if (end == start) end = Math.min(lineEnd, start + 1);
            if (end == start && start > lineStart) start--;
            JavaAnalyzer.Err err = new JavaAnalyzer.Err(start, Math.max(end, start + 1), d.message);
            err.line = d.line;
            err.warning = d.warning;
            list.add(err);
        }
        return list;
    }

    void showCompilerInfo() {
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
            .setTitle("Compiler")
            .setMessage("Build: v17 (ECJ Java 8 lambda support, AndroidX Maven/AAR library manager)\n\n" + JavaCompiler.describe(this) + "\nStatus: " + compilerStatus)
            .setPositiveButton("OK", null)
            .show();
    }

    void showErrorList() {
        setBottomMode(MODE_ERRORS);
    }

    void pickFile() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("*/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(i, REQ_PICK);
    }

    void installKotlinCompiler() {
        new android.app.AlertDialog.Builder(this)
            .setTitle("Install Kotlin compiler")
            .setMessage("Downloads the Android-port Kotlin compiler (~100 MB) from Maven Central/JitPack, converts it "
                + "to DEX on this device and installs it. Needs internet, ~1 GB free storage and several minutes. "
                + "Keep the app open and the screen on.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Install", new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int w) { runKotlinInstall(); }
            }).show();
    }

    void installKotlinFromFolder() {
        final java.io.File folder = KotlinCompilerInstaller.jarFolder();
        //noinspection ResultOfMethodCallIgnored
        folder.mkdirs();
        new android.app.AlertDialog.Builder(this)
            .setTitle("Install Kotlin compiler from folder")
            .setMessage("1. Copy the Kotlin compiler .jar files (the compiler plus its dependency jars/aars) into:\n\n"
                + folder.getAbsolutePath() + "\n\n2. Tap Install. The jars are converted to DEX on this device "
                + "and installed. It takes several minutes; keep the app open. If the app stops, tap Install again "
                + "to resume.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Install", new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int w) { runKotlinInstall(true); }
            }).show();
    }

    void runKotlinInstall() { runKotlinInstall(false); }

    void runKotlinInstall(final boolean fromFolder) {
        final android.app.ProgressDialog dialog = new android.app.ProgressDialog(this);
        dialog.setTitle("Installing Kotlin compiler");
        dialog.setMessage("Starting…");
        dialog.setCancelable(false);
        dialog.setProgressStyle(android.app.ProgressDialog.STYLE_SPINNER);
        dialog.show();
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        new Thread(new Runnable() {
            public void run() {
                String failure = null;
                try {
                    KotlinCompilerInstaller.Log progressLog = new KotlinCompilerInstaller.Log() {
                        public void update(final String m) {
                            runOnUiThread(new Runnable() { public void run() { dialog.setMessage(m); } });
                        }
                    };
                    if (fromFolder) KotlinCompilerInstaller.installFromFolder(MainActivity.this, progressLog);
                    else KotlinCompilerInstaller.install(MainActivity.this, progressLog);
                } catch (Throwable t) {
                    Throwable c = StandaloneApkBuild.unwrap(t);
                    failure = c.getClass().getSimpleName() + ": " + c.getMessage()
                        + "\n\nProgress is saved: tap Install again to resume where it stopped.";
                }
                final String error = failure;
                runOnUiThread(new Runnable() {
                    public void run() {
                        dialog.dismiss();
                        getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                        new android.app.AlertDialog.Builder(MainActivity.this)
                            .setTitle(error == null ? "Kotlin compiler installed" : "Kotlin install failed")
                            .setMessage(error == null
                                ? "You can now build Kotlin projects."
                                : error)
                            .setPositiveButton("OK", null).show();
                    }
                });
            }
        }, "kotlin-install").start();
    }

    void exportKotlinCompiler() {
        final android.app.ProgressDialog dialog = new android.app.ProgressDialog(this);
        dialog.setTitle("Exporting Kotlin compiler");
        dialog.setMessage("Copying files…");
        dialog.setCancelable(false);
        dialog.setProgressStyle(android.app.ProgressDialog.STYLE_SPINNER);
        dialog.show();
        new Thread(new Runnable() {
            public void run() {
                String text;
                boolean ok = true;
                try { text = KotlinToolchain.export(MainActivity.this); }
                catch (Throwable t) {
                    ok = false;
                    Throwable c = StandaloneApkBuild.unwrap(t);
                    text = c.getMessage() != null ? c.getMessage() : c.getClass().getName();
                }
                final String report = text;
                final boolean passed = ok;
                runOnUiThread(new Runnable() {
                    public void run() {
                        dialog.dismiss();
                        new android.app.AlertDialog.Builder(MainActivity.this)
                            .setTitle(passed ? "Kotlin compiler exported" : "Export failed")
                            .setMessage(report)
                            .setPositiveButton("OK", null).show();
                    }
                });
            }
        }, "kotlin-export").start();
    }

    void runKotlinSelfTest() {
        final android.app.ProgressDialog dialog = new android.app.ProgressDialog(this);
        dialog.setTitle("Kotlin compiler self-test");
        dialog.setMessage("Starting…");
        dialog.setCancelable(false);
        dialog.setProgressStyle(android.app.ProgressDialog.STYLE_SPINNER);
        dialog.show();
        new Thread(new Runnable() {
            public void run() {
                String text;
                boolean ok = true;
                try {
                    text = KotlinCompiler.selfTest(MainActivity.this, new KotlinCompilerInstaller.Log() {
                        public void update(final String m) {
                            runOnUiThread(new Runnable() { public void run() { dialog.setMessage(m); } });
                        }
                    });
                } catch (Throwable t) {
                    ok = false;
                    Throwable c = StandaloneApkBuild.unwrap(t);
                    text = c.getMessage() != null ? c.getMessage() : c.getClass().getName();
                }
                final String report = text;
                final boolean passed = ok;
                runOnUiThread(new Runnable() {
                    public void run() {
                        dialog.dismiss();
                        new android.app.AlertDialog.Builder(MainActivity.this)
                            .setTitle(passed ? "Kotlin compiler works" : "Kotlin compiler self-test failed")
                            .setMessage(report)
                            .setPositiveButton("OK", null).show();
                    }
                });
            }
        }, "kotlin-selftest").start();
    }

    void pickKotlinCompiler() {
        new android.app.AlertDialog.Builder(this)
            .setTitle("Import Kotlin compiler")
            .setMessage("Select an Android ART-compatible compiler DEX JAR (not a desktop kotlinc JAR). This does not download a compiler or guarantee compatibility.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Choose file", new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface dialog, int which) {
                    Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                    i.setType("*/*");
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    startActivityForResult(i, REQ_KOTLIN_COMPILER);
                }
            }).show();
    }

    void importKotlinCompiler(Uri uri) {
        try {
            java.io.File installed = KotlinOnDeviceCompiler.importCompiler(this,
                getContentResolver().openInputStream(uri));
            new android.app.AlertDialog.Builder(this).setTitle("Compiler imported")
                .setMessage("DEX archive validated and stored at " + installed.getAbsolutePath()
                    + ". Runtime compatibility still requires a Kotlin compilation test.")
                .setPositiveButton("OK", null).show();
        } catch (Exception error) {
            new android.app.AlertDialog.Builder(this).setTitle("Compiler import failed")
                .setMessage(error.getMessage()).setPositiveButton("OK", null).show();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_KOTLIN_COMPILER) {
            if (res == RESULT_OK && data != null && data.getData() != null) importKotlinCompiler(data.getData());
            return;
        }
        if (req == REQ_ALL_FILES) {
            if (hasProjectStorageAccess()) {
                prepareProjectDirectory();
                refresh();
                libraryPolling = true;
                editorHandler.removeCallbacks(autoLibraryPoll);
                editorHandler.post(autoLibraryPoll);
            } else showStorageDeniedMessage();
            return;
        }
        if (req == REQ_PICK && res == RESULT_OK && data != null) {
            Uri u = data.getData();
            try {
                currentFile = null;
                showText(u.getLastPathSegment(), u.toString(), readAll(getContentResolver().openInputStream(u)));
            } catch (Exception ex) {
                Toast.makeText(this, "Cannot open: " + ex.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }
    }

    // ---------- menus ----------

    // ---------- top (main) menu with submenus ----------

    static class M {
        String title; boolean enabled = true; boolean blue = false; M[] subs;
        M(String t, M[] s) { title = t; subs = s; }
        M off() { enabled = false; return this; }
        M blue() { blue = true; return this; }
    }
    static M m(String t, M... subs) { return new M(t, subs.length == 0 ? null : subs); }

    // Edit this tree to change/add menu entries. Item ids passed to onAction()
    // are the full path, e.g. "Find\u2026/Find Next".
    static final M[] MAIN_MENU = {
        m("View…", m("Files"), m("Errors"), m("Search Results"),
            m("Debugger"), m("LogCat"), m("File List"), m("Terminal")),
        m("GoTo"),
        m("Forward").off(),
        m("Save"),
        m("Reload from Disk"),
        m("Close File"),
        m("More…",
            m("Find…", m("Find Usages"), m("Find in File"),
                m("Find in Project"), m("GoTo Line"), m("Documentation")),
            m("Refactor…", m("Rename…"), m("Extract Method…"), m("Move…")),
            m("Code…",
                m("Organize Imports"), m("Surround with try-catch"),
                m("Outcomment Code"), m("Un-Outcomment Code"),
                m("Create Setters and Getters"), m("Create Constructor"),
                m("Extract Resources"), m("Replace Textually")),
            m("Code Helpers…", m("Autocomplete"), m("Go to Definition"), m("Comment / Uncomment"),
                m("Fix AndroidX Import…"), m("Block / Indent Guides")),
            m("Libraries…", m("Add AndroidX Activity"), m("Install Library for Import"),
                m("Add Maven Dependency…"), m("Sync Gradle Libraries"),
                m("Auto Sync Settings…"), m("Library Status")),
            m("Format…", m("Format Code"), m("Learn Code Style"), m("Configure Code Style"), m("Indent"), m("Unindent")),
            m("Project…", m("Refresh Build"), m("Build Variants"),
                m("Publish Project"), m("Close Project")),
            m("Settings"),
            m("Exit")),
    };

    final java.util.Map<Integer, String> menuPaths = new java.util.HashMap<Integer, String>();
    int menuIdCounter = 100;

    void buildMenu(android.view.Menu menu, M[] items, String prefix) {
        for (int i = 0; i < items.length; i++) {
            M it = items[i];
            String path = prefix + it.title;
            CharSequence label = it.title;
            if (it.blue) {
                android.text.SpannableString ss = new android.text.SpannableString(it.title);
                ss.setSpan(new android.text.style.ForegroundColorSpan(Color.parseColor("#29B6F6")),
                        0, ss.length(), 0);
                label = ss;
            }
            if (it.subs != null) {
                android.view.SubMenu sm = menu.addSubMenu(0, menuIdCounter++, i, label);
                buildMenu(sm, it.subs, path + "/");
            } else {
                int id = menuIdCounter++;
                menuPaths.put(id, path);
                android.view.MenuItem mi = menu.add(0, id, i, label);
                mi.setEnabled(it.enabled);
            }
        }
    }

    void toggleBlockGuides() {
        blockGuidesEnabled = !blockGuidesEnabled;
        getPreferences(MODE_PRIVATE).edit().putBoolean("block_guides", blockGuidesEnabled).apply();
        String name = currentFile != null ? currentFile.getName().toLowerCase(java.util.Locale.US) : "";
        boolean markup = name.endsWith(".xml") || name.endsWith(".html")
                || name.endsWith(".htm") || name.endsWith(".xhtml");
        editorText.configureGuides(blockGuidesEnabled, markup, 4);
        Toast.makeText(this, blockGuidesEnabled ? "Block guides on" : "Block guides off",
                Toast.LENGTH_SHORT).show();
    }

    void showMainMenu(View anchor) {
        PopupMenu pm = new PopupMenu(this, anchor);
        menuPaths.clear();
        buildMenu(pm.getMenu(), MAIN_MENU, "");
        pm.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            public boolean onMenuItemClick(android.view.MenuItem it) {
                String path = menuPaths.get(it.getItemId());
                if (path == null) return true;
                if (path.endsWith("Open File\u2026")) pickFile();      // load file
                else if (path.equals("More\u2026/Exit")) finish();
                else if (path.equals("More\u2026/Settings")) showCompilerInfo();
                else if (path.endsWith("/Find") || path.endsWith("/Find in File")) showFindBar();
                else if (path.endsWith("/Find in Project")) findInProject();
                else if (path.endsWith("/Find Usages")) findUsages();
                else if (path.endsWith("/GoTo Line") || path.equals("GoTo")) promptGoToLine();
                else if (path.endsWith("/Documentation")) openDocumentation();
                else if (path.equals("Save")) saveEditor(true);
                else if (path.equals("Reload from Disk")) reloadCurrentFileFromDisk();
                else if (path.equals("Close File")) { if (currentFile != null) closeOpenTab(currentFile); else Toast.makeText(MainActivity.this, "No file open", Toast.LENGTH_SHORT).show(); }
                else if (path.equals("View…/Files")) setBottomMode(MODE_FILES);
                else if (path.equals("View…/Errors")) setBottomMode(MODE_ERRORS);
                else if (path.equals("View…/Search Results")) setBottomMode(MODE_SEARCH);
                else if (path.equals("View…/Debugger")) setBottomMode(MODE_DEBUGGER);
                else if (path.equals("View…/LogCat")) setBottomMode(MODE_LOGCAT);
                else if (path.equals("View…/File List")) setBottomMode(MODE_FILE_LIST);
                else if (path.equals("View…/Terminal")) setBottomMode(MODE_TERMINAL);
                else if (path.equals("More\u2026/Code Helpers\u2026/Block / Indent Guides")) toggleBlockGuides();
                else if (path.equals("More\u2026/Format\u2026/Format Code")) formatCurrentCode();
                else if (path.equals("More\u2026/Format\u2026/Learn Code Style")) learnCurrentCodeStyle();
                else if (path.equals("More\u2026/Format\u2026/Configure Code Style")) configureCodeStyle();
                else if (path.equals("More\u2026/Format\u2026/Indent")) indentSelectedLines(false);
                else if (path.equals("More\u2026/Format\u2026/Unindent")) indentSelectedLines(true);
                else if (path.equals("More\u2026/Code\u2026/Organize Imports")) codeOrganizeImports();
                else if (path.equals("More\u2026/Code\u2026/Surround with try-catch")) codeSurroundTryCatch();
                else if (path.equals("More\u2026/Code\u2026/Outcomment Code")) codeLineComment(true);
                else if (path.equals("More\u2026/Code\u2026/Un-Outcomment Code")) codeLineComment(false);
                else if (path.equals("More\u2026/Code\u2026/Create Setters and Getters")) codeCreateMembers(false);
                else if (path.equals("More\u2026/Code\u2026/Create Constructor")) codeCreateMembers(true);
                else if (path.equals("More\u2026/Code\u2026/Extract Resources")) codeExtractResources();
                else if (path.equals("More\u2026/Code\u2026/Replace Textually")) codeReplaceTextually();
                else if (path.equals("More\u2026/Code Helpers\u2026/Autocomplete")) codeAutocomplete();
                else if (path.equals("More\u2026/Code Helpers\u2026/Go to Definition")) codeGoDefinition();
                else if (path.equals("More\u2026/Code Helpers\u2026/Comment / Uncomment")) codeToggleComment();
                else if (path.equals("More\u2026/Code Helpers\u2026/Fix AndroidX Import\u2026")) codeFixAndroidxImport();
                else if (path.equals("More\u2026/Libraries\u2026/Add AndroidX Activity")) addLibrary(MavenLibraries.DEFAULT_ACTIVITY);
                else if (path.equals("More\u2026/Libraries\u2026/Install Library for Import")) installImportLibrary();
                else if (path.equals("More\u2026/Libraries\u2026/Add Maven Dependency\u2026")) promptMavenDependency();
                else if (path.equals("More\u2026/Libraries\u2026/Sync Gradle Libraries")) syncProjectLibraries();
                else if (path.equals("More\u2026/Libraries\u2026/Auto Sync Settings\u2026")) showAutoSyncSettings();
                else if (path.equals("More\u2026/Libraries\u2026/Library Status")) showLibraryStatus();
                else if (path.equals("More\u2026/Project\u2026/Refresh Build")) refreshProjectBuild();
                else if (path.equals("More\u2026/Project\u2026/Build Variants")) showBuildVariants();
                else if (path.equals("More\u2026/Project\u2026/Publish Project")) showPublishProject();
                else if (path.equals("More\u2026/Project\u2026/Close Project")) closeCurrentProject();
                else if (path.equals("More\u2026/Refactor\u2026/Rename\u2026")) refactorRename();
                else if (path.equals("More\u2026/Refactor\u2026/Extract Method\u2026")) refactorExtract();
                else if (path.equals("More\u2026/Refactor\u2026/Move\u2026")) refactorMove();
                else onAction(path);
                return true;
            }
        });
        pm.show();
    }

    void showFolderMenu(View anchor) {
        PopupMenu m = new PopupMenu(this, anchor);
        if (activeProject != null && activeProject.isDirectory()) {
            m.getMenu().add(0, 8, 0, "Go to Project Root");
        }
        m.getMenu().add(0, 1, 1, "Create New File\u2026");
        m.getMenu().add(0, 6, 1, "Create New Class\u2026");
        m.getMenu().add(0, 7, 2, "Create New XML File\u2026");
        m.getMenu().add(0, 2, 2, "Create New Folder\u2026");
        if (owningProject(currentDir) == null) {
            m.getMenu().add(0, 3, 3, "Create New Project\u2026");
            m.getMenu().add(0, 4, 4, "Clone Git repository\u2026");
        }
        m.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            public boolean onMenuItemClick(android.view.MenuItem it) {
                switch (it.getItemId()) {
                    case 1: createBrowserEntry(false); break;
                    case 6: createJavaClass(); break;
                    case 7: createXmlFile(); break;
                    case 2: createBrowserEntry(true); break;
                    case 3: showCreateProjectDialog(); break;
                    case 4: onAction("clone_git"); break;
                    case 8: if (activeProject != null && activeProject.isDirectory()) navigateToDirectory(activeProject); break;
                }
                return true;
            }
        });
        m.show();
    }

    /** Create a real child file or directory in the directory currently shown by Files. */
    void createBrowserEntry(final boolean folder) {
        if (bottomMode != MODE_FILES || currentDir == null || !currentDir.isDirectory()) {
            Toast.makeText(this, "Open a writable folder in Files first", Toast.LENGTH_LONG).show();
            return;
        }
        final File parent = currentDir;
        promptText(folder ? "Create New Folder" : "Create New File",
                folder ? "Folder name" : "File name (for example, Example.java)", null,
                android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
                new TextCb() {
                    public void got(String entered) {
                        createChildEntry(parent, entered, folder);
                    }
                });
    }

    /** Create a valid XML starter document appropriate to the current resource folder. */
    void createXmlFile() {
        if (bottomMode != MODE_FILES || currentDir == null || !currentDir.isDirectory()) {
            Toast.makeText(this, "Open a writable folder in Files first", Toast.LENGTH_LONG).show();
            return;
        }
        final File folder = currentDir;
        promptText("Create New XML File", "XML filename (e.g., screen_main.xml)", null,
                android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
                new TextCb() {
                    public void got(String input) { createXmlFileAt(folder, input); }
                });
    }

    void createXmlFileAt(File folder, String input) {
        File target = null;
        boolean created = false;
        try {
            String filename = XmlFileTemplate.normalizeName(input, folder);
            target = new File(folder, filename);
            if (target.exists()) throw new java.io.IOException("File already exists: " + filename);
            String template = XmlFileTemplate.source(folder, filename);
            created = target.createNewFile();
            if (!created) throw new java.io.IOException("Could not create " + filename);
            java.io.FileOutputStream stream = new java.io.FileOutputStream(target);
            try { stream.write(template.getBytes("UTF-8")); }
            finally { stream.close(); }
            bottomMode = MODE_FILES;
            refresh();
            openFileSafely(target);
            Toast.makeText(this, "Created " + filename, Toast.LENGTH_SHORT).show();
        } catch (Exception error) {
            if (created && target != null) target.delete();
            new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
                    .setTitle("Cannot create XML file")
                    .setMessage(error.getMessage())
                    .setPositiveButton("OK", null).show();
        }
    }

    /** Kotlin when the folder (or else the module) is Kotlin, otherwise Java. */
    boolean kotlinProjectHere(File dir) {
        File module = MavenLibraries.moduleFromSource(dir);
        if (module == null) module = selectedProjectModule();
        return ProjectLanguage.isKotlin(dir, module);
    }

    /** Create a Kotlin or Java source file in the currently selected package directory. */
    void createJavaClass() {
        if (bottomMode != MODE_FILES || currentDir == null || !currentDir.isDirectory()) {
            Toast.makeText(this, "Open a source folder in Files first", Toast.LENGTH_LONG).show();
            return;
        }
        final File parent = currentDir;
        final boolean kotlin = kotlinProjectHere(parent);
        final String packageName = JavaClassTemplate.packageForFolder(parent);
        if (packageName == null) {
            new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
                .setTitle("Choose a package folder")
                .setMessage("Navigate into app/src/main/java/com/example (or src/main/kotlin/com/example) "
                        + "or src/com/example first. A class must be created under a source directory.")
                .setPositiveButton("OK", null).show();
            return;
        }
        promptText(kotlin ? "Create New Kotlin Class" : "Create New Class", "Class name (e.g., MyHelper)", null,
                android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
                new TextCb() {
                    public void got(String entered) { createClassAt(parent, packageName, entered, kotlin); }
                });
    }

    void createClassAt(File parent, String packageName, String entered, boolean kotlin) {
        String extension = kotlin ? ".kt" : ".java";
        String name = entered == null ? "" : entered.trim();
        if (name.endsWith(extension)) name = name.substring(0, name.length() - extension.length());
        boolean valid = kotlin ? JavaClassTemplate.validKotlinIdentifier(name) : JavaClassTemplate.validIdentifier(name);
        if (!valid) {
            Toast.makeText(this, "Invalid " + (kotlin ? "Kotlin" : "Java") + " class name", Toast.LENGTH_LONG).show();
            return;
        }
        File file = new File(parent, name + extension);
        if (file.exists()) {
            Toast.makeText(this, "Class already exists: " + name, Toast.LENGTH_LONG).show();
            return;
        }
        boolean created = false;
        try {
            String source = kotlin ? JavaClassTemplate.kotlinSource(name, packageName)
                                   : JavaClassTemplate.source(name, packageName);
            created = file.createNewFile();
            if (!created) throw new java.io.IOException("File already exists");
            java.io.FileOutputStream output = new java.io.FileOutputStream(file);
            try { output.write(source.getBytes("UTF-8")); }
            finally { output.close(); }
            bottomMode = MODE_FILES;
            refresh();
            openFileSafely(file);
            Toast.makeText(this, "Created " + file.getName(), Toast.LENGTH_SHORT).show();
        } catch (Exception exception) {
            if (created) file.delete();
            new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
                .setTitle("Cannot create " + (kotlin ? "Kotlin" : "Java") + " class")
                .setMessage(exception.getMessage())
                .setPositiveButton("OK", null).show();
        }
    }

    void createChildEntry(File parent, String name, boolean folder) {
        if (name == null) return;
        name = name.trim();
        // Only a single immediate child may be created. Reject separators and traversal.
        if (name.length() == 0 || ".".equals(name) || "..".equals(name)
                || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0
                || name.indexOf('\0') >= 0) {
            Toast.makeText(this, "Invalid name: enter one file or folder name", Toast.LENGTH_LONG).show();
            return;
        }
        File child = new File(parent, name);
        if (child.exists()) {
            Toast.makeText(this, "Already exists: " + name, Toast.LENGTH_LONG).show();
            return;
        }
        try {
            boolean created = folder ? child.mkdir() : child.createNewFile();
            if (!created) throw new java.io.IOException("Android did not create the entry");
            bottomMode = MODE_FILES;
            refresh();
            Toast.makeText(this, (folder ? "Folder" : "File") + " created: " + name,
                    Toast.LENGTH_SHORT).show();
            if (!folder) openFileSafely(child);
        } catch (Exception ex) {
            new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
                .setTitle(folder ? "Cannot create folder" : "Cannot create file")
                .setMessage(parent.getAbsolutePath() + "/" + name + "\n\n" + ex.getMessage()
                    + "\n\nCheck Android storage permissions or choose a writable directory.")
                .setPositiveButton("OK", null).show();
        }
    }

    /** Horizontal header swipes cycle through the lower panels (Files … File List, Terminal). */
    void initLowerHeaderSwipe() {
        LinearLayout lower = (LinearLayout) findViewById(R.id.bottomPanel);
        if (lower == null || lower.getChildCount() == 0) return;
        View header = lower.getChildAt(0);
        header.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            public boolean onTouch(View v, android.view.MotionEvent event) {
                switch (event.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        downX = event.getX();
                        downY = event.getY();
                        return true;
                    case android.view.MotionEvent.ACTION_UP:
                        float dx = event.getX() - downX;
                        float dy = event.getY() - downY;
                        if (Math.abs(dx) >= dp(55) && Math.abs(dx) > 1.4f * Math.abs(dy)) {
                            final int total = MODE_TERMINAL + 1;   // includes the Terminal panel
                            // Swipe left: next panel; swipe right: previous panel.
                            int next = (bottomMode + (dx < 0 ? 1 : total - 1)) % total;
                            setBottomMode(next);
                            return true;
                        }
                        return true;
                    case android.view.MotionEvent.ACTION_CANCEL:
                        return true;
                }
                return true;
            }
        });
    }

    // ---------- draggable splitter between editor and file panel ----------

    /** Drag to resize; dragging the blue line near the bottom collapses the browser. */
    void initSplitter() {
        final View frame = findViewById(R.id.editorFrame);
        final View splitter = findViewById(R.id.splitter);
        splitter.setOnTouchListener(new View.OnTouchListener() {
            float downY;
            float originalFraction;
            int startFrameHeight;
            int availableHeight;
            boolean dragging;
            public boolean onTouch(View v, android.view.MotionEvent ev) {
                if (bottomCollapsed) return false;
                switch (ev.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        downY = ev.getRawY();
                        originalFraction = editorPaneFraction;
                        startFrameHeight = frame.getHeight();
                        availableHeight = Math.max(1, startFrameHeight + bottomPanel.getHeight());
                        dragging = true;
                        v.getParent().requestDisallowInterceptTouchEvent(true);
                        return true;
                    case android.view.MotionEvent.ACTION_MOVE:
                    case android.view.MotionEvent.ACTION_UP: {
                        if (!dragging) return false;
                        final float desired = startFrameHeight + (ev.getRawY() - downY);
                        // The file browser should close when the divider is dragged
                        // to its lower edge, including when the keyboard is visible.
                        // Use the TOTAL available height, not the screen height.
                        final int collapseThreshold = Math.min(dp(78), availableHeight / 4);
                        final boolean shouldClose = PaneSplit.shouldCollapse(desired, availableHeight, collapseThreshold);
                        if (ev.getActionMasked() == android.view.MotionEvent.ACTION_UP && shouldClose) {
                            // Opening with the blue button should restore a usable split,
                            // not the almost-zero browser height seen at the drag endpoint.
                            editorPaneFraction = originalFraction;
                            setBottomCollapsed(true);
                            dragging = false;
                            return true;
                        }
                        if (shouldClose) {
                            // While the finger is still down, show the browser shrinking
                            // toward zero. Complete the collapse on finger release.
                            editorPaneFraction = PaneSplit.fraction(availableHeight - dp(12),
                                    availableHeight, dp(72), dp(8));
                        } else {
                            editorPaneFraction = PaneSplit.fraction(desired,
                                    availableHeight, dp(72), dp(28));
                        }
                        applyPaneWeights();
                        if (ev.getActionMasked() == android.view.MotionEvent.ACTION_UP) dragging = false;
                        return true;
                    }
                    case android.view.MotionEvent.ACTION_CANCEL:
                        dragging = false;
                        editorPaneFraction = originalFraction;
                        applyPaneWeights();
                        return true;
                }
                return false;
            }
        });
        applyPaneWeights();
    }

    void applyPaneWeights() {
        final View frame = findViewById(R.id.editorFrame);
        if (frame == null || bottomPanel == null) return;
        LinearLayout.LayoutParams editorLp = (LinearLayout.LayoutParams) frame.getLayoutParams();
        LinearLayout.LayoutParams filesLp = (LinearLayout.LayoutParams) bottomPanel.getLayoutParams();
        editorLp.height = 0;
        filesLp.height = 0;
        editorLp.weight = bottomCollapsed ? 1f : editorPaneFraction;
        filesLp.weight = bottomCollapsed ? 0f : 1f - editorPaneFraction;
        frame.setLayoutParams(editorLp);
        bottomPanel.setLayoutParams(filesLp);
    }

    // ---------- FIND ----------

    interface TextCb { void got(String s); }

    boolean editorOpen() { return editorScroll.getVisibility() == View.VISIBLE; }

    void promptText(String title, String hint, String initial, int inputType, final TextCb cb) {
        final EditText et = new EditText(this);
        et.setHint(hint);
        et.setInputType(inputType);
        et.setSingleLine(true);
        if (initial != null) { et.setText(initial); et.setSelection(initial.length()); }
        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this,
            android.R.style.Theme_Material_Light_Dialog_Alert);
        b.setTitle(title).setView(et);
        b.setNegativeButton("Cancel", null);
        b.setPositiveButton("OK", new android.content.DialogInterface.OnClickListener() {
            public void onClick(android.content.DialogInterface d, int w) {
                String s = et.getText().toString().trim();
                if (s.length() > 0) cb.got(s);
            }
        });
        android.app.AlertDialog dlg = b.create();
        dlg.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        dlg.show();
    }

    String selectedText() {
        if (!editorOpen()) return "";
        int s = editorText.getSelectionStart(), e = editorText.getSelectionEnd();
        if (s < 0 || e <= s) return "";
        return editorText.getText().toString().substring(s, e).trim();
    }

    void initFindBar() {
        findBar = findViewById(R.id.findBar);
        findInput = (EditText) findViewById(R.id.findInput);
        findCount = (TextView) findViewById(R.id.findCount);
        findCase = (TextView) findViewById(R.id.findCase);
        findInput.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence c, int a, int b, int d) { }
            public void onTextChanged(CharSequence c, int a, int b, int d) { }
            public void afterTextChanged(Editable e) { runFind(); }
        });
        findInput.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            public boolean onEditorAction(TextView v, int id, android.view.KeyEvent ev) { stepFind(1); return true; }
        });
        findViewById(R.id.findNext).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { stepFind(1); }
        });
        findViewById(R.id.findPrev).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { stepFind(-1); }
        });
        findViewById(R.id.findClose).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { closeFindBar(); }
        });
        findCase.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                caseSensitive = !caseSensitive;
                findCase.setTextColor(Color.parseColor(caseSensitive ? "#1E88E5" : "#888888"));
                findCase.setTypeface(null, caseSensitive ? Typeface.BOLD : Typeface.NORMAL);
                runFind();
            }
        });
    }

    void showFindBar() {
        if (!editorOpen()) { Toast.makeText(this, "Open a file first", Toast.LENGTH_SHORT).show(); return; }
        String sel = selectedText();
        findBar.setVisibility(View.VISIBLE);
        if (sel.length() > 0 && sel.indexOf('\n') < 0) findInput.setText(sel);
        findInput.requestFocus();
        findInput.setSelection(findInput.getText().length());
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.showSoftInput(findInput, 0);
        runFind();
    }

    void closeFindBar() {
        if (findBar == null) return;
        clearFindSpans();
        findMatches.clear();
        findBar.setVisibility(View.GONE);
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(findInput.getWindowToken(), 0);
    }

    void clearFindSpans() {
        CharSequence t = editorText.getText();
        if (t instanceof Spannable) {
            Spannable sp = (Spannable) t;
            for (Object o : findSpans) sp.removeSpan(o);
            if (curSpan != null) sp.removeSpan(curSpan);
            if (lineSpan != null) sp.removeSpan(lineSpan);
        }
        findSpans.clear(); curSpan = null; lineSpan = null;
    }

    void runFind() {
        clearFindSpans();
        findMatches.clear();
        findIndex = -1;
        String q = findInput.getText().toString();
        if (q.length() > 0 && editorOpen() && editorText.getText() instanceof Spannable) {
            Spannable sp = (Spannable) editorText.getText();
            String hay = sp.toString();
            int m = q.length();
            for (int i = 0; i + m <= hay.length() && findMatches.size() < 5000; ) {
                if (hay.regionMatches(!caseSensitive, i, q, 0, m)) {
                    findMatches.add(new int[]{i, i + m});
                    if (findSpans.size() < 2000) {
                        Object o = new BackgroundColorSpan(Color.parseColor("#FFF59D"));
                        sp.setSpan(o, i, i + m, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                        findSpans.add(o);
                    }
                    i += m;
                } else i++;
            }
        }
        if (findMatches.isEmpty()) findCount.setText("0/0");
        else gotoMatch(0);
    }

    void stepFind(int d) {
        if (findMatches.isEmpty()) return;
        int n = findMatches.size();
        gotoMatch(((findIndex + d) % n + n) % n);
    }

    void gotoMatch(int i) {
        findIndex = i;
        int[] mt = findMatches.get(i);
        if (editorText.getText() instanceof Spannable) {
            Spannable sp = (Spannable) editorText.getText();
            if (curSpan != null) sp.removeSpan(curSpan);
            curSpan = new BackgroundColorSpan(Color.parseColor("#FFB74D"));
            sp.setSpan(curSpan, mt[0], mt[1], Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        findCount.setText((i + 1) + "/" + findMatches.size());
        scrollToOffset(mt[0]);
    }

    void scrollToOffset(final int off) {
        editorText.post(new Runnable() {
            public void run() {
                Layout l = editorText.getLayout();
                if (l == null || off > editorText.getText().length()) return;
                int line = l.getLineForOffset(off);
                int y = l.getLineTop(line) + editorText.getPaddingTop();
                editorScroll.smoothScrollTo(0, Math.max(0, y - editorScroll.getHeight() / 3));
                View parent = (View) editorText.getParent();
                if (parent instanceof HorizontalScrollView) {
                    HorizontalScrollView h = (HorizontalScrollView) parent;
                    int x = (int) l.getPrimaryHorizontal(off);
                    h.smoothScrollTo(Math.max(0, x - h.getWidth() / 3), 0);
                }
            }
        });
    }

    // ----- GoTo Line -----

    void promptGoToLine() {
        if (!editorOpen()) { Toast.makeText(this, "Open a file first", Toast.LENGTH_SHORT).show(); return; }
        promptText("GoTo Line", "Line number", null, android.text.InputType.TYPE_CLASS_NUMBER, new TextCb() {
            public void got(String s) {
                try { goToLine(Integer.parseInt(s)); } catch (Exception e) { }
            }
        });
    }

    void goToLine(int line) {
        if (!editorOpen()) return;
        String t = editorText.getText().toString();
        int off = 0, cur = 1;
        while (cur < line) {
            int nl = t.indexOf('\n', off);
            if (nl < 0) break;
            off = nl + 1; cur++;
        }
        int end = t.indexOf('\n', off);
        if (end < 0) end = t.length();
        if (editorText.getText() instanceof Spannable && end > off) {
            Spannable sp = (Spannable) editorText.getText();
            if (lineSpan != null) sp.removeSpan(lineSpan);
            lineSpan = new BackgroundColorSpan(Color.parseColor("#BBDEFB"));
            sp.setSpan(lineSpan, off, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        scrollToOffset(off);
    }

    void openFileAt(final File f, final int line) {
        guardedNavigation(new Runnable() { public void run() {
            openFile(f);
            editorText.post(new Runnable() { public void run() { goToLine(line); } });
        }});
    }

    // ----- Find in Project / Find Usages -----

    void findInProject() {
        promptText("Find in Project", "Text to find", selectedText(),
            android.text.InputType.TYPE_CLASS_TEXT, new TextCb() {
                public void got(String s) { searchProject(s, false); }
            });
    }

    void findUsages() {
        String w = selectedText();
        if (w.length() > 0 && w.matches("[A-Za-z_$][A-Za-z0-9_$]*")) { searchProject(w, true); return; }
        promptText("Find Usages", "Symbol name", null, android.text.InputType.TYPE_CLASS_TEXT, new TextCb() {
            public void got(String s) { searchProject(s, true); }
        });
    }

    File projectRoot() {
        if (activeProject != null) return activeProject;
        File start = currentFile != null ? currentFile.getParentFile() : currentDir;
        for (File d = start; d != null; d = d.getParentFile()) {
            if (new File(d, "settings.gradle").exists() || new File(d, "settings.gradle.kts").exists()
                    || new File(d, ".git").exists()
                    || ((new File(d, "build.gradle").exists() || new File(d, "build.gradle.kts").exists())
                        && new File(d, "app").isDirectory())) return d;
        }
        return currentDir;
    }

    static final String[] TEXT_EXT = {"java", "kt", "kts", "xml", "gradle", "txt", "properties", "md", "json",
        "html", "js", "css", "c", "cpp", "h", "cmake", "mk", "pro", "yml", "yaml", "sh", "aidl", "smali"};

    static boolean isIdent(char c) { return Character.isLetterOrDigit(c) || c == '_' || c == '$'; }

    void searchProject(final String q, final boolean whole) {
        final File root = projectRoot();
        Toast.makeText(this, "Searching " + root.getName() + "\u2026", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            public void run() {
                final List<String> labels = new ArrayList<String>();
                final List<File> files = new ArrayList<File>();
                final List<Integer> lines = new ArrayList<Integer>();
                walk(root, root, q, whole, labels, files, lines);
                runOnUiThread(new Runnable() {
                    public void run() { showResults(q, labels, files, lines); }
                });
            }
        }).start();
    }

    void walk(File root, File dir, String q, boolean whole,
              List<String> labels, List<File> files, List<Integer> lines) {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        Arrays.sort(fs);
        for (File f : fs) {
            if (labels.size() >= 500) return;
            String n = f.getName();
            if (f.isDirectory()) {
                if (n.equals("build") || n.startsWith(".")  || n.equals("node_modules")) continue;
                walk(root, f, q, whole, labels, files, lines);
            } else if (f.length() < 1024 * 1024) {
                int dot = n.lastIndexOf('.');
                String ext = dot < 0 ? "" : n.substring(dot + 1).toLowerCase();
                if (!Arrays.asList(TEXT_EXT).contains(ext)) continue;
                try {
                    java.io.BufferedReader br = new java.io.BufferedReader(
                        new java.io.InputStreamReader(new FileInputStream(f), "UTF-8"));
                    String ln; int no = 0;
                    while ((ln = br.readLine()) != null && labels.size() < 500) {
                        no++;
                        int idx = 0, m = q.length();
                        while ((idx = indexOfIgnoreCase(ln, q, idx)) >= 0) {
                            boolean ok = !whole
                                || ((idx == 0 || !isIdent(ln.charAt(idx - 1)))
                                    && (idx + m >= ln.length() || !isIdent(ln.charAt(idx + m))));
                            if (ok) {
                                String rel = f.getAbsolutePath().substring(root.getAbsolutePath().length() + 1);
                                labels.add(rel + ":" + no + "\n" + ln.trim());
                                files.add(f); lines.add(no);
                                break;
                            }
                            idx += m;
                        }
                    }
                    br.close();
                } catch (Exception e) { }
            }
        }
    }

    static int indexOfIgnoreCase(String s, String q, int from) {
        for (int i = from; i + q.length() <= s.length(); i++)
            if (s.regionMatches(true, i, q, 0, q.length())) return i;
        return -1;
    }

    void showResults(String q, List<String> labels, final List<File> files, final List<Integer> lines) {
        searchResultLabels.clear();
        searchResultFiles.clear();
        searchResultLines.clear();
        lastSearchQuery = q;
        searchResultLabels.addAll(labels);
        searchResultFiles.addAll(files);
        searchResultLines.addAll(lines);
        if (labels.isEmpty()) {
            setBottomMode(MODE_SEARCH);
            Toast.makeText(this, "No results for '" + q + "'", Toast.LENGTH_SHORT).show();
            return;
        }
        setBottomMode(MODE_SEARCH);
        Toast.makeText(this, labels.size() + (labels.size() >= 500 ? "+" : "") + " result(s): " + q, Toast.LENGTH_SHORT).show();
    }

    // ----- Documentation -----

    void openDocumentation() {
        String w = selectedText();
        if (w.length() > 0 && w.matches("[A-Za-z_$][A-Za-z0-9_$]*")) { openDocFor(w); return; }
        promptText("Documentation", "Class name, e.g. Activity", null,
            android.text.InputType.TYPE_CLASS_TEXT, new TextCb() {
                public void got(String s) { openDocFor(s); }
            });
    }

    void openDocFor(String word) {
        String url = null;
        if (editorOpen()) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("import\\s+([\\w.]+)\\." + java.util.regex.Pattern.quote(word) + "\\s*;")
                .matcher(editorText.getText().toString());
            if (m.find()) url = "https://developer.android.com/reference/" + m.group(1).replace('.', '/') + "/" + word;
        }
        if (url == null) url = "https://developer.android.com/s/results?q=" + Uri.encode(word);
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
        catch (Exception e) { Toast.makeText(this, "No browser found", Toast.LENGTH_SHORT).show(); }
    }


    // ---------- editor helpers ----------

    void initSymbolBar() {
        if (symbolBar == null) return;
        symbolBar.removeAllViews();
        final String[] symbols = {"	", "{", "}", "(", ")", ";", ",", ".", "=", "\"", "|", "&", "!", "[", "]", "<", ">", "/", "*", "+", "-"};
        for (int i = 0; i < symbols.length; i++) {
            final String value = symbols[i];
            TextView tv = new TextView(this);
            tv.setText("	".equals(value) ? "⇥" : value);
            tv.setTextSize(20f);
            tv.setGravity(Gravity.CENTER);
            tv.setPadding(dp(12), dp(10), dp(12), dp(10));
            tv.setTextColor(Color.parseColor("#333333"));
            tv.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { insertSymbol(value); }
            });
            symbolBar.addView(tv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }

    void insertSymbol(String value) {
        if (!editorOpen()) return;
        int start = Math.max(0, editorText.getSelectionStart());
        int end = Math.max(0, editorText.getSelectionEnd());
        if (start > end) { int t = start; start = end; end = t; }
        String put = "	".equals(value) ? "    " : value;
        editorText.getText().replace(start, end, put);
        editorText.setSelection(start + put.length());
        updateUndoRedoButtons();
    }

    void toggleBottomPanel() {
        setBottomCollapsed(!bottomCollapsed);
    }

    /** Shared by the blue toggle and the drag-to-close gesture. */
    void setBottomCollapsed(boolean collapsed) {
        if (bottomPanel == null) return;
        bottomCollapsed = collapsed;
        bottomPanel.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        View splitter = findViewById(R.id.splitter);
        if (splitter != null) splitter.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        // Reset fixed heights and let the editor's layout weight occupy the freed
        // space. This keeps the symbol bar at the bottom above the keyboard.
        applyPaneWeights();
        btnToggleBottom.setText(collapsed ? "˄" : "˅");
        btnToggleBottom.setContentDescription(collapsed ? "Show file browser" : "Hide file browser");
    }

    void updateUndoRedoButtons() {
        if (btnUndo != null) btnUndo.setAlpha(editorText != null && editorText.canUndo() ? 1f : 0.35f);
        if (btnRedo != null) btnRedo.setAlpha(editorText != null && editorText.canRedo() ? 1f : 0.35f);
    }

    void afterProgrammaticEdit() {
        editorDirty = !editorText.getText().toString().equals(editorSaved);
        updateEditorTitle();
        updateOpenedTabs();
        updateLineNumbers(editorText.getText().toString());
        refreshJavaHighlight();
        updateUndoRedoButtons();
    }

    /** Build SDK + downloaded dependency class list once per module, off the UI thread. */
    void refreshCompletionCatalog(boolean force) {
        File module = currentFile == null ? null : MavenLibraries.moduleFromSource(currentFile);
        final String key = module == null ? "(platform)" : module.getAbsolutePath();
        if (completionIndexRunning) return;
        if (!force && key.equals(indexedModule) && CompletionCatalog.isReady()) return;
        completionIndexRunning = true;
        final File moduleDir = module;
        new Thread(new Runnable() {
            public void run() {
                int count = 0;
                try {
                    List<File> extras = new ArrayList<File>();
                    if (moduleDir != null) {
                        extras.addAll(MavenLibraries.classpath(moduleDir));
                        File libs = new File(moduleDir, "libs");
                        File[] files = libs.listFiles();
                        if (files != null) for (File lib : files) {
                            if (lib.isFile() && lib.getName().endsWith(".jar")) extras.add(lib);
                        }
                    }
                    File kotlinStdlib = KotlinToolchain.installedStdlib(MainActivity.this);
                    if (kotlinStdlib.isFile()) extras.add(kotlinStdlib);
                    java.io.InputStream jar = getAssets().open("android.jar");
                    count = CompletionCatalog.load(jar, extras);
                } catch (Exception ignored) { }
                final int number = count;
                runOnUiThread(new Runnable() {
                    public void run() {
                        completionIndexRunning = false;
                        if (number > 0) { indexedModule = key; completionIndexedClasses = number; }
                        File nowModule = currentFile == null ? null : MavenLibraries.moduleFromSource(currentFile);
                        String nowKey = nowModule == null ? "(platform)" : nowModule.getAbsolutePath();
                        if (!key.equals(nowKey)) { refreshCompletionCatalog(false); return; }
                        if (needsJavaEditorSilent() && editorText.hasFocus()) {
                            String text = editorText.getText().toString();
                            int at = Math.max(0, editorText.getSelectionStart());
                            if (at > 0 && text.charAt(at - 1) == '.') maybeShowCompletionPopup(false);
                        }
                    }
                });
            }
        }, "sdk-completion-index").start();
    }

    /** Popup is positioned at the caret, not below the entire scrolling EditText. */
    void initCompletionPopup() {
        completionList = new ListView(this);
        completionList.setDividerHeight(1);
        completionList.setBackgroundColor(Color.WHITE);
        completionList.setCacheColorHint(Color.TRANSPARENT);
        completionAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1,
                new ArrayList<String>());
        completionList.setAdapter(completionAdapter);
        completionPopup = new PopupWindow(completionList, dp(282), dp(220), false);
        completionPopup.setTouchable(true);
        completionPopup.setOutsideTouchable(false);
        completionPopup.setInputMethodMode(PopupWindow.INPUT_METHOD_NEEDED);
        completionPopup.setClippingEnabled(true);
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.WHITE);
        background.setCornerRadius(dp(6));
        background.setStroke(dp(1), Color.parseColor("#C7CED3"));
        completionPopup.setBackgroundDrawable(background);
        if (Build.VERSION.SDK_INT >= 21) completionPopup.setElevation(dp(6));
        completionList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= activeCompletions.size()) return;
                CodeActions.Completion c = activeCompletions.get(position);
                int len = editorText.length();
                if (c.start < 0 || c.end > len || c.start > c.end) return;
                editorText.getText().replace(c.start, c.end, c.label);
                editorText.setSelection(c.start + c.label.length());
                completionPopup.dismiss();
                editorText.requestFocus();
                afterProgrammaticEdit();
            }
        });
    }

    void maybeShowCompletionPopup(boolean explicit) {
        if (!needsJavaEditorSilent() || editorLoading || completionPopup == null) {
            if (completionPopup != null) completionPopup.dismiss();
            return;
        }
        final String source = editorText.getText().toString();
        int a = editorText.getSelectionStart(), b = editorText.getSelectionEnd();
        if (a < 0 || b < 0 || a != b) { completionPopup.dismiss(); return; }
        if (source.length() > 250000) { completionPopup.dismiss(); return; }
        // Dismiss for whitespace, strings and comments; only trigger on a Java identifier or a dot.
        if (!explicit) {
            if (a <= 0 || !(Character.isJavaIdentifierPart(source.charAt(a - 1)) || source.charAt(a - 1) == '.')) {
                completionPopup.dismiss(); return;
            }
            boolean[] code = RefactorEngine.codeMask(source);
            if (a - 1 >= code.length || !code[a - 1]) { completionPopup.dismiss(); return; }
        }
        List<CodeActions.Completion> choices;
        if (ResourceCompletions.matches(source, a)) {
            choices = ResourceCompletions.complete(currentFile, source, a);
        } else {
            boolean kotlin = currentFileIsKotlin();
            choices = CodeActions.completions(source, a, b,
                    CompletionCatalog.importedNames(source, kotlin), kotlin);
        }
        activeCompletions.clear();
        completionAdapter.clear();
        for (int i = 0; i < choices.size() && i < 80; i++) {
            CodeActions.Completion c = choices.get(i);
            activeCompletions.add(c);
            completionAdapter.add(c.label);
        }
        if (activeCompletions.isEmpty()) {
            completionPopup.dismiss();
            if (explicit) Toast.makeText(this, "No completions for cursor prefix", Toast.LENGTH_SHORT).show();
            return;
        }
        completionAdapter.notifyDataSetChanged();
        showCompletionAtCaret(a);
    }

    void showCompletionAtCaret(int offset) {
        Layout layout = editorText.getLayout();
        if (layout == null) return;
        Rect visible = new Rect();
        getWindow().getDecorView().getWindowVisibleDisplayFrame(visible);
        Rect editorVisible = new Rect();
        if (!editorText.getGlobalVisibleRect(editorVisible)) return;
        int[] location = new int[2];
        editorText.getLocationOnScreen(location);
        int pos = Math.min(Math.max(0, offset), editorText.length());
        int line = layout.getLineForOffset(pos);
        int caretX = location[0] + editorText.getTotalPaddingLeft()
                + (int) layout.getPrimaryHorizontal(pos);
        int caretTop = location[1] + editorText.getTotalPaddingTop() + layout.getLineTop(line);
        int caretBottom = location[1] + editorText.getTotalPaddingTop() + layout.getLineBottom(line);
        if (caretBottom < editorVisible.top || caretTop > editorVisible.bottom) {
            completionPopup.dismiss(); return;
        }
        int availableWidth = visible.width() - dp(12);
        int width = Math.max(dp(130), Math.min(dp(300), availableWidth));
        int desiredHeight = Math.min(dp(248), activeCompletions.size() * dp(48) + dp(3));
        int below = visible.bottom - caretBottom - dp(8);
        int above = caretTop - visible.top - dp(8);
        boolean placeBelow = below >= dp(110) || below >= above;
        int room = placeBelow ? below : above;
        int height = Math.min(desiredHeight, Math.max(dp(40), room));
        if (room < dp(40)) { completionPopup.dismiss(); return; }
        int x = Math.max(visible.left + dp(5),
                Math.min(caretX, visible.right - width - dp(5)));
        int y = placeBelow ? caretBottom + dp(2) : caretTop - height - dp(2);
        y = Math.max(visible.top + dp(2), Math.min(y, visible.bottom - height - dp(2)));
        // showAtLocation is relative to the activity's window rather than the full display.
        int[] decor = new int[2];
        getWindow().getDecorView().getLocationOnScreen(decor);
        int windowX = x - decor[0];
        int windowY = y - decor[1];
        if (completionPopup.isShowing()) completionPopup.update(windowX, windowY, width, height);
        else {
            completionPopup.setWidth(width);
            completionPopup.setHeight(height);
            completionPopup.showAtLocation(getWindow().getDecorView(), Gravity.TOP | Gravity.LEFT,
                    windowX, windowY);
        }
    }

    boolean needsJavaEditorSilent() {
        return currentFile != null && (currentFile.getName().endsWith(".java")
            || currentFile.getName().endsWith(".kt")) && editorOpen();
    }

    boolean currentFileIsKotlin() {
        return currentFile != null && currentFile.getName().endsWith(".kt");
    }

    void showEntryPopup(View anchor, final Entry e) {
        if (e == null || e.file == null) return;
        PopupMenu pm = new PopupMenu(this, anchor);
        pm.getMenu().add(0, 1, 0, "Rename...");
        pm.getMenu().add(0, 2, 1, "Delete");
        pm.getMenu().add(0, 3, 2, "Copy Path");
        if (e.type == FILE) pm.getMenu().add(0, 4, 3, "Open");
        pm.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            public boolean onMenuItemClick(android.view.MenuItem item) {
                if (item.getItemId() == 1) renameEntry(e.file);
                else if (item.getItemId() == 2) deleteEntry(e.file);
                else if (item.getItemId() == 3) copyPath(e.file);
                else if (item.getItemId() == 4) openFileSafely(e.file);
                return true;
            }
        });
        pm.show();
    }

    void renameEntry(final File file) {
        promptText("Rename", "New name", file.getName(), android.text.InputType.TYPE_CLASS_TEXT, new TextCb() {
            public void got(String s) {
                File dest = new File(file.getParentFile(), s);
                if (dest.exists()) { Toast.makeText(MainActivity.this, "Name already exists", Toast.LENGTH_SHORT).show(); return; }
                if (file.renameTo(dest)) { refresh(); Toast.makeText(MainActivity.this, "Renamed", Toast.LENGTH_SHORT).show(); }
                else Toast.makeText(MainActivity.this, "Rename failed", Toast.LENGTH_SHORT).show();
            }
        });
    }

    void deleteEntry(final File file) {
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
            .setTitle("Delete")
            .setMessage("Delete " + file.getName() + "?")
            .setPositiveButton("Delete", new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int which) {
                    if (deleteRecursive(file)) { refresh(); Toast.makeText(MainActivity.this, "Deleted", Toast.LENGTH_SHORT).show(); }
                    else Toast.makeText(MainActivity.this, "Delete failed", Toast.LENGTH_SHORT).show();
                }
            }).setNegativeButton("Cancel", null).show();
    }

    boolean deleteRecursive(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (int i = 0; i < children.length; i++) if (!deleteRecursive(children[i])) return false;
        }
        return file.delete();
    }

    void copyPath(File file) {
        android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(android.content.ClipData.newPlainText("path", file.getAbsolutePath()));
        Toast.makeText(this, "Path copied", Toast.LENGTH_SHORT).show();
    }

    // ---------- Java formatting (v22) ----------

    CodeFormatter.Style currentCodeStyle() {
        android.content.SharedPreferences p = getPreferences(MODE_PRIVATE);
        CodeFormatter.Style style = new CodeFormatter.Style();
        style.indentSize = p.getInt("format_indent", 4);
        if (style.indentSize != 2 && style.indentSize != 4 && style.indentSize != 8)
            style.indentSize = 4;
        style.tabs = p.getBoolean("format_tabs", false);
        style.braceNextLine = p.getBoolean("format_brace_next", false);
        style.spaceAfterComma = p.getBoolean("format_comma_space", true);
        style.spaceAroundAssignment = p.getBoolean("format_assignment_space", true);
        style.maxBlankLines = p.getInt("format_blank_lines", 2);
        return style;
    }

    void rememberCodeStyle(CodeFormatter.Style s) {
        getPreferences(MODE_PRIVATE).edit()
            .putInt("format_indent", s.indentSize)
            .putBoolean("format_tabs", s.tabs)
            .putBoolean("format_brace_next", s.braceNextLine)
            .putBoolean("format_comma_space", s.spaceAfterComma)
            .putBoolean("format_assignment_space", s.spaceAroundAssignment)
            .putInt("format_blank_lines", s.maxBlankLines)
            .apply();
        String name = currentFile == null ? "" : currentFile.getName().toLowerCase(java.util.Locale.US);
        editorText.configureGuides(blockGuidesEnabled,
            name.endsWith(".xml") || name.endsWith(".html") || name.endsWith(".htm") || name.endsWith(".xhtml"),
            s.indentSize);
    }

    boolean formatterReady() {
        if (editorScroll.getVisibility() != View.VISIBLE || currentFile == null
                || !(currentFile.getName().toLowerCase(java.util.Locale.US).endsWith(".java")
                     || currentFile.getName().toLowerCase(java.util.Locale.US).endsWith(".kt"))) {
            Toast.makeText(this, "Open a Java or Kotlin file before formatting", Toast.LENGTH_LONG).show();
            return false;
        }
        return true;
    }

    void replaceEditorBuffer(String oldCode, String newCode, int selectionStart, int selectionEnd) {
        if (oldCode.equals(newCode)) {
            Toast.makeText(this, "Code already matches the selected style", Toast.LENGTH_SHORT).show();
            return;
        }
        // One replacement = one Undo step. Do not save automatically or make backup files.
        if (completionPopup != null && completionPopup.isShowing()) completionPopup.dismiss();
        applyingFormatEdit = true;
        try { editorText.getText().replace(0, editorText.length(), newCode); }
        finally { applyingFormatEdit = false; }
        int caret = Math.min(newCode.length(), Math.max(0, selectionStart));
        if (selectionEnd > selectionStart) editorText.setSelection(caret, Math.min(newCode.length(), Math.max(caret, selectionEnd)));
        else editorText.setSelection(caret);
        updateUndoRedoButtons();
        Toast.makeText(this, "Formatted in editor. Use Undo to revert or Save to keep it.", Toast.LENGTH_LONG).show();
    }

    void formatCurrentCode() {
        if (!formatterReady()) return;
        String code = editorText.getText().toString();
        int a = editorText.refactorStart(), b = editorText.refactorEnd();
        boolean selected = a >= 0 && b > a && b <= code.length();
        if (!selected) { a = -1; b = -1; }
        try {
            String formatted = CodeFormatter.format(code, currentCodeStyle(), a, b, currentFileIsKotlin());
            replaceEditorBuffer(code, formatted, selected ? a : editorText.codeCaret(), selected ? b : editorText.codeCaret());
        } catch (RuntimeException error) {
            new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
                .setTitle("Cannot format")
                .setMessage(error.getMessage() == null ? error.toString() : error.getMessage())
                .setPositiveButton("OK", null).show();
        }
    }

    void indentSelectedLines(boolean unindent) {
        if (!formatterReady()) return;
        String code = editorText.getText().toString();
        int a = editorText.refactorStart(), b = editorText.refactorEnd();
        if (a < 0 || b <= a) {
            a = Math.max(0, editorText.codeCaret());
            b = a;
        }
        CodeFormatter.Edit e = CodeFormatter.indent(code, a, b, currentCodeStyle(), unindent);
        if (code.equals(e.text)) {
            Toast.makeText(this, "No indentation change needed", Toast.LENGTH_SHORT).show();
            return;
        }
        applyingFormatEdit = true;
        try { editorText.getText().replace(0, editorText.length(), e.text); }
        finally { applyingFormatEdit = false; }
        editorText.setSelection(Math.min(editorText.length(), e.start), Math.min(editorText.length(), e.end));
        updateUndoRedoButtons();
    }

    void learnCurrentCodeStyle() {
        if (!formatterReady()) return;
        final CodeFormatter.Style inferred = CodeFormatter.learn(editorText.getText().toString());
        String details = "Indentation: " + (inferred.tabs ? "Tabs" : inferred.indentSize + " spaces")
            + "\nOpening braces: " + (inferred.braceNextLine ? "On the next line" : "Same line")
            + "\n\nApply these settings to future Format Code actions?"
            + "\nThe source file will not change until you run Format Code.";
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
            .setTitle("Learn Code Style")
            .setMessage(details)
            .setPositiveButton("Use Learned Style", new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int which) {
                    CodeFormatter.Style now = currentCodeStyle();
                    now.indentSize = inferred.indentSize;
                    now.tabs = inferred.tabs;
                    now.braceNextLine = inferred.braceNextLine;
                    rememberCodeStyle(now);
                    Toast.makeText(MainActivity.this, "Learned style saved", Toast.LENGTH_SHORT).show();
                }
            }).setNegativeButton("Cancel", null).show();
    }

    void configureCodeStyle() {
        final CodeFormatter.Style original = currentCodeStyle();
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(22), dp(8), dp(22), dp(8));
        scroll.addView(panel);

        TextView label = new TextView(this);
        label.setText("Indentation");
        label.setTextColor(Color.DKGRAY);
        label.setTextSize(16);
        panel.addView(label);
        final RadioGroup indent = new RadioGroup(this);
        final int[] ids = {2522, 2524, 2528, 2529};
        final String[] options = {"2 spaces", "4 spaces", "8 spaces", "Tabs"};
        for (int i=0; i<options.length; i++) {
            RadioButton item = new RadioButton(this);
            item.setId(ids[i]); item.setText(options[i]); indent.addView(item);
        }
        indent.check(original.tabs ? ids[3] : original.indentSize == 2 ? ids[0] : original.indentSize == 8 ? ids[2] : ids[1]);
        panel.addView(indent);

        TextView bracesLabel = new TextView(this);
        bracesLabel.setText("Opening brace placement");
        bracesLabel.setTextColor(Color.DKGRAY);
        bracesLabel.setTextSize(16);
        bracesLabel.setPadding(0, dp(10), 0, 0);
        panel.addView(bracesLabel);
        final RadioGroup braces = new RadioGroup(this);
        RadioButton same = new RadioButton(this);
        same.setId(2531); same.setText("Same line: if (...) {"); braces.addView(same);
        RadioButton next = new RadioButton(this);
        next.setId(2532); next.setText("Next line: { on its own line"); braces.addView(next);
        braces.check(original.braceNextLine ? 2532 : 2531);
        panel.addView(braces);

        final android.widget.CheckBox comma = new android.widget.CheckBox(this);
        comma.setText("Space after commas"); comma.setChecked(original.spaceAfterComma);
        panel.addView(comma);
        final android.widget.CheckBox assignment = new android.widget.CheckBox(this);
        assignment.setText("Spaces around = assignments"); assignment.setChecked(original.spaceAroundAssignment);
        panel.addView(assignment);

        new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
            .setTitle("Configure Code Style")
            .setView(scroll)
            .setPositiveButton("Save Style", new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int which) {
                    CodeFormatter.Style s = original.copy();
                    int selected = indent.getCheckedRadioButtonId();
                    s.tabs = selected == ids[3];
                    s.indentSize = selected == ids[0] ? 2 : selected == ids[2] ? 8 : 4;
                    s.braceNextLine = braces.getCheckedRadioButtonId() == 2532;
                    s.spaceAfterComma = comma.isChecked();
                    s.spaceAroundAssignment = assignment.isChecked();
                    rememberCodeStyle(s);
                    Toast.makeText(MainActivity.this, "Code style saved", Toast.LENGTH_SHORT).show();
                }
            }).setNegativeButton("Cancel", null).show();
    }

    // ---------- Code menu: real Java editor actions ----------

    void initCodeEditor() {
        editorText.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                lastChangeStart = start;
                lastChangeBefore = before;
                lastChangeCount = count;
            }
            public void afterTextChanged(final Editable e) {
                if (editorLoading || !editorOpen()) return;
                editorDirty = !e.toString().equals(editorSaved);
                updateEditorTitle();
                updateOpenedTabs();
                updateLineNumbers(e.toString());
                updateUndoRedoButtons();
                if (pendingHighlight != null) editorHandler.removeCallbacks(pendingHighlight);
                final boolean skipSuggestions = applyingFormatEdit;
                pendingHighlight = new Runnable() {
                    public void run() {
                        refreshJavaHighlight();
                        if (!skipSuggestions && lastChangeCount > 0) {
                            String inserted = "";
                            try { inserted = e.subSequence(lastChangeStart, Math.min(e.length(), lastChangeStart + lastChangeCount)).toString(); }
                            catch (Exception ex) { }
                            if (inserted.matches(".*[A-Za-z_.].*")) maybeShowCompletionPopup(false);
                            else if (completionPopup != null) completionPopup.dismiss();
                        }
                    }
                };
                editorHandler.postDelayed(pendingHighlight, 180);
                if (currentFile != null) {
                    String fileName = currentFile.getName().toLowerCase(java.util.Locale.US);
                    if (fileName.endsWith(".java")) scheduleLiveCompile();
                    else if (fileName.endsWith(".kt")) scheduleLiveKotlinCheck();
                    else if (fileName.endsWith(".xml")) scheduleLiveXmlCheck();
                }
            }
        });
    }

    void updateEditorTitle() {
        if (currentFile != null) titleText.setText(currentFile.getName() + (editorDirty ? " *" : ""));
    }

    void updateLineNumbers(String text) {
        int n = 1;
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') n++;
        StringBuilder b = new StringBuilder(n * 5);
        for (int i = 1; i <= n; i++) { if (i > 1) b.append('\n'); b.append(i); }
        lineNumbers.setText(b);
    }

    void refreshJavaHighlight() {
        if (!editorOpen() || currentFile == null) return;
        String text = editorText.getText().toString();
        if (text.length() > 300000) return;
        boolean xml = currentFile.getName().toLowerCase(java.util.Locale.US).endsWith(".xml");
        boolean kotlin = currentFile.getName().endsWith(".kt") || currentFile.getName().endsWith(".kts");
        if (!xml && !kotlin && !currentFile.getName().endsWith(".java")) return;
        JavaAnalyzer.Result r = xml || kotlin ? null : JavaAnalyzer.analyze(text, currentFile);
        Editable target = editorText.getText();
        if (!text.equals(target.toString())) return;
        ForegroundColorSpan[] colors = target.getSpans(0, target.length(), ForegroundColorSpan.class);
        for (ForegroundColorSpan span : colors) target.removeSpan(span);
        StyleSpan[] styles = target.getSpans(0, target.length(), StyleSpan.class);
        for (StyleSpan span : styles) target.removeSpan(span);
        CharSequence highlightedText = xml ? XmlSyntaxHighlighter.highlight(text)
                : kotlin ? KotlinSyntaxHighlighter.highlight(text) : r.text;
        if (highlightedText instanceof Spanned) {
            Spanned from = (Spanned) highlightedText;
            for (ForegroundColorSpan span : from.getSpans(0, from.length(), ForegroundColorSpan.class)) {
                target.setSpan(new ForegroundColorSpan(span.getForegroundColor()),
                        from.getSpanStart(span), from.getSpanEnd(span), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            for (StyleSpan span : from.getSpans(0, from.length(), StyleSpan.class)) {
                target.setSpan(new StyleSpan(span.getStyle()), from.getSpanStart(span),
                        from.getSpanEnd(span), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        if (!xml && !kotlin) {
            currentErrors = r.errors;
            editorText.setErrors(currentErrors);
            updateErrorBadge();
        }
    }

    /** Saves the current editor buffer directly to disk without creating an automatic backup file. */
    boolean saveEditor(boolean showMessage) {
        if (currentFile == null || !currentFile.isFile()) {
            if (showMessage) Toast.makeText(this, "Open a writable project file first", Toast.LENGTH_LONG).show();
            return false;
        }
        if (!editorDirty) {
            if (showMessage) Toast.makeText(this, "No changes to save", Toast.LENGTH_SHORT).show();
            return true;
        }
        try {
            String old = RefactorEngine.read(currentFile);
            if (!old.equals(editorSaved)) throw new java.io.IOException("File changed outside the editor. Reopen it before saving.");
            String changed = editorText.getText().toString();
            java.io.FileOutputStream out = new java.io.FileOutputStream(currentFile, false);
            out.write(changed.getBytes("UTF-8"));
            out.close();
            editorSaved = changed;
            editorDirty = false;
            updateEditorTitle();
            updateOpenedTabs();
            if (currentFile.getName().endsWith(".java")) runCompiler(currentFile);
            else if (currentFile.getName().endsWith(".kt")) runKotlinCheck(currentFile);
            else if (currentFile.getName().toLowerCase(java.util.Locale.US).endsWith(".xml")) scheduleLiveXmlCheck();
            if (currentFile.getName().equals("build.gradle") || currentFile.getName().equals("build.gradle.kts")
                    || currentFile.getName().equals("settings.gradle") || currentFile.getName().equals("settings.gradle.kts")) {
                editorHandler.post(new Runnable() { public void run() { checkAutomaticLibrarySync(); } });
            }
            if (showMessage) Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
            return true;
        } catch (Exception ex) {
            showRefactorError(ex);
            return false;
        }
    }

    boolean ensureSavedForRefactor() {
        if (!editorDirty) return true;
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
            .setTitle("Save changes first")
            .setMessage("You have unsaved changes. Tap Save, then choose Refactor again.")
            .setPositiveButton("Save", new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int which) { saveEditor(true); }
            }).setNegativeButton("Cancel", null).show();
        return false;
    }

    void openFileSafely(final File target) {
        final File targetProject = owningProject(target);
        if (targetProject != null && activeProject != null && !sameProject(targetProject, activeProject)) {
            requestProjectSwitch(targetProject, new Runnable() {
                public void run() {
                    if (target.getParentFile() != null) currentDir = target.getParentFile();
                    openFile(target);
                    if (bottomMode == MODE_FILES) refresh();
                }
            });
            return;
        }
        guardedNavigation(new Runnable() {
            public void run() {
                if (targetProject != null) activeProject = targetProject;
                openFile(target);
            }
        });
    }

    void guardedNavigation(final Runnable action) {
        if (!editorDirty || currentFile == null) { action.run(); return; }
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
            .setTitle("Unsaved changes")
            .setMessage("Save edits to " + currentFile.getName() + " before leaving?")
            .setPositiveButton("Save", new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int which) {
                    if (saveEditor(false)) action.run();
                }
            }).setNeutralButton("Discard", new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int which) { action.run(); }
            }).setNegativeButton("Cancel", null).show();
    }

    boolean needsJavaEditor() {
        if (needsJavaEditorSilent()) return true;
        Toast.makeText(this, "Open a Java or Kotlin source file first", Toast.LENGTH_LONG).show();
        return false;
    }

    void codeToggleComment() {
        if (!needsJavaEditor()) return;
        String text = editorText.getText().toString();
        int a = editorText.codeCaret(), b = editorText.codeEnd();
        if (a < 0) a = b = 0;
        CodeActions.SelectionEdit edit = CodeActions.toggleComments(text, a, b);
        if (edit.text.equals(text)) {
            Toast.makeText(this, "No non-empty lines to comment", Toast.LENGTH_SHORT).show();
            return;
        }
        editorText.getText().replace(0, editorText.length(), edit.text);
        editorText.setSelection(Math.min(edit.start, editorText.length()),
                Math.min(edit.end, editorText.length()));
        // Preserves unsaved state; Save creates a backup.
        Toast.makeText(this, "Comment toggled. Tap Save to write the file.", Toast.LENGTH_SHORT).show();
    }

    void codeAutocomplete() {
        if (!needsJavaEditorSilent()) {
            Toast.makeText(this, "Open a Java or Kotlin source file first", Toast.LENGTH_LONG).show();
            return;
        }
        maybeShowCompletionPopup(true);
    }

    void codeGoDefinition() {
        if (!needsJavaEditor()) return;
        String src = editorText.getText().toString();
        int a = editorText.codeCaret(), b = editorText.codeEnd();
        String word = CodeActions.wordAt(src, a, b);
        if (word.length() == 0) {
            Toast.makeText(this, "Place cursor on a symbol or select its name", Toast.LENGTH_LONG).show();
            return;
        }
        if (editorDirty) {
            new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
                .setTitle("Save before navigating")
                .setMessage("Unsaved edits may change symbol locations. Save, then run Go to Definition again.")
                .setPositiveButton("Save", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int which) { saveEditor(true); }
                }).setNegativeButton("Cancel", null).show();
            return;
        }
        final String symbol = word;
        final File file = currentFile, root = projectRoot();
        final int offset = a;
        Toast.makeText(this, "Looking for " + symbol + "…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            public void run() {
                try {
                    final List<CodeActions.Definition> found = CodeActions.definitions(file, root, symbol, offset);
                    runOnUiThread(new Runnable() {
                        public void run() { showDefinitions(symbol, found); }
                    });
                } catch (final Exception err) {
                    runOnUiThread(new Runnable() {
                        public void run() { showRefactorError(err); }
                    });
                }
            }
        }, "find-definitions").start();
    }

    void showDefinitions(String word, final List<CodeActions.Definition> found) {
        if (found.isEmpty()) {
            new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
                .setTitle("No local definition for " + word)
                .setMessage("This lightweight resolver only finds common Java class, method and variable declarations "
                        + "in local .java project files. SDK/library declarations and overloaded symbols "
                        + "require a full Java language server.")
                .setPositiveButton("OK", null).show();
            return;
        }
        CharSequence[] labels = new CharSequence[found.size()];
        for (int i = 0; i < found.size(); i++) {
            CodeActions.Definition f = found.get(i);
            labels[i] = f.file.getName() + ":" + f.line + "  " + f.label;
        }
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
            .setTitle("Definitions: " + word)
            .setItems(labels, new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int which) {
                    CodeActions.Definition target = found.get(which);
                    openFileAt(target.file, target.line);
                }
            }).setNegativeButton("Cancel", null).show();
    }

    /** Fix the AndroidX import from the user's sample without touching other imports. */
    void codeFixAndroidxImport() {
        if (!needsJavaEditor() || !ensureSavedForRefactor()) return;
        final File file = currentFile;
        final List<String> imports = ImportQuickFix.findAndroidxImports(editorText.getText().toString());
        if (imports.isEmpty()) {
            new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
                .setTitle("No AndroidX imports")
                .setMessage("No AndroidX imports found in this Java file. If you use AndroidX classes, check your Gradle dependencies instead.")
                .setPositiveButton("OK", null).show();
            return;
        }
        CharSequence[] choices = new CharSequence[imports.size()];
        for (int i = 0; i < imports.size(); i++) choices[i] = "import " + imports.get(i) + ";";
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
            .setTitle("Remove selected AndroidX import")
            .setItems(choices, new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int which) {
                    final String chosen = imports.get(which);
                    prepareRefactor(new RefactorJob() {
                        public RefactorEngine.Plan build() throws Exception {
                            return ImportQuickFix.removeImport(file, chosen);
                        }
                    });
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    // ---------- Maven / AndroidX libraries (editor classpath, not APK packaging) ----------

    File currentModule() {
        File module = currentFile == null ? null : MavenLibraries.moduleFromSource(currentFile);
        if (module == null) module = MavenLibraries.moduleFromDirectory(currentDir);
        if (module == null) module = MavenLibraries.moduleFromDirectory(projectRoot());
        return module;
    }

    void maybeOfferAndroidx(final File source) {
        String coordinate = MavenLibraries.importCoordinate(editorText.getText().toString());
        if (coordinate == null) return;
        final File module = MavenLibraries.moduleFromSource(source);
        if (module == null) return;
        String key = module.getAbsolutePath() + "::" + coordinate;
        if (libraryOffered.contains(key)) return;
        libraryOffered.add(key);
        final String recommendation = coordinate;
        try {
            if (MavenLibraries.hasDeclared(module, MavenLibraries.coordinate(recommendation))) {
                if (!MavenLibraries.importIsInstalled(source, editorText.getText().toString())) {
                    // Already declared: automatically download missing POM/AAR dependencies.
                    // Poller performs a non-modal sync for declared dependencies.
                    checkAutomaticLibrarySync();
                }
            } else if (!MavenLibraries.importIsInstalled(source, editorText.getText().toString())) {
                new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
                    .setTitle("AndroidX dependency needed")
                    .setMessage("This Java file imports AndroidX. Add " + recommendation
                        + " to " + module.getName() + "/build.gradle and download its dependencies?"
                        + "\n\nAideUI uses the downloaded classes for code checking."
                        + " Your project's Gradle build still packages the APK.")
                    .setPositiveButton("Add and download", new android.content.DialogInterface.OnClickListener() {
                        public void onClick(android.content.DialogInterface d, int which) { addLibrary(recommendation); }
                    }).setNegativeButton("Later", null).show();
            }
        } catch (Exception ignored) { }
    }

    void installImportLibrary() {
        if (!needsJavaEditor()) return;
        String coordinate = MavenLibraries.importCoordinate(editorText.getText().toString());
        if (coordinate == null) {
            new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
                .setTitle("AndroidX import")
                .setMessage("No supported AndroidX import was detected. Use Add Maven Dependency to enter group:artifact:version manually.")
                .setPositiveButton("OK", null).show();
        } else addLibrary(coordinate);
    }

    void promptMavenDependency() {
        promptText("Maven dependency", "group:artifact:version", MavenLibraries.DEFAULT_ACTIVITY,
            android.text.InputType.TYPE_CLASS_TEXT, new TextCb() {
                public void got(String entry) { addLibrary(entry); }
            });
    }

    void addLibrary(String input) {
        final File module = currentModule();
        if (module == null) {
            Toast.makeText(this, "Open a Java source in a Gradle module first", Toast.LENGTH_LONG).show();
            return;
        }
        final MavenLibraries.Coordinate coordinate;
        try { coordinate = MavenLibraries.coordinate(input); }
        catch (Exception ex) {
            new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
                .setTitle("Invalid Maven dependency").setMessage(ex.getMessage()).setPositiveButton("OK", null).show();
            return;
        }
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
            .setTitle("Add library and download")
            .setMessage("Dependency: " + coordinate + "\n\nModule: " + module.getAbsolutePath()
                + "\n\nCache: " + MavenLibraries.store(module).getAbsolutePath()
                + "\n\nThis modifies build.gradle (with backup), then downloads AAR/JAR files and transitive dependencies via HTTPS from Google Maven / Maven Central. "
                + "Mobile data may be used. Gradle/AIDE must still build the final APK.")
            .setPositiveButton("Add and download", new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int which) {
                    if (librarySyncing) return;
                    librarySyncing = true;
                    final android.app.ProgressDialog progress = createLibraryProgress("Editing Gradle dependency...");
                    new Thread(new Runnable() {
                        public void run() {
                            try {
                                MavenLibraries.addGradleDependency(module, coordinate);
                                doSyncLibraries(module, progress);
                            } catch (final Exception ex) { libraryFailed(ex, progress); }
                        }
                    }, "maven-add-library").start();
                }
            }).setNegativeButton("Cancel", null).show();
    }

    // ---------- v13: automatic Gradle dependency monitoring / safe Maven cache ----------

    boolean autoSyncEnabled() {
        return getPreferences(MODE_PRIVATE).getBoolean("auto_gradle_dependencies", true);
    }

    boolean customRepositoriesEnabled() {
        return getPreferences(MODE_PRIVATE).getBoolean("custom_maven_repositories", false);
    }

    void showAutoSyncSettings() {
        final String[] choices = {
            "Automatically sync after Gradle changes (while AideUI is open)",
            "Allow project-defined custom HTTPS Maven repositories"
        };
        final boolean[] selected = {autoSyncEnabled(), customRepositoriesEnabled()};
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
            .setTitle("Library sync settings")
            .setMultiChoiceItems(choices, selected, new android.content.DialogInterface.OnMultiChoiceClickListener() {
                public void onClick(android.content.DialogInterface d, int which, boolean checked) {
                    selected[which] = checked;
                }
            })
            .setPositiveButton("Save", new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int which) {
                    getPreferences(MODE_PRIVATE).edit()
                        .putBoolean("auto_gradle_dependencies", selected[0])
                        .putBoolean("custom_maven_repositories", selected[1]).apply();
                    autoLibraryAttempted.clear();
                    editorHandler.post(new Runnable() { public void run() { checkAutomaticLibrarySync(); } });
                }
            }).setNegativeButton("Cancel", null).show();
    }

    void setLibraryStatus(File module, String info, boolean failure) {
        if (libraryStatusText == null) return;
        File selected = currentModule();
        if (selected == null || module == null || !selected.equals(module)) return;
        libraryStatusText.setVisibility(View.VISIBLE);
        libraryStatusText.setText("Libraries: " + info + "  (tap for details)");
        libraryStatusText.setTextColor(Color.parseColor(failure ? "#B71C1C" : "#366475"));
    }

    /**
     * Do not probe Gradle from the directory browser. Earlier releases could start
     * expensive dependency work simply because the user entered a project folder.
     * Only an opened project file enables automatic syncing. Filesystem inspection
     * runs off the UI thread; the callback rechecks which file/module is active.
     */
    void checkAutomaticLibrarySync() {
        if (currentFile == null || !currentFile.isFile()) {
            if (libraryStatusText != null) libraryStatusText.setVisibility(View.GONE);
            return;
        }
        final File fromFile = MavenLibraries.moduleFromSource(currentFile);
        final File module = fromFile != null ? fromFile
            : MavenLibraries.moduleFromDirectory(currentFile.getParentFile());
        if (module == null || !MavenLibraries.gradleFile(module).isFile()) {
            if (libraryStatusText != null) libraryStatusText.setVisibility(View.GONE);
            return;
        }
        if (!autoSyncEnabled()) {
            setLibraryStatus(module, "Auto sync OFF", false);
            return;
        }
        if (librarySyncing || libraryCheckRunning) return;
        libraryCheckRunning = true;
        final File fileAtStart = currentFile;
        final String lastAttempt = autoLibraryAttempted.get(module.getAbsolutePath());
        new Thread(new Runnable() {
            public void run() {
                String fingerprint = null;
                boolean needed = false;
                String lastStatus = "";
                String failure = null;
                try {
                    fingerprint = GradleAutoSync.fingerprint(module);
                    needed = GradleAutoSync.needsSync(module);
                    if (needed && fingerprint.equals(lastAttempt))
                        lastStatus = GradleAutoSync.status(module);
                } catch (Throwable ex) {
                    failure = ex.getClass().getSimpleName() + ": " + String.valueOf(ex.getMessage());
                }
                final String resultFingerprint = fingerprint;
                final boolean resultNeeded = needed;
                final String resultStatus = lastStatus;
                final String resultError = failure;
                runOnUiThread(new Runnable() {
                    public void run() {
                        libraryCheckRunning = false;
                        // No sync status or download should be applied to a different file.
                        if (!libraryPolling || currentFile == null || !currentFile.equals(fileAtStart)) return;
                        if (resultError != null) {
                            setLibraryStatus(module, "Gradle check failed: " + resultError, true);
                            return;
                        }
                        if (!resultNeeded) {
                            setLibraryStatus(module, "Up to date (cached)", false);
                            return;
                        }
                        if (resultFingerprint != null
                                && resultFingerprint.equals(autoLibraryAttempted.get(module.getAbsolutePath()))) {
                            if (resultStatus.startsWith("Failed"))
                                setLibraryStatus(module, "Sync failed — tap for retry", true);
                            return;
                        }
                        if (resultFingerprint != null)
                            autoLibraryAttempted.put(module.getAbsolutePath(), resultFingerprint);
                        syncLibraries(module, true);
                    }
                });
            }
        }, "aideui-gradle-check").start();
    }

    void syncProjectLibraries() {
        File module = currentModule();
        if (module == null) { Toast.makeText(this, "Open a Gradle module first", Toast.LENGTH_LONG).show(); return; }
        syncLibraries(module, false);
    }

    android.app.ProgressDialog createLibraryProgress(String message) {
        android.app.ProgressDialog p = new android.app.ProgressDialog(this);
        p.setTitle("Maven library sync");
        p.setMessage(message);
        p.setIndeterminate(true);
        p.setCancelable(false);
        p.show();
        return p;
    }

    void syncLibraries(final File module) { syncLibraries(module, false); }

    void syncLibraries(final File module, boolean automatic) {
        if (librarySyncing) {
            if (!automatic) Toast.makeText(this, "Library download already running", Toast.LENGTH_SHORT).show();
            return;
        }
        librarySyncing = true;
        setLibraryStatus(module, "Checking Gradle dependencies...", false);
        final android.app.ProgressDialog progress = automatic ? null : createLibraryProgress("Resolving Maven dependencies...");
        final boolean auto = automatic;
        new Thread(new Runnable() {
            public void run() { doSyncLibraries(module, progress, auto); }
        }, "maven-library-sync").start();
    }

    void doSyncLibraries(final File module, final android.app.ProgressDialog progress) {
        doSyncLibraries(module, progress, false);
    }

    void doSyncLibraries(final File module, final android.app.ProgressDialog progress, final boolean automatic) {
        try {
            final String before = GradleAutoSync.fingerprint(module);
            final MavenLibraries.Summary result = MavenLibraries.sync(module, new MavenLibraries.Progress() {
                public void update(final String message) {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            if (progress != null) progress.setMessage(message);
                            setLibraryStatus(module, message, false);
                        }
                    });
                }
            }, customRepositoriesEnabled());
            final boolean unchanged = GradleAutoSync.markSuccess(module, before);
            GradleAutoSync.saveStatus(module, unchanged
                ? "Success: " + result.artifacts + " artifacts, " + result.jars.size() + " JARs; "
                    + result.warnings.size() + " warnings"
                : "Gradle changed during sync; another sync is required");
            runOnUiThread(new Runnable() {
                public void run() {
                    librarySyncing = false;
                    if (progress != null) progress.dismiss();
                    setLibraryStatus(module, unchanged
                        ? "Ready — " + result.jars.size() + " JARs" : "Gradle changed — rechecking", false);
                    if (currentFile != null && module.equals(MavenLibraries.moduleFromSource(currentFile))) {
                        refreshCompletionCatalog(true);
                        refreshJavaHighlight();
                        if (!editorDirty) runCompiler(currentFile);
                    }
                    if (progress != null) {
                        new android.app.AlertDialog.Builder(MainActivity.this, android.R.style.Theme_Material_Light_Dialog_Alert)
                            .setTitle("Maven libraries synced for editor")
                            .setMessage(result.message(module) + "\n\nAPK builds are handled separately by Gradle/AIDE."
                                + (unchanged ? "" : "\nGradle changed during download; it will be checked again."))
                            .setPositiveButton("OK", null).show();
                    }
                    // A second Gradle edit during download will be picked up next poll.
                    editorHandler.postDelayed(new Runnable() {
                        public void run() { if (libraryPolling) checkAutomaticLibrarySync(); }
                    }, 300);
                }
            });
        } catch (Exception ex) { libraryFailed(ex, progress, module, automatic); }
    }

    void libraryFailed(final Exception ex, final android.app.ProgressDialog progress) {
        libraryFailed(ex, progress, currentModule(), false);
    }

    void libraryFailed(final Exception ex, final android.app.ProgressDialog progress,
                       final File module, final boolean automatic) {
        try {
            if (module != null) GradleAutoSync.saveStatus(module, "Failed: " + String.valueOf(ex.getMessage()));
        } catch (Exception ignored) { }
        runOnUiThread(new Runnable() {
            public void run() {
                librarySyncing = false;
                if (progress != null) progress.dismiss();
                setLibraryStatus(module, "Sync failed — tap for details", true);
                if (!automatic) {
                    new android.app.AlertDialog.Builder(MainActivity.this, android.R.style.Theme_Material_Light_Dialog_Alert)
                        .setTitle("Library sync failed")
                        .setMessage(String.valueOf(ex.getMessage())
                            + "\n\nCheck network access, supported dependency syntax and repositories. "
                            + "The previous classpath is preserved. Choose Sync Gradle Libraries to retry.")
                        .setPositiveButton("OK", null).show();
                }
            }
        });
    }

    void showLibraryStatus() {
        File module = currentModule();
        if (module == null) { Toast.makeText(this, "Open a Gradle module first", Toast.LENGTH_LONG).show(); return; }
        StringBuilder b = new StringBuilder("Module: " + module + "\n");
        b.append("Auto sync: ").append(autoSyncEnabled() ? "ON" : "OFF")
            .append(" (every 3s while open)\nCustom HTTPS repositories: ")
            .append(customRepositoriesEnabled() ? "ENABLED" : "OFF");
        b.append("\nLast sync: ").append(GradleAutoSync.status(module));
        b.append("\n\nGradle dependencies:\n");
        try {
            for (MavenLibraries.Coordinate c : MavenLibraries.dependencies(module)) b.append(" • ").append(c).append('\n');
            b.append("Repositories: ").append(MavenLibraries.repositoryUrls(module, customRepositoriesEnabled()).size());
            b.append(GradleAutoSync.needsSync(module) ? "\nChanges pending download" : "\nClasspath up to date");
        } catch (Exception ex) { b.append("Read error: ").append(ex.getMessage()); }
        b.append("\nCached classpath JARs: ").append(MavenLibraries.classpath(module).size());
        b.append("\nLocation: ").append(MavenLibraries.store(module).getAbsolutePath());
        b.append("\n\nNote: this cache is for editor/ECJ only, not packaged into the APK. ")
            .append("Full AndroidX builds depend on the project's Gradle/AIDE support.");
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
            .setTitle("Library Status").setMessage(b.toString())
            .setNeutralButton("Retry Sync", new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int which) { syncProjectLibraries(); }
            }).setPositiveButton("OK", null).show();
    }

    // ---------- Advanced Code actions (APK-style menu order) ----------

    void codeOrganizeImports() {
        if (!needsJavaEditor() || !ensureSavedForRefactor()) return;
        final File file=currentFile;
        final CharSequence[] choices={"Sort imports only (safer)", "Sort + remove apparently unused imports"};
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
            .setTitle("Organize Imports")
            .setSingleChoiceItems(choices, 0, null)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Preview", new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int which) {
                    android.widget.ListView list=((android.app.AlertDialog)d).getListView();
                    final boolean remove=list != null && list.getCheckedItemPosition()==1;
                    prepareRefactor(new RefactorJob() {
                        public RefactorEngine.Plan build() throws Exception {
                            return AdvancedCodeActions.organizeImports(file, remove);
                        }
                    });
                }
            }).show();
    }

    void codeSurroundTryCatch() {
        if (!needsJavaEditor() || !ensureSavedForRefactor()) return;
        int a=editorText.refactorStart(), b=editorText.refactorEnd();
        if (a<0 || b<=a) {
            new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
                .setTitle("Surround with try-catch")
                .setMessage("Long-press the editor and select complete Java statement lines inside a method, then choose this action.")
                .setPositiveButton("OK",null).show();
            return;
        }
        final File file=currentFile;
        final int start=a, end=b;
        prepareRefactor(new RefactorJob() {
            public RefactorEngine.Plan build() throws Exception {
                return AdvancedCodeActions.surroundTryCatch(file,start,end);
            }
        });
    }

    void codeLineComment(boolean comment) {
        if (!needsJavaEditor()) return;
        String src=editorText.getText().toString();
        int a=editorText.refactorStart(),b=editorText.refactorEnd();
        if(a<0) { a=editorText.codeCaret(); b=editorText.codeEnd(); }
        if(a<0) a=b=0;
        if(b<a) {int t=a;a=b;b=t;}
        CodeActions.SelectionEdit edit=AdvancedCodeActions.lineComment(src,a,b,comment);
        if(edit.text.equals(src)) {
            Toast.makeText(this,comment ? "Lines already commented" : "No // comments on selected lines",Toast.LENGTH_SHORT).show();
            return;
        }
        editorText.getText().replace(0,editorText.length(),edit.text);
        editorText.setSelection(Math.min(edit.start,editorText.length()),Math.min(edit.end,editorText.length()));
        Toast.makeText(this,comment ? "Commented. Tap Save to write file." : "Uncommented. Tap Save to write file.",Toast.LENGTH_LONG).show();
    }

    void codeCreateMembers(final boolean constructor) {
        if (needsJavaEditorSilent() && currentFileIsKotlin()) {
            new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
                .setTitle(constructor ? "Constructors in Kotlin" : "Getters and setters in Kotlin")
                .setMessage(constructor
                    ? "Kotlin declares constructors in the class header: class Person(val name: String, var age: Int). "
                      + "Every val/var in the header is a property and a constructor parameter, so there is nothing to generate. "
                      + "For extra constructors write: constructor(name: String) : this(name, 0)."
                    : "Every Kotlin val/var is already a property with a getter (and a setter for var). "
                      + "Use val for read-only and var for read-write. Custom accessors go right below the property: "
                      + "val label: String get() = \"x\".")
                .setPositiveButton("OK", null).show();
            return;
        }
        if (!needsJavaEditor() || !ensureSavedForRefactor()) return;
        final File file=currentFile;
        final AdvancedCodeActions.ClassInfo info;
        try { info=AdvancedCodeActions.inspectClass(editorText.getText().toString()); }
        catch(Exception e) { showRefactorError(e); return; }
        final java.util.List<AdvancedCodeActions.Field> available=new java.util.ArrayList<AdvancedCodeActions.Field>();
        for(AdvancedCodeActions.Field f:info.fields) {
            if(!constructor || (!f.isStatic && !(f.isFinal && f.hasInitializer))) available.add(f);
        }
        final CharSequence[] labels=new CharSequence[available.size()];
        final boolean[] chosen=new boolean[available.size()];
        for(int i=0;i<available.size();i++) { labels[i]=available.get(i).label(); chosen[i]=true; }
        if (constructor && available.isEmpty() && info.fields.isEmpty()) {
            new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
                .setTitle("Create Constructor")
                .setMessage("Create a no-argument constructor for class " + info.name + "?"
                        + (info.androidComponent ? "\nCaution: framework-created Android components should not use parameterized constructors." : ""))
                .setNegativeButton("Cancel",null)
                .setPositiveButton("Preview",new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        prepareRefactor(new RefactorJob(){
                            public RefactorEngine.Plan build() throws Exception {
                                return AdvancedCodeActions.createConstructor(file,new java.util.HashSet<String>());
                            }
                        });
                    }
                }).show();
            return;
        }
        if(available.isEmpty()) {
            Toast.makeText(this,"No eligible class fields found",Toast.LENGTH_LONG).show();return;
        }
        final java.util.Set<String> selected=new java.util.LinkedHashSet<String>();
        for(AdvancedCodeActions.Field f:available) selected.add(f.name);
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
            .setTitle(constructor ? "Create Constructor: " + info.name : "Create Setters and Getters")
            .setMultiChoiceItems(labels,chosen,new android.content.DialogInterface.OnMultiChoiceClickListener() {
                public void onClick(android.content.DialogInterface d,int which,boolean isChecked) {
                    if(isChecked) selected.add(available.get(which).name);
                    else selected.remove(available.get(which).name);
                }
            })
            .setNegativeButton("Cancel",null)
            .setPositiveButton("Preview",new android.content.DialogInterface.OnClickListener(){
                public void onClick(android.content.DialogInterface d,int which) {
                    final java.util.Set<String> pick=new java.util.LinkedHashSet<String>(selected);
                    prepareRefactor(new RefactorJob(){
                        public RefactorEngine.Plan build() throws Exception {
                            if(constructor) return AdvancedCodeActions.createConstructor(file,pick);
                            return AdvancedCodeActions.createAccessors(file,pick);
                        }
                    });
                }
            }).show();
    }

    void codeExtractResources() {
        if(!needsJavaEditor() || !ensureSavedForRefactor()) return;
        int a=editorText.refactorStart(), b=editorText.refactorEnd();
        if(a<0 || b<=a) {a=editorText.codeCaret();b=a;}
        if(a<0) {Toast.makeText(this,"Place cursor inside a Java string",Toast.LENGTH_LONG).show();return;}
        final int start=a,end=b; final File file=currentFile;
        LinearLayout root=refactorForm();
        final EditText name=formField(root,"String resource name", "extracted_text", "e.g. welcome_message");
        TextView note=new TextView(this);
        note.setText("Select a Java string literal, or place the cursor inside it. Creates a string resource and replaces it with getString(R.string.name). Requires Android Context/Activity.");
        note.setTextSize(14); root.addView(note);
        new android.app.AlertDialog.Builder(this,android.R.style.Theme_Material_Light_Dialog_Alert)
            .setTitle("Extract Resources")
            .setView(root)
            .setNegativeButton("Cancel",null)
            .setPositiveButton("Preview",new android.content.DialogInterface.OnClickListener(){
                public void onClick(android.content.DialogInterface d,int which) {
                    final String resource=name.getText().toString().trim();
                    prepareRefactor(new RefactorJob(){
                        public RefactorEngine.Plan build() throws Exception {
                            return AdvancedCodeActions.extractResource(file,start,end,resource);
                        }
                    });
                }
            }).show();
    }

    void codeReplaceTextually() {
        if(!needsJavaEditor() || !ensureSavedForRefactor()) return;
        final File file=currentFile;
        String from=""; int a=editorText.refactorStart(),b=editorText.refactorEnd();
        String code=editorText.getText().toString();
        if(a>=0 && b>a && b<=code.length() && b-a<=100) from=code.substring(a,b);
        LinearLayout root=refactorForm();
        final EditText target=formField(root,"Find text",from,"Text to replace");
        final EditText with=formField(root,"Replace with","","Replacement (empty = delete)");
        final android.widget.CheckBox allFiles=new android.widget.CheckBox(this);
        allFiles.setText("All Java files in this project (not just this file)");root.addView(allFiles);
        final android.widget.CheckBox caseMatch=new android.widget.CheckBox(this);
        caseMatch.setText("Match case");caseMatch.setChecked(true);root.addView(caseMatch);
        final android.widget.CheckBox regex=new android.widget.CheckBox(this);
        regex.setText("Regular expression ($1 groups supported)");root.addView(regex);
        TextView caution=new TextView(this);
        caution.setText("Text replacements ALSO affect comments and strings. Review before applying. Existing files are backed up.");
        caution.setTextSize(14);root.addView(caution);
        new android.app.AlertDialog.Builder(this,android.R.style.Theme_Material_Light_Dialog_Alert)
            .setTitle("Replace Textually")
            .setView(root)
            .setNegativeButton("Cancel",null)
            .setPositiveButton("Preview",new android.content.DialogInterface.OnClickListener(){
                public void onClick(android.content.DialogInterface d,int which) {
                    final String f=target.getText().toString(),r=with.getText().toString();
                    final boolean whole=allFiles.isChecked(),re=regex.isChecked(),sensitive=caseMatch.isChecked();
                    prepareRefactor(new RefactorJob(){
                        public RefactorEngine.Plan build() throws Exception {
                            return AdvancedCodeActions.replaceTextually(file,projectRoot(),f,r,whole,re,sensitive);
                        }
                    });
                }
            }).show();
    }

    // ---------- Refactor: Rename, Extract Method, Move ----------

    boolean requireJavaFile() {
        if (currentFile != null && currentFile.isFile() && needsJavaEditorSilent()) return true;
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
            .setTitle("Open Java or Kotlin source first")
            .setMessage("Open a project folder in the lower file browser, then tap a .java or .kt file. "
                + "For Rename or Extract Method, long-press code to select an identifier or complete statements.")
            .setPositiveButton("OK", null).show();
        return false;
    }

    LinearLayout refactorForm() {
        LinearLayout l = new LinearLayout(this);
        l.setPadding(dp(20), dp(8), dp(20), dp(8));
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    EditText formField(LinearLayout root, String label, String initial, String hint) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(15);
        t.setTextColor(Color.DKGRAY);
        root.addView(t);
        EditText f = new EditText(this);
        f.setSingleLine(true);
        f.setTextSize(17);
        f.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        f.setHint(hint);
        if (initial != null) f.setText(initial);
        root.addView(f);
        return f;
    }

    String currentSymbol() {
        String content = editorText.getText().toString();
        return RefactorEngine.symbolAt(content, editorText.refactorStart(), editorText.refactorEnd());
    }

    void refactorRename() {
        if (!ensureSavedForRefactor() || !requireJavaFile()) return;
        final File file = currentFile;
        LinearLayout root = refactorForm();
        final EditText oldName = formField(root, "Existing identifier", currentSymbol(), "e.g. oldMethod");
        final EditText newName = formField(root, "New identifier", "", "e.g. newMethod");
        TextView caution = new TextView(this);
        caution.setPadding(0, dp(12), 0, dp(4));
        caution.setText("Scope (code identifiers only; not Java semantic binding)");
        caution.setTextSize(14);
        root.addView(caution);
        final RadioGroup scope = new RadioGroup(this);
        scope.setOrientation(RadioGroup.VERTICAL);
        RadioButton thisFile = new RadioButton(this);
        thisFile.setId(11221);
        thisFile.setText("This Java file only");
        scope.addView(thisFile);
        RadioButton allFiles = new RadioButton(this);
        allFiles.setId(11222);
        allFiles.setText("All Java files in this project");
        scope.addView(allFiles);
        scope.check(11221);
        root.addView(scope);
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
            .setTitle("Refactor: Rename")
            .setView(root)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Preview", new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int which) {
                    prepareRefactor(new RefactorJob() {
                        public RefactorEngine.Plan build() throws Exception {
                            return RefactorEngine.rename(file, projectRoot(),
                                oldName.getText().toString().trim(),
                                newName.getText().toString().trim(), scope.getCheckedRadioButtonId() == 11222);
                        }
                    });
                }
            }).show();
    }

    void refactorExtract() {
        if (!ensureSavedForRefactor() || !requireJavaFile()) return;
        int a = editorText.refactorStart(), b = editorText.refactorEnd();
        if (a < 0 || b <= a) {
            new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
                .setTitle("Select code to extract")
                .setMessage("Long-press inside the source and select entire statement lines. "
                    + "Then choose More… > Refactor… > Extract Method….\n\n"
                    + "The extracted statements must be inside an existing method.")
                .setPositiveButton("OK", null).show();
            return;
        }
        final int start = a, end = b;
        final File file = currentFile;
        LinearLayout root = refactorForm();
        final EditText method = formField(root, "New method name", "extractedMethod", "e.g. updateDisplay");
        final boolean kotlinSource = currentFileIsKotlin();
        final EditText args = formField(root, "Parameters (optional)", "", kotlinSource ? "e.g. count: Int, label: String" : "e.g. int count, String label");
        TextView note = new TextView(this);
        note.setText("Creates a private " + (kotlinSource ? "fun" : "void method") + " and replaces the selected statements with a call. "
            + "If the code uses local variables from the original method, add them as parameters. "
            + "Statements containing return/break/continue are not supported.");
        note.setTextSize(14);
        note.setPadding(0, dp(12), 0, 0);
        root.addView(note);
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
            .setTitle("Refactor: Extract Method")
            .setView(root)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Preview", new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int which) {
                    prepareRefactor(new RefactorJob() {
                        public RefactorEngine.Plan build() throws Exception {
                            return RefactorEngine.extract(file, start, end,
                                method.getText().toString().trim(), args.getText().toString());
                        }
                    });
                }
            }).show();
    }

    void refactorMove() {
        if (!ensureSavedForRefactor() || !requireJavaFile()) return;
        final File file = currentFile;
        final String currentPkg;
        try { currentPkg = RefactorEngine.declaredPackage(RefactorEngine.read(file)); }
        catch (Exception ex) { showRefactorError(ex); return; }
        LinearLayout root = refactorForm();
        final EditText pkg = formField(root, "Destination package", currentPkg, "e.g. com.example.utils");
        TextView note = new TextView(this);
        note.setText("Moves the Java file under src/main/java and updates the package, explicit "
                + "imports, qualified Java references and manifest component names. "
                + "Review any reflection strings, wildcard imports and resources afterwards.");
        note.setTextSize(14);
        note.setPadding(0, dp(12), 0, 0);
        root.addView(note);
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
            .setTitle("Refactor: Move class")
            .setView(root)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Preview", new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int which) {
                    prepareRefactor(new RefactorJob() {
                        public RefactorEngine.Plan build() throws Exception {
                            return RefactorEngine.move(file, projectRoot(), pkg.getText().toString().trim());
                        }
                    });
                }
            }).show();
    }

    interface RefactorJob { RefactorEngine.Plan build() throws Exception; }

    void prepareRefactor(final RefactorJob job) {
        Toast.makeText(this, "Preparing refactor preview…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            public void run() {
                try {
                    final RefactorEngine.Plan plan = job.build();
                    runOnUiThread(new Runnable() {
                        public void run() { showRefactorPreview(plan); }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        public void run() { showRefactorError(e); }
                    });
                }
            }
        }, "refactor-plan").start();
    }

    void showRefactorPreview(final RefactorEngine.Plan plan) {
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
            .setTitle("Preview refactor")
            .setMessage(plan.preview())
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Apply and back up", new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int which) {
                    Toast.makeText(MainActivity.this, "Applying refactor…", Toast.LENGTH_SHORT).show();
                    new Thread(new Runnable() {
                        public void run() {
                            try {
                                RefactorEngine.apply(plan);
                                runOnUiThread(new Runnable() {
                                    public void run() {
                                        if (plan.openAfter != null && plan.openAfter.exists()) {
                                            currentDir = plan.openAfter.getParentFile();
                                            openFile(plan.openAfter);
                                        } else if (currentFile != null && !currentFile.exists()) {
                                            currentFile = null;
                                            titleText.setText("");
                                            editorScroll.setVisibility(View.GONE);
                                            emptyText.setVisibility(View.VISIBLE);
                                        }
                                        refresh();
                                        Toast.makeText(MainActivity.this,
                                            "Refactor saved; backup(s) created", Toast.LENGTH_LONG).show();
                                    }
                                });
                            } catch (final Exception e) {
                                runOnUiThread(new Runnable() {
                                    public void run() { showRefactorError(e); refresh(); }
                                });
                            }
                        }
                    }, "refactor-apply").start();
                }
            }).show();
    }

    void showRefactorError(Exception error) {
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
            .setTitle("Refactor cannot continue")
            .setMessage(error.getMessage() == null ? error.toString() : error.getMessage())
            .setPositiveButton("OK", null).show();
    }

    /** Create an Android Java/XML application as actual files, not a TODO placeholder. */
    void showNewAndroidProjectWizard(final boolean sample) {
        if (currentDir == null || !currentDir.isDirectory() || owningProject(currentDir) != null) {
            Toast.makeText(this, "Choose an outer project folder first", Toast.LENGTH_LONG).show();
            return;
        }
        final File base = currentDir;
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(dp(22), dp(8), dp(22), dp(4));
        TextView destination = new TextView(this);
        destination.setText("Create project in " + base.getAbsolutePath());
        destination.setTextColor(Color.parseColor("#666666"));
        destination.setTextSize(13);
        fields.addView(destination);
        final android.widget.Spinner language = new android.widget.Spinner(this);
        language.setAdapter(new android.widget.ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_dropdown_item, new String[]{"Java", "Kotlin", "Kotlin + Compose"}));
        fields.addView(language);
        final EditText projectName = new EditText(this);
        projectName.setSingleLine(true);
        projectName.setHint("Project name (e.g. MyApp3)");
        projectName.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        fields.addView(projectName);
        final EditText packageName = new EditText(this);
        packageName.setSingleLine(true);
        packageName.setHint("Package (e.g. com.example.myapp3)");
        packageName.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        fields.addView(packageName);
        TextView hint = new TextView(this);
        hint.setText("Java or Kotlin Activity · XML layout · Gradle files. "
                + "Kotlin + Compose creates a Jetpack Compose (Material 3) app with no XML layout. "
                + "Kotlin builds use the compiler bundled with AideUI; "
                + "Compose also downloads its compiler plugin and libraries on the first build.");
        hint.setTextSize(13);
        fields.addView(hint);
        final android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this,
                android.R.style.Theme_Material_Light_Dialog_Alert)
            .setTitle(sample ? "Create sample Android app" : "New Android App")
            .setView(fields)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Create", null)
            .create();
        dialog.setOnShowListener(new android.content.DialogInterface.OnShowListener() {
            public void onShow(android.content.DialogInterface ignored) {
                dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    public void onClick(View view) {
                        final String name = projectName.getText().toString().trim();
                        final String pkg = packageName.getText().toString().trim();
                        final boolean composeProject = language.getSelectedItemPosition() == 2;
                        final boolean kotlinProject = language.getSelectedItemPosition() >= 1;
                        if (!ProjectTemplate.validProjectName(name)) {
                            projectName.setError("Use letters, digits, underscores or hyphens; start with a letter"); return;
                        }
                        if (!ProjectTemplate.validPackageName(pkg)) {
                            packageName.setError("Enter a valid Java package such as com.example.myapp"); return;
                        }
                        final File target = new File(base, name);
                        if (target.exists()) { projectName.setError("Project already exists"); return; }
                        // A project may already be open while the user is browsing outside it.
                        requestProjectSwitch(target, new Runnable() {
                            public void run() {
                                try {
                                    File created = composeProject
                                            ? ProjectTemplate.createAndroidCompose(MainActivity.this, base, name, pkg)
                                            : kotlinProject
                                            ? ProjectTemplate.createAndroidKotlin(MainActivity.this, base, name, pkg, sample)
                                            : ProjectTemplate.createAndroid(MainActivity.this, base, name, pkg, sample);
                                    dialog.dismiss();
                                    activeProject = created;
                                    bottomMode = MODE_FILES;
                                    currentDir = new File(created, "app/src/main/java/" + pkg.replace('.', '/'));
                                    refresh();
                                    openFileSafely(new File(currentDir, kotlinProject ? "MainActivity.kt" : "MainActivity.java"));
                                    Toast.makeText(MainActivity.this, "Created Android app: " + name, Toast.LENGTH_LONG).show();
                                } catch (java.io.IOException ex) {
                                    // Do not leave an active-project pointer to a failed template.
                                    activeProject = null;
                                    new android.app.AlertDialog.Builder(MainActivity.this,
                                            android.R.style.Theme_Material_Light_Dialog_Alert)
                                        .setTitle("Cannot create project").setMessage(ex.getMessage())
                                        .setPositiveButton("OK", null).show();
                                }
                            }
                        });
                    }
                });
            }
        });
        dialog.show();
    }

    // ---------- Create new Project dialog ----------

    // {category, child1, child2...}; "title|subtitle" for two-line children
    static final String[][] CATEGORIES = {
        {"Android App", "New Android App|Gradle/Android SDK/Java/Xml", "Samples\u2026"},
        {"Mobile Game", "New Mobile Game"},
        {"Java Application", "New Java Application"},
        {"C/C++ Application", "New C/C++ Application"},
        {"Native Android App", "New Native Android App"},
        {"PhoneGap App", "New PhoneGap App"},
        {"Website", "New Website"},
        {"JavaScript Application", "New JavaScript Application"},
    };

    void showCreateProjectDialog() {
        final Dialog d = new Dialog(this, android.R.style.Theme_Material_Light_Dialog);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);

        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        sv.addView(root);

        TextView title = new TextView(this);
        title.setText("Create new Project");
        title.setTextSize(22);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(Color.BLACK);
        title.setPadding(dp(24), dp(24), dp(24), dp(12));
        root.addView(title);

        for (int i = 0; i < CATEGORIES.length; i++)
            addCategory(root, CATEGORIES[i], i == 0, d);

        d.setContentView(sv);
        d.show();
        d.getWindow().setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.93f),
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    void addCategory(LinearLayout root, final String[] cat, boolean expanded, final Dialog d) {
        root.addView(divider());

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(8), 0, dp(8), 0);
        header.setMinimumHeight(dp(56));
        final ImageView chev = new ImageView(this);
        header.addView(chev, new LinearLayout.LayoutParams(dp(32), dp(32)));
        TextView name = new TextView(this);
        name.setText(cat[0]);
        name.setTextSize(20);
        name.setTextColor(Color.parseColor("#444444"));
        name.setPadding(dp(12), 0, 0, 0);
        header.addView(name);
        root.addView(header);

        final LinearLayout children = new LinearLayout(this);
        children.setOrientation(LinearLayout.VERTICAL);
        root.addView(children);

        for (int i = 1; i < cat.length; i++) {
            final String id = cat[i];
            String[] parts = id.split("\\|");
            if (i > 1) children.addView(divider());
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), dp(8), dp(8), dp(8));
            row.setMinimumHeight(dp(56));
            ImageView icon = new ImageView(this);
            icon.setImageResource(R.drawable.ic_logo);
            row.addView(icon, new LinearLayout.LayoutParams(dp(40), dp(40)));
            LinearLayout texts = new LinearLayout(this);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.setPadding(dp(12), 0, 0, 0);
            TextView t = new TextView(this);
            t.setText(parts[0]);
            t.setTextSize(20);
            t.setTextColor(Color.parseColor("#555555"));
            texts.addView(t);
            if (parts.length > 1) {
                TextView s = new TextView(this);
                s.setText(parts[1]);
                s.setTextSize(15);
                s.setTextColor(Color.parseColor("#777777"));
                texts.addView(s);
            }
            row.addView(texts);
            row.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { d.dismiss(); onAction("project:" + id); }
            });
            children.addView(row);
        }

        final boolean[] open = {expanded};
        children.setVisibility(expanded ? View.VISIBLE : View.GONE);
        chev.setImageResource(expanded ? R.drawable.ic_expand_less : R.drawable.ic_expand_more);
        header.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                open[0] = !open[0];
                children.setVisibility(open[0] ? View.VISIBLE : View.GONE);
                chev.setImageResource(open[0] ? R.drawable.ic_expand_less : R.drawable.ic_expand_more);
            }
        });
    }

    View divider() {
        View v = new View(this);
        v.setBackgroundColor(Color.parseColor("#DDDDDD"));
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        return v;
    }

    int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    // ---------- Clone a public Git repository using smart HTTPS ----------

    boolean gitCloning = false;
    BuildSession buildSession;
    android.app.AlertDialog buildDialog;
    TextView buildStatusText, buildNotesText;
    android.widget.ProgressBar buildProgressBar, buildSpinner;

    static class BuildSession {
        final File module; final String variant; final boolean offerInstall;
        final java.util.concurrent.atomic.AtomicBoolean cancel = new java.util.concurrent.atomic.AtomicBoolean(false);
        volatile boolean running = true; volatile int percent = 0;
        volatile String phase = "Preparing build..."; volatile ProjectBuild.Result result; volatile Thread worker;
        BuildSession(File module, String variant, boolean offerInstall) {
            this.module = module; this.variant = variant; this.offerInstall = offerInstall;
        }
    }


    String suggestedGitFolder(String remote) {
        String name = remote == null ? "" : remote.trim();
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        if (name.endsWith(".git")) name = name.substring(0, name.length() - 4);
        if (!name.matches("[A-Za-z0-9_.-]{1,80}") || name.equals(".") || name.equals("..")) return "";
        return name;
    }

    void showCloneGitDialog() {
        if (gitCloning) {
            Toast.makeText(this, "A Git clone is already running", Toast.LENGTH_SHORT).show();
            return;
        }
        final File base = new File(Environment.getExternalStorageDirectory(), "AppProjects");
        if (!base.isDirectory()) {
            Toast.makeText(this, "AppProjects directory is unavailable", Toast.LENGTH_LONG).show();
            return;
        }
        final LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(8), dp(20), dp(4));
        TextView help = new TextView(this);
        help.setText("Clone a public HTTPS Git repository. Private repositories, SSH and Git LFS are not supported.");
        help.setTextSize(14);
        form.addView(help);
        final EditText remoteInput = new EditText(this);
        remoteInput.setSingleLine(true);
        remoteInput.setTextSize(16);
        remoteInput.setHint("https://github.com/owner/repo.git");
        remoteInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        form.addView(remoteInput);
        final EditText folderInput = new EditText(this);
        folderInput.setSingleLine(true);
        folderInput.setHint("Project folder name (optional)");
        folderInput.setTextSize(16);
        form.addView(folderInput);
        TextView path = new TextView(this);
        path.setText("Destination: " + base.getAbsolutePath());
        path.setTextSize(13);
        path.setTextColor(Color.parseColor("#666666"));
        form.addView(path);
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
            .setTitle("Clone Git repository")
            .setView(form)
            .setPositiveButton("Clone", new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface dialog, int which) {
                    String url = remoteInput.getText().toString().trim();
                    String folder = folderInput.getText().toString().trim();
                    if (folder.length() == 0) folder = suggestedGitFolder(url);
                    if (!folder.matches("[A-Za-z0-9_.-]{1,80}") || folder.equals(".") || folder.equals("..") || folder.equalsIgnoreCase(".git")) {
                        Toast.makeText(MainActivity.this, "Use a simple project folder name (letters, numbers, dot, dash or underscore)", Toast.LENGTH_LONG).show();
                        return;
                    }
                    try {
                        url = GitClone.normalizeUrl(url);
                    } catch (Exception err) {
                        Toast.makeText(MainActivity.this, err.getMessage(), Toast.LENGTH_LONG).show();
                        return;
                    }
                    final File dest = new File(base, folder);
                    if (dest.exists()) {
                        Toast.makeText(MainActivity.this, "Destination folder already exists: " + folder, Toast.LENGTH_LONG).show();
                        return;
                    }
                    startGitClone(url, dest);
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    void startGitClone(final String remote, final File destination) {
        if (gitCloning) return;
        gitCloning = true;
        final boolean[] cancelled = {false};
        LinearLayout progressPanel = new LinearLayout(this);
        progressPanel.setOrientation(LinearLayout.VERTICAL);
        progressPanel.setPadding(dp(20), dp(16), dp(20), dp(12));
        final android.widget.ProgressBar spinner = new android.widget.ProgressBar(this);
        LinearLayout.LayoutParams spinnerParams = new LinearLayout.LayoutParams(dp(48), dp(48));
        spinnerParams.gravity = Gravity.CENTER_HORIZONTAL;
        progressPanel.addView(spinner, spinnerParams);
        final TextView progressText = new TextView(this);
        progressText.setText("Connecting to repository...");
        progressText.setTextSize(15);
        progressText.setPadding(0, dp(12), 0, 0);
        progressPanel.addView(progressText);
        final android.app.AlertDialog progressDialog = new android.app.AlertDialog.Builder(this,
            android.R.style.Theme_Material_Light_Dialog_Alert)
            .setTitle("Cloning Git repository")
            .setView(progressPanel)
            .setNegativeButton("Cancel", new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int which) { cancelled[0] = true; }
            })
            .create();
        progressDialog.setCancelable(false);
        progressDialog.show();
        new Thread(new Runnable() {
            public void run() {
                Exception failed = null;
                try {
                    GitClone.cloneRepository(remote, destination, new GitClone.Progress() {
                        long lastUpdate;
                        public boolean cancelled() { return cancelled[0]; }
                        public void update(final String message) {
                            long now = System.currentTimeMillis();
                            if (now - lastUpdate < 170 && !message.startsWith("Completed")) return;
                            lastUpdate = now;
                            runOnUiThread(new Runnable() {
                                public void run() { if (progressDialog.isShowing()) progressText.setText(message); }
                            });
                        }
                    });
                } catch (Exception err) { failed = err; }
                final Exception error = failed;
                runOnUiThread(new Runnable() {
                    public void run() {
                        gitCloning = false;
                        if (progressDialog.isShowing()) progressDialog.dismiss();
                        if (isFinishing()) return;
                        if (error == null) {
                            currentDir = destination;
                            refresh();
                            new android.app.AlertDialog.Builder(MainActivity.this, android.R.style.Theme_Material_Light_Dialog_Alert)
                                .setTitle("Clone completed")
                                .setMessage("Repository: " + destination.getName() + "\nPath: " + destination.getAbsolutePath()
                                    + "\n\nGit history and working files are available in the project folder.")
                                .setPositiveButton("OK", null).show();
                        } else if (cancelled[0]) {
                            Toast.makeText(MainActivity.this, "Git clone cancelled", Toast.LENGTH_LONG).show();
                        } else {
                            new android.app.AlertDialog.Builder(MainActivity.this, android.R.style.Theme_Material_Light_Dialog_Alert)
                                .setTitle("Git clone failed")
                                .setMessage(error.getMessage() == null ? error.toString() : error.getMessage())
                                .setPositiveButton("OK", null).show();
                        }
                    }
                });
            }
        }, "git-https-clone").start();
    }

    // ---------- Project menu: refresh, select variant, publish built APK, close ----------

    File selectedProjectModule() {
        File module = currentModule();
        if (StandaloneApkBuild.isGradleProject(module)) return module;
        File context = currentFile != null ? currentFile.getParentFile() : currentDir;
        module = StandaloneApkBuild.findBuildRoot(context);
        return module;
    }

    String selectedBuildVariant(File module) {
        return getPreferences(MODE_PRIVATE).getString("build_variant:" + module.getAbsolutePath(), "debug");
    }

    /** Refresh Build creates an APK but never installs or launches it. */
    void refreshProjectBuild() {
        final File module = selectedProjectModule();
        if (module == null) {
            new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
                .setTitle("Refresh Build")
                .setMessage("Open a Gradle or classic AIDE Android project with AndroidManifest.xml and src/ first.")
                .setPositiveButton("OK", null).show();
            return;
        }
        if (buildSession != null && buildSession.running) {
            showProjectBuildDialog(buildSession);
            return;
        }
        if (editorDirty && currentFile != null) {
            new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
                .setTitle("Save before building")
                .setMessage("Save the current file before building the APK?")
                .setPositiveButton("Save & Build", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        if (saveEditor(false)) beginProjectBuild(module, false);
                    }
                }).setNegativeButton("Cancel", null).show();
            return;
        }
        beginProjectBuild(module, false);
    }

    void showBuildVariants() {
        final File module = selectedProjectModule();
        if (module == null) {
            Toast.makeText(this, "Open a Gradle module first", Toast.LENGTH_LONG).show();
            return;
        }
        final List<ProjectActions.Variant> variants;
        try { variants = ProjectActions.variants(module); }
        catch (Exception ex) {
            new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
                .setTitle("Build Variants")
                .setMessage(ex.getMessage() + "\n\nThis lightweight parser supports common literal buildTypes and productFlavors blocks.")
                .setPositiveButton("OK", null).show();
            return;
        }
        if (variants.isEmpty()) {
            Toast.makeText(this, "No variants found", Toast.LENGTH_LONG).show(); return;
        }
        CharSequence[] names = new CharSequence[variants.size()];
        int selected = 0;
        String current = selectedBuildVariant(module);
        for (int i = 0; i < variants.size(); i++) {
            names[i] = variants.get(i).name;
            if (variants.get(i).name.equals(current)) selected = i;
        }
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
            .setTitle("Build Variants (" + module.getName() + ")")
            .setSingleChoiceItems(names, selected, new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int which) {
                    final String name = variants.get(which).name;
                    getPreferences(MODE_PRIVATE).edit().putString("build_variant:" + module.getAbsolutePath(), name).apply();
                    d.dismiss();
                    if (currentFile != null && !editorDirty && module.equals(MavenLibraries.moduleFromSource(currentFile)))
                        runCompiler(currentFile);
                }
            })
            .setNegativeButton("Cancel", null).show();
    }

    void showPublishProject() {
        final File module = selectedProjectModule();
        if (module == null) {
            Toast.makeText(this, "Open a Gradle module first", Toast.LENGTH_LONG).show(); return;
        }
        List<File> existing = ProjectActions.releaseApks(module);
        if (StandaloneApkBuild.isClassicProject(module)) {
            File[] classicApks = new File(module, "bin").listFiles();
            if (classicApks != null) for (File apk : classicApks)
                if (apk.isFile() && apk.getName().endsWith("-release-aideui.apk")) existing.add(apk);
        }
        final List<File> release = new ArrayList<File>();
        for (File file : existing) {
            String name = file.getAbsolutePath().toLowerCase(java.util.Locale.US);
            if (name.contains("release")) release.add(file);
        }
        if (release.isEmpty()) {
            new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
                .setTitle("Publish Project")
                .setMessage("No release APK was found in:\n" + new File(module, "build/outputs/apk").getAbsolutePath()
                    + "\n\nBuild a Release APK first (Project → Build Variants → Release, then Refresh Build)."
                    + " Local release keys are device-bound and are not portable publishing keys.")
                .setPositiveButton("OK", null).show();
            return;
        }
        CharSequence[] labels = new CharSequence[release.size()];
        for (int i = 0; i < release.size(); i++) {
            File apk = release.get(i);
            labels[i] = apk.getName() + " (" + (apk.length() / 1024) + " KiB)\n" + apk.getParentFile().getName();
        }
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
            .setTitle("Publish Project - existing release APKs")
            .setMessage("Select an existing Release APK to share. AideUI can locally build and sign one, but cannot upload it to an app store.")
            .setItems(labels, new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int which) { confirmApkShare(release.get(which)); }
            })
            .setNegativeButton("Cancel", null).show();
    }

    void confirmApkShare(final File apk) {
        if (apk.getName().toLowerCase(java.util.Locale.US).contains("unsigned")) {
            new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
                .setTitle("Unsigned release APK")
                .setMessage("This APK appears unsigned. Build a signed Release APK first.")
                .setPositiveButton("OK", null).show();
            return;
        }
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
            .setTitle("Share built APK")
            .setMessage(apk.getName() + "\n\nAideUI has not verified this APK's signing certificate or release readiness. Share only an APK you have built and checked yourself.")
            .setPositiveButton("Share APK", new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int which) { shareExistingApk(apk); }
            })
            .setNegativeButton("Cancel", null).show();
    }

    void shareExistingApk(File apk) {
        try {
            Uri uri = ApkShareProvider.prepareShare(this, apk);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("application/vnd.android.package-archive");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.setClipData(android.content.ClipData.newUri(getContentResolver(), "APK", uri));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, "Share release APK"));
        } catch (Exception ex) {
            new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
                .setTitle("Cannot share APK").setMessage(ex.getMessage()).setPositiveButton("OK", null).show();
        }
    }

    void openOriginalAide() {
        Intent launch = getPackageManager().getLaunchIntentForPackage("com.aide.ui");
        if (launch == null) {
            Toast.makeText(this, "Original AIDE is not installed or cannot be found", Toast.LENGTH_LONG).show();
            return;
        }
        try { startActivity(launch); }
        catch (Exception ex) { Toast.makeText(this, "Could not open AIDE: " + ex.getMessage(), Toast.LENGTH_LONG).show(); }
    }

    void closeCurrentProject() {
        if (editorDirty && currentFile != null) {
            new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
                .setTitle("Close Project")
                .setMessage("Save changes to " + currentFile.getName() + " before closing the project?")
                .setPositiveButton("Save & Close", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int which) {
                        if (saveEditor(false)) closeProjectNow();
                    }
                })
                .setNeutralButton("Discard & Close", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int which) { closeProjectNow(); }
                })
                .setNegativeButton("Cancel", null).show();
            return;
        }
        closeProjectNow();
    }

    void closeProjectNow() {
        if (completionPopup != null) completionPopup.dismiss();
        if (pendingHighlight != null) editorHandler.removeCallbacks(pendingHighlight);
        if (pendingLiveCompile != null) editorHandler.removeCallbacks(pendingLiveCompile);
        closeFindBar();
        editorLoading = true;
        currentFile = null;
        activeProject = null;
        editorDirty = false;
        editorSaved = "";
        openTabs.clear();
        editorText.setText("");
        editorText.clearHistoryToCurrent();
        editorText.setErrors(new ArrayList<JavaAnalyzer.Err>());
        currentErrors.clear();
        searchResultLabels.clear();
        searchResultFiles.clear();
        searchResultLines.clear();
        lastSearchQuery = "";
        bottomMode = MODE_FILES;
        editorLoading = false;
        editorScroll.setVisibility(View.GONE);
        emptyText.setVisibility(View.VISIBLE);
        fileHeader.setVisibility(View.GONE);
        fileHeaderLine.setVisibility(View.GONE);
        titleText.setText("");
        compilerStatus = "";
        ++compilerSequence;
        updateErrorBadge();
        updateUndoRedoButtons();
        updateOpenedTabs();
        if (libraryStatusText != null) libraryStatusText.setVisibility(View.GONE);
        StartupLog.mark("MainActivity initialize folder");
        currentDir = new File(Environment.getExternalStorageDirectory(), "AppProjects");
        refresh();
        android.view.inputmethod.InputMethodManager keyboard =
            (android.view.inputmethod.InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(editorText.getWindowToken(), 0);
        Toast.makeText(this, "Project closed", Toast.LENGTH_SHORT).show();
    }

    // ---------- Run/Build: AIDE-like progress dialog with real Java checks ----------

    void runProjectBuild() {
        if (buildSession != null && buildSession.running) {
            showProjectBuildDialog(buildSession);
            return;
        }
        final File module = selectedProjectModule();
        if (module == null) {
            new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
                .setTitle("Building app...")
                .setMessage("Open a Gradle or classic AIDE Android project with AndroidManifest.xml and src/ first. AideUI will attempt a standalone signed debug APK build.")
                .setPositiveButton("OK", null).show();
            return;
        }
        if (editorDirty && currentFile != null) {
            new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
                .setTitle("Save before building")
                .setMessage("Save changes to " + currentFile.getName() + " before checking this project?")
                .setPositiveButton("Save & Build", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        if (saveEditor(false)) beginProjectBuild(module);
                    }
                }).setNegativeButton("Cancel", null).show();
            return;
        }
        beginProjectBuild(module);
    }

    void beginProjectBuild(File module) {
        beginProjectBuild(module, true);
    }

    /** True when the project has Kotlin sources, can be built here, and the compiler is not installed yet. */
    boolean needsKotlinSetup(File module) {
        try {
            boolean classic = StandaloneApkBuild.isClassicProject(module);
            if (KotlinOnDeviceCompiler.collectSources(module, classic, false).isEmpty()) return false;
            if (StandaloneApkBuild.unsupportedFeatures(module) != null) return false;   // would fail anyway
            if (KotlinToolchain.compiler(this).isFile()) return false;
            return !KotlinOnDeviceCompiler.bundledAvailable(this);   // packaged compiler: no download needed
        } catch (Throwable ignored) { return false; }
    }

    /** One tap: download + convert the compiler, self-test it, keep a backup copy, then start the build. */
    void setUpKotlinAndBuild(final File module, final boolean offerInstall) {
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
            .setTitle("Set up Kotlin")
            .setMessage("This project uses Kotlin. AideUI will now download and prepare the Kotlin compiler "
                + "(about 100 MB, one time), test it, and then build your project automatically. "
                + "Keep the screen on until it finishes.")
            .setPositiveButton("Set up & build", new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int w) { runKotlinAutoSetup(module, offerInstall); }
            }).setNegativeButton("Cancel", null).show();
    }

    void runKotlinAutoSetup(final File module, final boolean offerInstall) {
        final android.app.ProgressDialog dialog = new android.app.ProgressDialog(this);
        dialog.setTitle("Setting up Kotlin");
        dialog.setMessage("Starting…");
        dialog.setCancelable(false);
        dialog.setProgressStyle(android.app.ProgressDialog.STYLE_SPINNER);
        dialog.show();
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        new Thread(new Runnable() {
            public void run() {
                String failure = null;
                final KotlinCompilerInstaller.Log log = new KotlinCompilerInstaller.Log() {
                    public void update(final String m) {
                        runOnUiThread(new Runnable() { public void run() { dialog.setMessage(m); } });
                    }
                };
                try {
                    log.update("Step 1/3: downloading and preparing the compiler…");
                    KotlinCompilerInstaller.install(MainActivity.this, log);
                    log.update("Step 2/3: testing the compiler…");
                    KotlinCompiler.selfTest(MainActivity.this, log);
                    log.update("Step 3/3: saving a backup copy…");
                    try { KotlinToolchain.export(MainActivity.this, false); } catch (Throwable backupFailed) { }
                } catch (Throwable t) {
                    Throwable c = StandaloneApkBuild.unwrap(t);
                    failure = (c.getMessage() != null ? c.getMessage() : c.getClass().getName())
                        + "\n\nProgress is saved: tap Run again to resume.";
                }
                final String error = failure;
                runOnUiThread(new Runnable() {
                    public void run() {
                        dialog.dismiss();
                        getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                        if (error == null) { beginProjectBuild(module, offerInstall); return; }
                        new android.app.AlertDialog.Builder(MainActivity.this)
                            .setTitle("Kotlin setup failed").setMessage(error)
                            .setPositiveButton("OK", null).show();
                    }
                });
            }
        }, "kotlin-auto-setup").start();
    }

    void beginProjectBuild(File module, boolean offerInstall) {
        if (needsKotlinSetup(module)) { setUpKotlinAndBuild(module, offerInstall); return; }
        // Both Refresh Build and Run use this same compiler, packaging and signing pipeline.
        if (buildDialog != null && buildDialog.isShowing()) buildDialog.dismiss();
        buildDialog = null;
        final BuildSession session = new BuildSession(module, selectedBuildVariant(module), offerInstall);
        buildSession = session;
        showProjectBuildDialog(session);
        session.worker = new Thread(null, new Runnable() {
            public void run() {
                final ProjectBuild.Result result = StandaloneApkBuild.build(MainActivity.this, session.module,
                    session.variant, new ProjectBuild.Progress() {
                        long lastUpdate;
                        public boolean isCancelled() { return session.cancel.get(); }
                        public void update(String stage, int percent) {
                            session.phase = stage;
                            session.percent = Math.max(0, Math.min(percent, 100));
                            long now = android.os.SystemClock.uptimeMillis();
                            if (now - lastUpdate < 125 && percent < 100) return;
                            lastUpdate = now;
                            runOnUiThread(new Runnable() {
                                public void run() { renderBuildSession(session); }
                            });
                        }
                    });
                runOnUiThread(new Runnable() {
                    public void run() {
                        session.result = result;
                        session.running = false;
                        session.phase = result.status;
                        session.percent = 100;
                        renderBuildSession(session);
                        if (session == buildSession && result.passed && currentFile != null
                                && !editorDirty && session.module.equals(
                                    StandaloneApkBuild.findBuildRoot(currentFile.getParentFile())))
                            runCompiler(currentFile);
                        if (session == buildSession && !result.cancelled && !result.passed)
                            Toast.makeText(MainActivity.this, result.status, Toast.LENGTH_LONG).show();
                    }
                });
            }
        }, "aideui-project-check", 32L * 1024 * 1024);
        session.worker.start();
    }

    void showProjectBuildDialog(final BuildSession session) {
        if (buildDialog != null && buildDialog.isShowing()) {
            renderBuildSession(session);
            return;
        }
        android.view.inputmethod.InputMethodManager keyboard =
            (android.view.inputmethod.InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(editorText.getWindowToken(), 0);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(22), dp(8), dp(22), dp(2));
        buildStatusText = new TextView(this);
        buildStatusText.setGravity(Gravity.CENTER);
        buildStatusText.setTextColor(Color.parseColor("#666666"));
        buildStatusText.setTextSize(16);
        buildStatusText.setPadding(0, dp(2), 0, dp(4));
        body.addView(buildStatusText, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        buildSpinner = new android.widget.ProgressBar(this);
        row.addView(buildSpinner, new LinearLayout.LayoutParams(dp(40), dp(40)));
        buildProgressBar = new android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        buildProgressBar.setIndeterminate(false);
        buildProgressBar.setMax(100);
        LinearLayout.LayoutParams progressLp = new LinearLayout.LayoutParams(0, dp(6), 1);
        progressLp.leftMargin = dp(12);
        row.addView(buildProgressBar, progressLp);
        body.addView(row, new LinearLayout.LayoutParams(-1, dp(44)));
        buildNotesText = new TextView(this);
        buildNotesText.setText("Local APK build · AAPT → ECJ → D8 → APK signing");
        buildNotesText.setTextSize(12);
        buildNotesText.setTextColor(Color.parseColor("#777777"));
        buildNotesText.setGravity(Gravity.CENTER);
        buildNotesText.setPadding(0, dp(2), 0, dp(4));
        body.addView(buildNotesText);

        buildDialog = new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
            .setTitle(session.offerInstall ? "Building app..." : "Refresh Build...")
            .setView(body)
            .setNeutralButton("Task list", null)
            .setNegativeButton(session.running ? "Stop" : session.offerInstall && session.result != null && session.result.apk != null ? "Install APK" : "Close", null)
            .setPositiveButton("Hide", null)
            .create();
        buildDialog.setCancelable(false);
        buildDialog.show();
        buildDialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showBuildTasks(session); }
        });
        buildDialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (!session.running) {
                    if (session.offerInstall && session.result != null && session.result.apk != null) installBuiltApk(session.result.apk);
                    else if (buildDialog != null) buildDialog.dismiss();
                    return;
                }
                session.cancel.set(true);
                session.phase = "Stopping build...";
                Thread worker = session.worker;
                if (worker != null) worker.interrupt();
                renderBuildSession(session);
            }
        });
        buildDialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { buildDialog.dismiss(); }
        });
        renderBuildSession(session);
    }

    void renderBuildSession(BuildSession session) {
        if (buildDialog == null || !buildDialog.isShowing() || session != buildSession) return;
        if (buildStatusText != null) buildStatusText.setText(session.phase);
        if (buildProgressBar != null) buildProgressBar.setProgress(session.percent);
        if (buildSpinner != null) buildSpinner.setVisibility(session.running ? View.VISIBLE : View.INVISIBLE);
        android.widget.Button action = buildDialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE);
        if (action != null) {
            action.setText(session.running ? "Stop" : session.offerInstall && session.result != null && session.result.apk != null ? "Install APK" : "Close");
            action.setEnabled(!session.running || !session.cancel.get());
        }
        if (buildNotesText != null) {
            // While building there is nothing extra to say: hide the line so it takes no space.
            buildNotesText.setVisibility(session.result == null ? View.GONE : View.VISIBLE);
            if (session.result != null) buildNotesText.setText(session.result.apk != null ?
                (session.offerInstall ? "Signed APK ready · Tap Install APK" :
                 "Signed " + session.variant + " APK saved (no installation): " + session.result.apk.getAbsolutePath()) :
                session.result.sourceCount + " Java files · " + session.result.errors + " errors · "
                + session.result.warnings + " warnings · no APK produced");
        }
    }

    /** Install the in-app signed APK; never launches the original AIDE. */
    void installBuiltApk(File apk) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
                new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
                    .setTitle("Allow APK installation")
                    .setMessage("Allow AideUI to install unknown apps in Android settings, then tap Install APK again.")
                    .setPositiveButton("Open settings", new android.content.DialogInterface.OnClickListener() {
                        public void onClick(android.content.DialogInterface d, int which) {
                            android.content.Intent intent = new android.content.Intent(
                                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                android.net.Uri.parse("package:" + getPackageName()));
                            startActivity(intent);
                        }
                    }).setNegativeButton("Cancel", null).show();
                return;
            }
            android.net.Uri uri = ApkShareProvider.prepareShare(this, apk);
            android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
        } catch (Exception failure) {
            new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
                .setTitle("Cannot install APK")
                .setMessage(failure.toString() + "\n\nAPK: " + apk.getAbsolutePath())
                .setPositiveButton("OK", null).show();
        }
    }

    void showBuildTasks(BuildSession session) {
        String summary;
        if (session.result == null) {
            summary = "Project: " + session.module.getAbsolutePath()
                + "\nVariant: " + session.variant
                + "\n\n1. Compile resources with AAPT\n2. Compile Java sources with ECJ"
                + "\n3. Convert classes to DEX with D8\n4. Package resources and DEX"
                + "\n5. Sign debug APK with AndroidKeyStore\n\nCurrent task: " + session.phase;
        } else summary = session.result.display();
        final String report = summary;
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
            .setTitle("Build tasks & diagnostics")
            .setMessage(summary)
            .setNeutralButton("Copy report", new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int which) {
                    android.content.ClipboardManager clipboard = (android.content.ClipboardManager)
                        getSystemService(Context.CLIPBOARD_SERVICE);
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("AideUI build report", report));
                    Toast.makeText(MainActivity.this, "Build report copied", Toast.LENGTH_SHORT).show();
                }
            })
            .setPositiveButton("OK", null).show();
    }

    // Other unfinished legacy menu actions retain their own TODO handlers.
    void onAction(String id) {
        if ("clone_git".equals(id)) { showCloneGitDialog(); return; }
        if ("project:New Android App|Gradle/Android SDK/Java/Xml".equals(id)) {
            showNewAndroidProjectWizard(false); return;
        }
        if ("project:Samples…".equals(id) || "project:Samples\u2026".equals(id)) {
            showNewAndroidProjectWizard(true); return;
        }
        if (id != null && id.startsWith("project:")) {
            new android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
                .setTitle("Template not installed")
                .setMessage("This template category is not implemented. Choose Android App → New Android App for a supported Java/XML project.")
                .setPositiveButton("OK", null).show();
            return;
        }
        if ("run".equals(id)) { runProjectBuild(); return; }
        Toast.makeText(this, "TODO: " + id, Toast.LENGTH_SHORT).show();
    }
}
