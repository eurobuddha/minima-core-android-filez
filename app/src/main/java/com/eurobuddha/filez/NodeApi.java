package com.eurobuddha.filez;

import android.app.Activity;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;
import org.minimarex.minimaapi.MinimaAPI;
import org.minimarex.minimaapi.MinimaAPIListener;

/**
 * Thin wrapper around the Minima Core native IPC SDK.
 *
 * - Holds the single {@link MinimaAPI} instance (which auto-registers this app with the node).
 * - Runs node commands and delivers the result back ON THE MAIN THREAD (the raw SDK callback
 *   arrives on the broadcast-receiver thread), with a timeout so a missing/stopped node
 *   surfaces an error instead of hanging.
 * - Detects the "app not enabled in Minima Core" reply and routes it to a pairing listener
 *   so the UI can show the approve-me banner.
 *
 * Filez addition: {@link #file} drives the node's FILE bridge (list/get/put/mkdir/move/delete
 * on the node's base folder). Needs node >= 1.3.1 AND the Admin toggle for this app.
 */
public class NodeApi {

    public interface Cb {
        void onResult(JSONObject json);
        void onError(String message);
    }

    public interface PairingListener {
        void onEnabled(boolean enabled);
    }

    /** Returned as the error message when the node says we are not enabled yet. */
    public static final String ERR_NOT_ENABLED = "NOT_ENABLED";

    /** Returned when the node never answered within the timeout — caller maps it to user text
     *  (an old node without the FILE bridge produces exactly this on every FILE call). */
    public static final String ERR_TIMEOUT = "TIMEOUT";

    private static final long READ_TIMEOUT_MS = 30000;
    private static final long WRITE_TIMEOUT_MS = 180000;   // backup / PoW / big file copies are slow on mobile

    /** Backup and file-copy commands can take a long time on a phone; reads are quick. */
    private static long timeoutFor(String command) {
        String c = command == null ? "" : command.trim();
        if (c.startsWith("backup") || c.startsWith("archive") || c.startsWith("send")
                || c.startsWith("txnsign") || c.startsWith("txnpost")) {
            return WRITE_TIMEOUT_MS;
        }
        return READ_TIMEOUT_MS;
    }

    private final MinimaAPI mApi;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final PairingListener mPairing;
    private final Context mContext;
    // Pending timeout Runnables (main-thread only) so they can be cancelled on destroy.
    private final java.util.HashSet<Runnable> mPending = new java.util.HashSet<>();
    private boolean mReleased = false;

    public NodeApi(Context ctx, PairingListener pairing) {
        mContext = ctx;
        mPairing = pairing;
        // Constructing MinimaAPI auto-sends the REGISTER broadcast; the reply tells us
        // whether the user has enabled this app in Minima Core -> Apps yet.
        mApi = new MinimaAPI(ctx, new MinimaAPIListener() {
            @Override
            public void response(JSONObject zResponse) {
                final boolean enabled = zResponse.optBoolean("enabled", false);
                mMain.post(() -> {
                    if (dead()) return;
                    if (mPairing != null) mPairing.onEnabled(enabled);
                });
            }
        });
    }

    /** True once the hosting Activity is gone — don't deliver callbacks into dead views. */
    private boolean dead() {
        return mContext instanceof Activity
                && (((Activity) mContext).isFinishing() || ((Activity) mContext).isDestroyed());
    }

    public void cmd(String command, Cb cb) {
        cmd(command, timeoutFor(command), cb);
    }

    /** Explicit-timeout variant — used for the short old-node probe. */
    public void cmd(String command, long timeoutMs, Cb cb) {
        if (mReleased) return;
        final boolean[] done = {false};
        final Runnable timeout = armTimeout(timeoutMs, done, cb);
        mApi.Command(command, listenerFor(timeout, done, cb));
    }

    /**
     * FILE bridge — operate on the node's base folder.
     *
     * @param action  list | get | put | mkdir | move | delete
     * @param path    path relative to the node base folder ("/" = root)
     * @param newPath move only — destination path, else null
     * @param uri     put only — a content:// uri already granted to the node package, else null
     */
    public void file(String action, String path, String newPath, Uri uri, Cb cb) {
        if (mReleased) return;
        // get/put stream real file bytes — give them the long timeout
        long ms = ("put".equals(action) || "get".equals(action)) ? WRITE_TIMEOUT_MS : READ_TIMEOUT_MS;
        final boolean[] done = {false};
        final Runnable timeout = armTimeout(ms, done, cb);
        mApi.FileCommand(action, path, newPath, uri, listenerFor(timeout, done, cb));
    }

    // ---- shared timeout + delivery plumbing ----

    private Runnable armTimeout(long ms, boolean[] done, Cb cb) {
        final Runnable[] ref = new Runnable[1];
        final Runnable timeout = () -> {
            mPending.remove(ref[0]);
            if (done[0] || dead()) return;
            done[0] = true;
            if (cb != null) cb.onError(ERR_TIMEOUT);
        };
        ref[0] = timeout;
        mPending.add(timeout);
        mMain.postDelayed(timeout, ms);
        return timeout;
    }

    private MinimaAPIListener listenerFor(Runnable timeout, boolean[] done, Cb cb) {
        return new MinimaAPIListener() {
            @Override
            public void response(JSONObject zResponse) {
                mMain.post(() -> {
                    if (done[0]) return;
                    done[0] = true;
                    mMain.removeCallbacks(timeout);
                    mPending.remove(timeout);
                    if (dead()) return;   // cleaned up above; just don't touch dead views

                    // "enabled":false only appears on the gating reply; real command
                    // responses omit the key, so default true.
                    if (!zResponse.optBoolean("enabled", true)) {
                        if (mPairing != null) mPairing.onEnabled(false);
                        if (cb != null) cb.onError(ERR_NOT_ENABLED);
                        return;
                    }
                    if (cb != null) cb.onResult(zResponse);
                });
            }
        };
    }

    public void onDestroy() {
        mReleased = true;
        for (Runnable r : mPending) mMain.removeCallbacks(r);
        mPending.clear();
        if (mApi != null) mApi.onDestroy();
    }
}
