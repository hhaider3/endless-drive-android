package com.endlessdrive.app;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
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
import android.window.OnBackInvokedDispatcher;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;

/**
 * Endless Drive in a full-screen WebView. The game is bundled in the app's
 * assets (copied from its repository at build time) and served from an https
 * origin of its own, so its ES modules, module worker and import map load as
 * they do from a web server. The point of the app over the browser: it can ask
 * the display for its fastest refresh rate (Chrome on Android keeps pages at
 * 60 Hz on many phones), so a 120–165 Hz screen runs as fast as the game can.
 */
public class MainActivity extends Activity {
    private static final String HOST = "appassets.androidplatform.net";
    private static final String START = "https://" + HOST + "/index.html";
    private WebView web;

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

        if (state == null || web.restoreState(state) == null) web.loadUrl(START);
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

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() { back(); } // before Android 13

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    @Override
    protected void onResume() { super.onResume(); web.onResume(); }

    @Override
    protected void onPause() { web.onPause(); super.onPause(); } // the game pauses itself when hidden

    @Override
    protected void onSaveInstanceState(Bundle state) { super.onSaveInstanceState(state); web.saveState(state); }

    @Override
    protected void onDestroy() { web.destroy(); super.onDestroy(); }

    /** Serves the bundled game at https://appassets.androidplatform.net/; anything else (web fonts) goes to the network. */
    private final class GameClient extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            Uri url = request.getUrl();
            if (!HOST.equals(url.getHost())) return null;
            String path = url.getPath();
            if (path == null || path.isEmpty() || path.equals("/")) path = "/index.html";
            if (path.contains("..")) return missing();
            String mime = mimeOf(path);
            try {
                InputStream data = getAssets().open("game" + path);
                return new WebResourceResponse(mime, mime.startsWith("text/") || mime.contains("json") ? "utf-8" : null, 200, "OK",
                    Collections.singletonMap("Cache-Control", "no-cache"), data);
            } catch (IOException e) {
                return missing();
            }
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return !HOST.equals(request.getUrl().getHost()); // the game never leaves its own pages
        }

        private WebResourceResponse missing() {
            return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", Collections.emptyMap(), new ByteArrayInputStream(new byte[0]));
        }
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
        return "application/octet-stream";
    }
}
