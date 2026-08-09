package com.eurobuddha.filez;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Filez — a native file manager for the Minima Core node's base folder.
 *
 * Lets you EXPORT files the node terminal creates (backups, archive exports, .txn files)
 * to the Android file system via SAF, and IMPORT documents/files from the phone into the
 * node's base folder so terminal commands (restore, archive import, txnimport…) can use them.
 *
 * All file access goes through the node's ADMIN-gated FILE IPC bridge (node >= 1.3.1).
 */
public class MainActivity extends AppCompatActivity {

    public static final String NODE_PKG = "org.minimarex.minimacore";
    public static final String PANDAAPPS_PKG = "com.eurobuddha.pandaapps";
    private static final String FILEPROVIDER_AUTHORITY = "com.eurobuddha.filez.fileprovider";
    private static final String IMPORT_DIR = "imports";
    private static final String MSG_NO_NODE = "Minima Core didn't respond. Is it installed, running and enabled?";

    private NodeApi node;

    private LinearLayout header;
    private View pairingBanner;
    private TextView pairingTitle, pairingText;
    private TextView title, status, backBtn;
    private RecyclerView recycler;
    private FileAdapter adapter;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private ActivityResultLauncher<String> exportLauncher;
    private ActivityResultLauncher<String[]> importLauncher;

    private String currentPath = "/";
    private String pendingExportPath = null;   // node path awaiting a SAF destination
    private boolean loading = false;
    // Absolute path of the node's base folder (from `status` -> response.data). Terminal
    // commands treat any file: arg containing "/" as a literal filesystem path, so only
    // <nodeRoot> + <relative path> is paste-able into the terminal for subfolder files.
    private String nodeRoot = "";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        header = findViewById(R.id.header);
        title = findViewById(R.id.title);
        status = findViewById(R.id.status);
        backBtn = findViewById(R.id.backBtn);
        recycler = findViewById(R.id.recycler);
        pairingBanner = findViewById(R.id.pairingBanner);
        pairingTitle = findViewById(R.id.pairingTitle);
        pairingText = findViewById(R.id.pairingText);

        applyInsets();

        adapter = new FileAdapter();
        recycler.setLayoutManager(new LinearLayoutManager(this));
        recycler.setAdapter(adapter);

        findViewById(R.id.refreshBtn).setOnClickListener(v -> loadDirectory(currentPath));
        findViewById(R.id.menuBtn).setOnClickListener(this::showMainMenu);
        ((Button) findViewById(R.id.openNodeBtn)).setOnClickListener(v -> openMinimaCore());
        backBtn.setOnClickListener(v -> navigateUp());

        // The SAF destination is chosen FIRST, then the node hands us a content:// of the source.
        exportLauncher = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("application/octet-stream"),
                uri -> { if (uri != null && pendingExportPath != null) doExport(pendingExportPath, uri); });
        importLauncher = registerForActivityResult(new ActivityResultContracts.OpenDocument(),
                uri -> { if (uri != null) doImport(uri); });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (!currentPath.equals("/")) navigateUp();
                else { setEnabled(false); getOnBackPressedDispatcher().onBackPressed(); }
            }
        });

        node = new NodeApi(this, this::onPaired);

        // A crash/rotation mid-import can strand a staged copy - never let the cache grow
        io.execute(() -> {
            File dir = new File(getCacheDir(), IMPORT_DIR);
            File[] leftovers = dir.listFiles();
            if (leftovers != null) for (File f : leftovers) f.delete();
        });

        loadDirectory("/");
    }

    private void applyInsets() {
        final View root = findViewById(R.id.main);
        final int headerTop = header.getPaddingTop();
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            header.setPadding(header.getPaddingLeft(), headerTop + bars.top, header.getPaddingRight(), header.getPaddingBottom());
            recycler.setPadding(recycler.getPaddingLeft(), recycler.getPaddingTop(), recycler.getPaddingRight(), bars.bottom + dp(8));
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
        new WindowInsetsControllerCompat(getWindow(), root).setAppearanceLightStatusBars(false);
    }

    // ---- pairing ----

    private void onPaired(boolean enabled) {
        if (!enabled) {
            // Reset the wording - showAdminBanner/showOldNodeBanner may have rewritten it earlier
            pairingTitle.setText("Filez is not enabled yet");
            pairingText.setText("Open Minima Core → Apps and enable \"Filez\" with Admin (file access needs it), then come back.");
            resetBannerButton();
        }
        pairingBanner.setVisibility(enabled ? View.GONE : View.VISIBLE);
        if (enabled) loadDirectory(currentPath);
    }

    /** FILE verbs need the Admin toggle too — the node tells us in the error text. */
    private void showAdminBanner() {
        pairingTitle.setText("Filez needs Admin access");
        pairingText.setText("File access can read node backups, so it is Admin-gated.\nOpen Minima Core → Apps → Filez and switch on Admin, then refresh.");
        resetBannerButton();
        pairingBanner.setVisibility(View.VISIBLE);
    }

    /** The node answers normal commands but not FILE — it's alive but pre-1.3.1 (no file bridge). */
    private void showOldNodeBanner() {
        pairingTitle.setText("Minima Core update needed");
        pairingText.setText("Your Minima Core is running but doesn't have the file bridge.\nInstall \"Minima Core — New UI (Preview)\" 1.3.1 or newer from PandaApps, then come back.");
        Button btn = findViewById(R.id.openNodeBtn);
        btn.setText("Open PandaApps");
        btn.setOnClickListener(v -> openPandaApps());
        pairingBanner.setVisibility(View.VISIBLE);
    }

    private void resetBannerButton() {
        Button btn = findViewById(R.id.openNodeBtn);
        btn.setText("Open Minima Core");
        btn.setOnClickListener(v -> openMinimaCore());
    }

    /**
     * A FILE call timed out. An old node (no FILE bridge) NEVER answers FILE but answers
     * normal commands fine — probe with a cheap `block` to tell the two cases apart.
     */
    private void onFileTimeout() {
        status.setText("Checking Minima Core version…");
        node.cmd("block", 8000, new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                showOldNodeBanner();
                status.setText("Minima Core needs updating for file access");
            }
            @Override public void onError(String m) {
                // ERR_NOT_ENABLED -> the pairing listener already showed the enable banner
                if (!NodeApi.ERR_NOT_ENABLED.equals(m)) status.setText(MSG_NO_NODE);
            }
        });
    }

    /** Shared error routing for every FILE call. */
    private void handleFileCallError(String m) {
        if (NodeApi.ERR_NOT_ENABLED.equals(m)) return;      // banner shown via pairing listener
        if (NodeApi.ERR_TIMEOUT.equals(m)) { onFileTimeout(); return; }
        status.setText(m);
        toast(m);
    }

    // ---- directory listing ----

    private void fetchNodeRoot() {
        if (!nodeRoot.isEmpty()) return;
        node.cmd("status", new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                JSONObject r = j.optJSONObject("response");
                if (r != null) nodeRoot = r.optString("data", "");
            }
            @Override public void onError(String m) {}   // cosmetic only - retried on next load
        });
    }

    /** The full filesystem path a terminal `file:` argument needs for this entry. */
    private String terminalPathFor(String relPath) {
        if (nodeRoot.isEmpty()) return relPath;
        return nodeRoot + relPath;
    }

    private void copyToClipboard(String label, String value) {
        // Deferred: a write fired while a popup/dialog is dismissing can be dropped during the
        // window-focus transition on some OEMs. Then READ BACK and report truthfully — Samsung /
        // Knox can swallow setPrimaryClip silently (toast used to lie about it).
        ui.postDelayed(() -> {
            boolean ok = false;
            try {
                android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText(label, value));
                android.content.ClipData clip = cm.getPrimaryClip();
                ok = clip != null && clip.getItemCount() > 0
                        && value.contentEquals(String.valueOf(clip.getItemAt(0).coerceToText(this)));
            } catch (Exception ignored) {}
            toast(ok ? "Copied" : "Copy blocked by the system — long-press the path in file details, or use Share path");
        }, 200);
    }


    private void loadDirectory(String path) {
        if (loading) return;
        loading = true;
        fetchNodeRoot();
        status.setText("Loading…");
        node.file("list", path, null, null, new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                loading = false;
                if (!j.optBoolean("status", false)) { onFileError(j); return; }
                pairingBanner.setVisibility(View.GONE);
                // Root for displayed Locations. NOT status->data: Minima appends "1.1" to
                // -data (ParamConfigurer), so that points INSIDE the version folder, one level
                // below the file area (-basefolder / getFilesDir) these listings come from.
                // The bridge reports its own canonical base; status->data is only the fallback
                // for a 1.3.1 node and is one folder off there.
                String base = j.optString("base", "");
                if (!base.isEmpty()) nodeRoot = base;
                currentPath = j.optString("path", path);
                List<FileEntry> entries = new ArrayList<>();
                JSONArray arr = j.optJSONArray("list");
                if (arr != null) for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o != null) entries.add(new FileEntry(o));
                }
                entries.sort(Comparator.comparing((FileEntry e) -> !e.isdir)
                        .thenComparing(e -> e.name.toLowerCase()));
                adapter.setData(entries);
                updateHeader(entries.size());
            }
            @Override public void onError(String m) {
                loading = false;
                handleFileCallError(m);
            }
        });
    }

    private void updateHeader(int count) {
        boolean root = currentPath.equals("/");
        title.setText(root ? "Filez — node folder" : currentPath);
        backBtn.setVisibility(root ? View.GONE : View.VISIBLE);
        status.setText(count + (count == 1 ? " item" : " items")
                + (root ? "  ·  backups, exports & imports live here" : ""));
    }

    private void navigateUp() {
        String parent = Util.parentOf(currentPath);
        if (parent != null) loadDirectory(parent);
    }

    /** A failed FILE response — admin gating gets the banner, the rest a toast + status line. */
    private void onFileError(JSONObject j) {
        String err = j.optString("error", j.optString("response", "File action failed"));
        if (err.contains("ADMIN")) { showAdminBanner(); status.setText("Waiting for Admin access…"); return; }
        status.setText(err);
        Toast.makeText(this, err, Toast.LENGTH_LONG).show();
    }

    // ---- menus ----

    private void showMainMenu(View anchor) {
        PopupMenu m = new PopupMenu(this, anchor);
        m.getMenu().add(0, 1, 0, "Import file here");
        m.getMenu().add(0, 2, 1, "New folder");
        m.getMenu().add(0, 3, 2, "Create node backup");
        m.getMenu().add(0, 4, 3, "Refresh");
        m.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: importLauncher.launch(new String[]{"*/*"}); return true;
                case 2: promptNewFolder(); return true;
                case 3: createBackup(); return true;
                case 4: loadDirectory(currentPath); return true;
                default: return false;
            }
        });
        m.show();
    }

    private void showEntryMenu(View anchor, FileEntry e) {
        PopupMenu m = new PopupMenu(this, anchor);
        if (!e.isdir) m.getMenu().add(0, 1, 0, "Export to phone…");
        m.getMenu().add(0, 4, 1, "Copy path");
        m.getMenu().add(0, 2, 2, "Rename");
        m.getMenu().add(0, 3, 3, "Delete");
        m.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: startExport(e); return true;
                case 4: copyToClipboard("path", terminalPathFor(Util.joinPath(currentPath, e.name))); return true;
                case 2: promptRename(e); return true;
                case 3: confirmDelete(e); return true;
                default: return false;
            }
        });
        m.show();
    }

    /** Mirrors the web Filez File page exactly: Name / Size / File Type / Location sections
     *  and a "Copy path" button that flips to "Copied to clipboard" (2.5s), where
     *  Location = fullPath + location. */
    private void showDetail(FileEntry e) {
        final String location = terminalPathFor(Util.joinPath(currentPath, e.name));

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(12), dp(20), dp(12));

        detailSection(box, "Size", Util.formatBytes(e.size));
        detailSection(box, "File Type", Util.getExtension(e.name));
        detailSection(box, "Location", location);

        // Live self-check: ask the node to resolve the EXACT Location string the way a
        // terminal file: argument resolves, and print the verdict right in the dialog.
        TextView check = new TextView(this);
        check.setTextSize(12f);
        check.setPadding(0, dp(6), 0, dp(6));
        check.setTextColor(FilezDesign.DIM);
        check.setText("Checking path with node…");
        box.addView(check);
        node.file("stat", location, null, null, new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                if (!j.optBoolean("status", false)) { check.setText(""); return; }
                if (j.optBoolean("exists", false)) {
                    check.setTextColor(FilezDesign.SUCCESS);
                    check.setText("✓ Terminal will find this path (" + Util.formatBytes(j.optLong("size", 0)) + ")");
                } else {
                    check.setTextColor(FilezDesign.ERROR);
                    StringBuilder sb = new StringBuilder("✗ Terminal will NOT find this path!");
                    org.json.JSONArray kids = j.optJSONArray("parentlist");
                    if (!j.optBoolean("parentexists", false)) {
                        sb.append("\nThat folder doesn't exist either.");
                    } else if (kids != null) {
                        sb.append("\nThe node sees in that folder: ");
                        for (int i = 0; i < kids.length(); i++) {
                            if (i > 0) sb.append(", ");
                            sb.append('"').append(kids.optString(i)).append('"');
                        }
                    }
                    check.setText(sb.toString());
                }
            }
            @Override public void onError(String m) { check.setText(""); }
        });

        Button copy = new Button(this);
        copy.setText("Copy path");
        copy.setOnClickListener(v -> {
            copyToClipboard("path", location);
            copy.setText("Copied to clipboard");
            ui.postDelayed(() -> copy.setText("Copy path"), 2500);
        });
        box.addView(copy);

        new AlertDialog.Builder(this)
                .setTitle(e.name)
                .setView(box)
                .setPositiveButton("Export to phone…", (d, w) -> startExport(e))
                .setNeutralButton("Close", null)
                .setNegativeButton("Delete", (d, w) -> confirmDelete(e))
                .show();
    }

    private void detailSection(LinearLayout parent, String label, String value) {
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextSize(12f);
        l.setTextColor(FilezDesign.DIM);
        l.setPadding(0, dp(8), 0, dp(2));
        parent.addView(l);
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(14f);
        v.setTextIsSelectable(true);
        parent.addView(v);
    }

    // ---- EXPORT: node base folder -> Android file system (SAF) ----

    private void startExport(FileEntry e) {
        pendingExportPath = Util.joinPath(currentPath, e.name);
        exportLauncher.launch(e.name);
    }

    private void doExport(String nodePath, Uri dest) {
        pendingExportPath = null;
        status.setText("Exporting…");
        node.file("get", nodePath, null, null, new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                if (!j.optBoolean("status", false)) { onFileError(j); return; }
                final String srcUri = j.optString("uri", "");
                final long size = j.optLong("size", 0);
                io.execute(() -> {
                    try {
                        long copied = copyStream(
                                getContentResolver().openInputStream(Uri.parse(srcUri)),
                                getContentResolver().openOutputStream(dest));
                        final String doneMsg = "Exported " + Util.formatBytes(copied);
                        ui.post(() -> { status.setText(doneMsg); toast(doneMsg); updateHeaderSoon(); });
                        if (size > 0 && copied != size) {
                            ui.post(() -> toast("Warning: expected " + size + " bytes, wrote " + copied));
                        }
                    } catch (Exception exc) {
                        ui.post(() -> { status.setText("Export failed"); toast("Export failed: " + exc.getMessage()); });
                    }
                });
            }
            @Override public void onError(String m) { handleFileCallError(m); }
        });
    }

    // ---- IMPORT: Android file system -> node base folder ----

    private void doImport(Uri src) {
        status.setText("Importing…");
        io.execute(() -> {
            File staged = null;
            Uri grantUri = null;
            try {
                String name = Util.safeName(displayNameOf(src));
                if (name.isEmpty()) name = "import_" + System.currentTimeMillis();

                // Stage into cache/imports/ so our FileProvider can serve it to the node
                File dir = new File(getCacheDir(), IMPORT_DIR);
                if (!dir.exists()) dir.mkdirs();
                staged = new File(dir, name);
                copyStream(getContentResolver().openInputStream(src), new FileOutputStream(staged));

                grantUri = FileProvider.getUriForFile(this, FILEPROVIDER_AUTHORITY, staged);
                grantUriPermission(NODE_PKG, grantUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);

                final File fstaged = staged;
                final Uri fgrant = grantUri;
                final String dest = Util.joinPath(currentPath, name);
                ui.post(() -> node.file("put", dest, null, fgrant, new NodeApi.Cb() {
                    @Override public void onResult(JSONObject j) {
                        cleanupImport(fstaged, fgrant);
                        if (!j.optBoolean("status", false)) { onFileError(j); return; }
                        String msg = "Imported " + Util.formatBytes(j.optLong("size", 0));
                        status.setText(msg);
                        toast(msg);
                        loadDirectory(currentPath);
                    }
                    @Override public void onError(String m) {
                        cleanupImport(fstaged, fgrant);
                        handleFileCallError(m);
                    }
                }));
            } catch (Exception exc) {
                cleanupImport(staged, grantUri);
                ui.post(() -> { status.setText("Import failed"); toast("Import failed: " + exc.getMessage()); });
            }
        });
    }

    private void cleanupImport(File staged, Uri grantUri) {
        try { if (grantUri != null) revokeUriPermission(grantUri, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) {}
        try { if (staged != null) staged.delete(); } catch (Exception ignored) {}
    }

    private String displayNameOf(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    String n = c.getString(idx);
                    if (n != null) return n;
                }
            }
        } catch (Exception ignored) {}
        String last = uri.getLastPathSegment();
        return last == null ? "" : last;
    }

    // ---- mkdir / rename / delete / backup ----

    private void promptNewFolder() {
        promptText("New folder", "", name -> {
            String safe = Util.safeName(name);
            if (safe.isEmpty()) return;
            node.file("mkdir", Util.joinPath(currentPath, safe), null, null, simpleRefreshCb("Folder created"));
        });
    }

    private void promptRename(FileEntry e) {
        promptText("Rename " + e.name, e.name, name -> {
            String safe = Util.safeName(name);
            if (safe.isEmpty() || safe.equals(e.name)) return;
            node.file("move", Util.joinPath(currentPath, e.name), Util.joinPath(currentPath, safe), null,
                    simpleRefreshCb("Renamed"));
        });
    }

    private void confirmDelete(FileEntry e) {
        String warn = e.isdir
                ? "Delete the folder \"" + e.name + "\" and EVERYTHING in it from the node?"
                : "Delete \"" + e.name + "\" from the node?";
        new AlertDialog.Builder(this)
                .setTitle("Delete")
                .setMessage(warn)
                .setPositiveButton("Delete", (d, w) ->
                        node.file("delete", Util.joinPath(currentPath, e.name), null, null, simpleRefreshCb("Deleted")))
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Runs the node `backup` terminal command — the .bak lands in the base folder root. */
    private void createBackup() {
        status.setText("Creating backup… (can take a while)");
        node.cmd("backup", new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                if (!j.optBoolean("status", false)) {
                    String err = j.optString("error", j.optString("message", "Backup failed"));
                    status.setText(err); toast(err); return;
                }
                String file = "";
                JSONObject r = j.optJSONObject("response");
                if (r != null) {
                    JSONObject bk = r.optJSONObject("backup");
                    if (bk != null) file = bk.optString("file", "");
                }
                toast(file.isEmpty() ? "Backup created" : "Backup created: " + new File(file).getName());
                loadDirectory("/");
            }
            @Override public void onError(String m) {
                // Plain CMD — an old node answers this fine, so a timeout means unreachable
                String msg = NodeApi.ERR_TIMEOUT.equals(m) ? MSG_NO_NODE : m;
                status.setText(msg);
                toast(msg);
            }
        });
    }

    private NodeApi.Cb simpleRefreshCb(String okMsg) {
        return new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                if (!j.optBoolean("status", false)) { onFileError(j); return; }
                toast(okMsg);
                loadDirectory(currentPath);
            }
            @Override public void onError(String m) { handleFileCallError(m); }
        };
    }

    private void promptText(String titleText, String prefill, TextCb cb) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setText(prefill);
        input.setSelection(prefill.length());
        input.setTextColor(FilezDesign.TEXT);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setPadding(dp(20), dp(8), dp(20), 0);
        wrap.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle(titleText)
                .setView(wrap)
                .setPositiveButton("OK", (d, w) -> cb.text(input.getText().toString()))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private interface TextCb { void text(String value); }

    // ---- adapter ----

    private class FileAdapter extends RecyclerView.Adapter<FileAdapter.VH> {
        private List<FileEntry> data = new ArrayList<>();
        void setData(List<FileEntry> d) { data = d; notifyDataSetChanged(); }

        class VH extends RecyclerView.ViewHolder {
            final TextView glyph, name, meta, more;
            VH(LinearLayout row, TextView g, TextView n, TextView m, TextView mo) {
                super(row); glyph = g; name = n; meta = m; more = mo;
            }
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout row = new LinearLayout(MainActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), dp(12), dp(8), dp(12));
            row.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView glyph = new TextView(MainActivity.this);
            glyph.setTextSize(18f);
            glyph.setWidth(dp(32));
            row.addView(glyph);

            LinearLayout mid = new LinearLayout(MainActivity.this);
            mid.setOrientation(LinearLayout.VERTICAL);
            mid.setPadding(dp(6), 0, dp(6), 0);
            mid.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView name = new TextView(MainActivity.this);
            name.setTextSize(15f);
            name.setTypeface(Typeface.DEFAULT_BOLD);
            name.setTextColor(FilezDesign.TEXT);
            TextView meta = new TextView(MainActivity.this);
            meta.setTextSize(12f);
            meta.setTextColor(FilezDesign.DIM);
            mid.addView(name); mid.addView(meta);
            row.addView(mid);

            TextView more = new TextView(MainActivity.this);
            more.setText("⋮");
            more.setTextSize(18f);
            more.setTextColor(FilezDesign.DIM);
            more.setPadding(dp(14), dp(6), dp(14), dp(6));
            row.addView(more);

            return new VH(row, glyph, name, meta, more);
        }

        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            FileEntry e = data.get(pos);
            h.glyph.setText(e.isdir ? "📁" : "📄");
            h.name.setText(e.name);
            h.meta.setText(e.isdir ? "Folder" : Util.formatBytes(e.size)
                    + (e.modified > 0 ? "  ·  " + Util.formatDate(e.modified) : ""));
            h.itemView.setOnClickListener(v -> {
                if (e.isdir) loadDirectory(Util.joinPath(currentPath, e.name));
                else showDetail(e);
            });
            h.more.setOnClickListener(v -> showEntryMenu(v, e));
        }

        @Override public int getItemCount() { return data.size(); }
    }

    // ---- helpers ----

    private static long copyStream(InputStream is, OutputStream os) throws Exception {
        if (is == null || os == null) throw new IllegalStateException("Could not open stream");
        try {
            long total = 0;
            byte[] buf = new byte[65536];
            int read;
            while ((read = is.read(buf)) != -1) { os.write(buf, 0, read); total += read; }
            os.flush();
            return total;
        } finally {
            try { is.close(); } catch (Exception ignored) {}
            try { os.close(); } catch (Exception ignored) {}
        }
    }

    private void updateHeaderSoon() { ui.postDelayed(() -> updateHeader(adapter.getItemCount()), 1500); }

    private void openMinimaCore() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(NODE_PKG);
        if (launch != null) startActivity(launch);
        else Toast.makeText(this, "Minima Core isn't installed.", Toast.LENGTH_LONG).show();
    }

    private void openPandaApps() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(PANDAAPPS_PKG);
        if (launch != null) startActivity(launch);
        else openMinimaCore();   // no store installed — at least surface the node
    }

    private void toast(String msg) { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show(); }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    @Override
    protected void onResume() { super.onResume(); if (node != null) loadDirectory(currentPath); }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (node != null) node.onDestroy();
        io.shutdownNow();
    }
}
