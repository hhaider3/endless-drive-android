package com.endlessdrive.app;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Display;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import android.window.OnBackInvokedDispatcher;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Endless Drive in a full-screen WebView. The game is bundled in the app's
 * assets (copied from its repository at build time) and served from an https
 * origin of its own, so its ES modules, module worker and import map load as
 * they do from a web server. The point of the app over the browser: it can ask
 * the display for its fastest refresh rate (Chrome on Android keeps pages at
 * 60 Hz on many phones), so a 120 Hz screen runs as fast as the game can.
 * A newer game from the repository replaces the bundled one (see GameUpdater).
 */
public class MainActivity extends Activity {
    private static final String HOST = "appassets.androidplatform.net";
    private static final String START = "https://" + HOST + "/index.html";
    private static final Pattern RANGE = Pattern.compile("bytes=(\\d*)-(\\d*)");
    private WebView web;
    private GameUpdater updater;
    private final GameUpdater.Listener onDownloaded = () -> runOnUiThread(this::updateWhenIdle);
    private volatile File gameDir; // the downloaded game being served; null while the bundled one is

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowManager.LayoutParams attributes = window.getAttributes();
        attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        requestFastestRefresh(attributes);
        window.setAttributes(attributes);

        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) WebView.setWebContentsDebuggingEnabled(true); // chrome://inspect
        web = new WebView(this);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);               // settings and records (localStorage)
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setSupportZoom(false);
        web.setWebViewClient(new GameClient());
        web.setWebChromeClient(new WebChromeClient()); // page console messages reach logcat
        // Android 15+: ask for a high frame rate while the game draws, not
        // just the panel's fastest mode (variable-rate screens drop otherwise).
        if (Build.VERSION.SDK_INT >= 35) web.setRequestedFrameRate(View.REQUESTED_FRAME_RATE_CATEGORY_HIGH);
        setContentView(web);
        hideSystemBars();

        // Back pauses a drive; on the menu it leaves the game running in the
        // background rather than closing it.
        if (Build.VERSION.SDK_INT >= 33) getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::back);

        updater = GameUpdater.get(this);
        updater.setListener(onDownloaded);
        updater.promote();
        gameDir = updater.activeDir();
        if (state == null || web.restoreState(state) == null) web.loadUrl(START);
        updater.checkAsync();
    }

    /** The display's fastest refresh rate at its current resolution. */
    private void requestFastestRefresh(WindowManager.LayoutParams attributes) {
        Display display = getDisplay();
        if (display == null) return;
        Display.Mode current = display.getMode(), fastest = current;
        for (Display.Mode mode : display.getSupportedModes()) {
            if (mode.getPhysicalWidth() == current.getPhysicalWidth() && mode.getPhysicalHeight() == current.getPhysicalHeight() && mode.getRefreshRate() > fastest.getRefreshRate()) fastest = mode;
        }
        attributes.preferredDisplayModeId = fastest.getModeId();
        attributes.preferredRefreshRate = fastest.getRefreshRate();
    }

    private void hideSystemBars() {
        Window window = getWindow();
        window.setDecorFitsSystemWindows(false);
        WindowInsetsController controller = window.getInsetsController();
        if (controller == null) return;
        controller.hide(WindowInsets.Type.systemBars());
        controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    }

    private void back() {
        web.evaluateJavascript("(() => { const b = document.getElementById('pauseBtn'); if (b && !b.hidden) { b.click(); return 'paused'; } return 'menu'; })()",
            result -> { if (!"\"paused\"".equals(result)) moveTaskToBack(true); });
    }

    /**
     * Loads a downloaded game while no drive has started: reloading would lose
     * it, paused or not (the game shows its stats row only once a drive pauses).
     * Otherwise the new game loads when the app next starts.
     */
    private void updateWhenIdle() {
        if (isDestroyed() || !updater.hasPending()) return;
        web.evaluateJavascript("(() => { const p = document.getElementById('pauseBtn'), s = document.getElementById('menuStats'); return !!p && !!s && p.hidden && s.hidden; })()", result -> {
            if (!"true".equals(result) || isDestroyed() || !updater.promote()) return;
            gameDir = updater.activeDir();
            web.loadUrl(START);
            Toast.makeText(this, R.string.game_updated, Toast.LENGTH_SHORT).show();
        });
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() { back(); } // before Android 13

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    @Override
    protected void onResume() { super.onResume(); web.onResume(); updateWhenIdle(); updater.checkAsync(); }

    @Override
    protected void onPause() { web.onPause(); super.onPause(); } // the game pauses itself when hidden

    @Override
    protected void onSaveInstanceState(Bundle state) { super.onSaveInstanceState(state); web.saveState(state); }

    @Override
    protected void onDestroy() { updater.clearListener(onDownloaded); web.destroy(); super.onDestroy(); }

    /**
     * Serves the bundled game at https://appassets.androidplatform.net/; anything else (web fonts) goes to the network.
     * Answers byte ranges as a web server does: the music streams through <audio>, which asks for the part
     * of a track it needs whenever it seeks or has let go of what it buffered, and given the whole file
     * from the start instead it stalls (the longest track, first).
     */
    private final class GameClient extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            Uri url = request.getUrl();
            if (!HOST.equals(url.getHost())) return null;
            String path = url.getPath();
            if (path == null || path.isEmpty() || path.equals("/")) path = "/index.html";
            if (path.contains("..")) return missing();
            String mime = mimeOf(path), encoding = mime.startsWith("text/") || mime.contains("json") ? "utf-8" : null;
            Map<String, String> headers = new HashMap<>();
            headers.put("Cache-Control", "no-cache");
            try {
                long length = lengthOf(path);
                if (length < 0) return new WebResourceResponse(mime, encoding, 200, "OK", headers, open(path));
                headers.put("Accept-Ranges", "bytes");
                Matcher range = RANGE.matcher(rangeOf(request));
                if (!range.matches() || range.group(1).isEmpty() && range.group(2).isEmpty()) {
                    headers.put("Content-Length", Long.toString(length));
                    return new WebResourceResponse(mime, encoding, 200, "OK", headers, open(path));
                }
                long first, last;
                if (range.group(1).isEmpty()) { // the last n bytes
                    first = Math.max(0, length - Long.parseLong(range.group(2)));
                    last = length - 1;
                } else {
                    first = Long.parseLong(range.group(1));
                    last = range.group(2).isEmpty() ? length - 1 : Math.min(length - 1, Long.parseLong(range.group(2)));
                }
                if (first >= length || first > last) {
                    headers.put("Content-Range", "bytes */" + length);
                    return new WebResourceResponse(mime, encoding, 416, "Range Not Satisfiable", headers, new ByteArrayInputStream(new byte[0]));
                }
                InputStream data = open(path);
                skipFully(data, first);
                headers.put("Content-Range", "bytes " + first + "-" + last + "/" + length);
                headers.put("Content-Length", Long.toString(last - first + 1));
                return new WebResourceResponse(mime, encoding, 206, "Partial Content", headers, new Limited(data, last - first + 1));
            } catch (IOException | NumberFormatException e) {
                return missing();
            }
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return !HOST.equals(request.getUrl().getHost()); // the game never leaves its own pages
        }

        /** A file of the downloaded game when there is one (all of it, never a mix), else of the bundled game. */
        private InputStream open(String path) throws IOException {
            File root = gameDir;
            if (root == null) return getAssets().open("game" + path);
            File file = new File(root, path);
            if (!file.isFile()) throw new IOException("not in the game: " + path);
            return new FileInputStream(file);
        }

        /** The file's size, or -1 for a bundled file packed compressed (not media), whose size isn't known without reading it. */
        private long lengthOf(String path) throws IOException {
            File root = gameDir;
            if (root != null) {
                File file = new File(root, path);
                if (!file.isFile()) throw new IOException("not in the game: " + path);
                return file.length();
            }
            try (AssetFileDescriptor fd = getAssets().openFd("game" + path)) {
                return fd.getLength();
            } catch (FileNotFoundException e) { // compressed, or missing (open() tells which)
                return -1;
            }
        }

        private WebResourceResponse missing() {
            return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", Collections.emptyMap(), new ByteArrayInputStream(new byte[0]));
        }
    }

    private static String rangeOf(WebResourceRequest request) {
        for (Map.Entry<String, String> header : request.getRequestHeaders().entrySet()) {
            if ("range".equalsIgnoreCase(header.getKey())) return header.getValue().trim();
        }
        return "";
    }

    private static void skipFully(InputStream in, long count) throws IOException {
        while (count > 0) {
            long skipped = in.skip(count);
            if (skipped <= 0) {
                if (in.read() < 0) throw new IOException("past the end");
                skipped = 1;
            }
            count -= skipped;
        }
    }

    /** The first {@code left} bytes of a stream. */
    private static final class Limited extends FilterInputStream {
        private long left;

        Limited(InputStream in, long left) { super(in); this.left = left; }

        @Override
        public int read() throws IOException {
            if (left <= 0) return -1;
            int b = super.read();
            if (b >= 0) left--;
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int count) throws IOException {
            if (left <= 0) return -1;
            int n = super.read(buffer, offset, (int) Math.min(count, left));
            if (n > 0) left -= n;
            return n;
        }

        @Override
        public long skip(long count) throws IOException {
            long n = super.skip(Math.min(count, left));
            left -= n;
            return n;
        }

        @Override
        public int available() throws IOException { return (int) Math.min(super.available(), left); }
    }

    /** Module scripts must arrive as JavaScript, or the browser refuses them. */
    private static String mimeOf(String path) {
        String name = path.toLowerCase();
        if (name.endsWith(".html")) return "text/html";
        if (name.endsWith(".mjs") || name.endsWith(".js")) return "text/javascript";
        if (name.endsWith(".css")) return "text/css";
        if (name.endsWith(".json")) return "application/json";
        if (name.endsWith(".webmanifest")) return "application/manifest+json";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".svg")) return "image/svg+xml";
        if (name.endsWith(".glb")) return "model/gltf-binary";
        if (name.endsWith(".m4a")) return "audio/mp4";
        return "application/octet-stream";
    }
}
