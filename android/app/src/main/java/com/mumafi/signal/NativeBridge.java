package com.mumafi.signal;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

import java.io.OutputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Saving an export to disk.
 *
 * A WebView cannot download a blob: URL — there is no DownloadListener path for
 * it, and the anchor-with-download trick the web build uses silently does
 * nothing. So the page hands the bytes over here instead, in chunks, and they go
 * straight into MediaStore's Downloads collection. On API 29+ that needs no
 * storage permission and the file shows up in the user's Downloads folder and in
 * every file manager.
 *
 * Chunking matters: a mastered WAV can be tens of megabytes, and moving that
 * across the JavaScript bridge as one base64 string would mean holding the file
 * three times over in memory at once.
 */
public class NativeBridge {

    private static final class Pending {
        Uri uri;
        OutputStream stream;
        String displayName;
    }

    private final Activity activity;
    private final Map<String, Pending> open = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    NativeBridge(Activity activity) {
        this.activity = activity;
    }

    @JavascriptInterface
    public String beginFile(String displayName, String mimeType) {
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, displayName);
            values.put(MediaStore.Downloads.MIME_TYPE,
                    mimeType == null || mimeType.isEmpty() ? "application/octet-stream" : mimeType);
            values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            // Marked pending until the last chunk lands, so nothing tries to read
            // a half-written master.
            values.put(MediaStore.Downloads.IS_PENDING, 1);

            ContentResolver resolver = activity.getContentResolver();
            Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) return "";

            Pending pending = new Pending();
            pending.uri = uri;
            pending.stream = resolver.openOutputStream(uri);
            pending.displayName = displayName;
            if (pending.stream == null) {
                resolver.delete(uri, null, null);
                return "";
            }

            String id = "f" + sequence.incrementAndGet();
            open.put(id, pending);
            return id;
        } catch (Exception e) {
            return "";
        }
    }

    @JavascriptInterface
    public boolean writeChunk(String id, String base64Chunk) {
        Pending pending = open.get(id);
        if (pending == null) return false;
        try {
            pending.stream.write(Base64.decode(base64Chunk, Base64.DEFAULT));
            return true;
        } catch (Exception e) {
            abortFile(id);
            return false;
        }
    }

    @JavascriptInterface
    public boolean endFile(String id) {
        Pending pending = open.remove(id);
        if (pending == null) return false;
        try {
            pending.stream.flush();
            pending.stream.close();
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.IS_PENDING, 0);
            activity.getContentResolver().update(pending.uri, values, null, null);
            toast(activity.getString(R.string.saved_to_downloads, pending.displayName));
            return true;
        } catch (Exception e) {
            discard(pending);
            return false;
        }
    }

    @JavascriptInterface
    public void abortFile(String id) {
        Pending pending = open.remove(id);
        if (pending != null) discard(pending);
    }

    @JavascriptInterface
    public void reportFailure() {
        toast(activity.getString(R.string.save_failed));
    }

    private void discard(Pending pending) {
        try { if (pending.stream != null) pending.stream.close(); } catch (Exception ignored) { }
        try { activity.getContentResolver().delete(pending.uri, null, null); } catch (Exception ignored) { }
    }

    private void toast(String message) {
        activity.runOnUiThread(() -> Toast.makeText(activity, message, Toast.LENGTH_LONG).show());
    }
}
