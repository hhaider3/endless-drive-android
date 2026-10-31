package com.endlessdrive.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Keeps the game up to date with its repository. The build bundles one version
 * of the game; this asks GitHub for the newest commit on the repository's main
 * branch and, when that differs from what the app is running, downloads the
 * game files of that commit into app storage. A downloaded version is only
 * swapped in by {@link #promote()}, which the activity calls while no page is
 * running on the old files, so a drive never sees half of one version and half
 * of another. Only a commit that comes after the running one replaces it, so
 * a build of a game commit GitHub doesn't have yet keeps its own game. The app
 * itself (this wrapper) is not updated: only the game. One per process, as a
 * download can outlive the activity that started it.
 */
final class GameUpdater {
    private static final String TAG = "GameUpdater";
    private static final String REPO = "hhaider3/project-magellan";
    private static final long CHECK_EVERY_MS = 15 * 60 * 1000;
    private static final Pattern SHA = Pattern.compile("[0-9a-f]{40}");
    // What the build copies into the app (app/build.gradle), minus the Blender sources.
    private static final Pattern GAME_FILE = Pattern.compile("index\\.html|[^/]+\\.mjs|style\\.css|manifest\\.webmanifest|(vendor|assets)/.+");

    interface Listener { void onDownloaded(); }

    private static GameUpdater instance;

    private final Context context;
    private final SharedPreferences prefs;
    private final File active, pending, partial;
    private final AtomicBoolean busy = new AtomicBoolean();
    private volatile Listener listener;

    static synchronized GameUpdater get(Context context) {
        if (instance == null) instance = new GameUpdater(context);
        return instance;
    }

    private GameUpdater(Context context) {
        this.context = context.getApplicationContext();
        prefs = this.context.getSharedPreferences("game-updater", Context.MODE_PRIVATE);
        active = new File(this.context.getFilesDir(), "game");
        pending = new File(this.context.getFilesDir(), "game-pending");
        partial = new File(this.context.getFilesDir(), "game-partial");
        dropStaleForNewInstall();
    }

    /** A newly installed app brings its own bundled game: forget any downloaded one, which may be older. */
    private void dropStaleForNewInstall() {
        long build;
        try {
            build = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).getLongVersionCode();
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return;
        }
        if (prefs.getLong("app_build", -1) == build) return;
        deleteTree(active);
        deleteTree(pending);
        deleteTree(partial);
        prefs.edit().clear().putLong("app_build", build).apply();
    }

    /** Who hears of a finished download: the activity in front, if any. */
    void setListener(Listener listener) { this.listener = listener; }

    /** Forgets {@code listener} unless another one has replaced it. */
    void clearListener(Listener listener) { if (this.listener == listener) this.listener = null; }

    /** The downloaded game being served, or null while the bundled one is. */
    File activeDir() { return active.isDirectory() ? active : null; }

    boolean hasPending() { return pending.isDirectory(); }

    /** Makes a finished download the game being served. Only while no page is using the old files. */
    synchronized boolean promote() {
        if (!pending.isDirectory()) return false;
        deleteTree(active);
        if (!pending.renameTo(active)) { // back to the bundled game
            deleteTree(pending);
            prefs.edit().remove("active").remove("pending").apply();
            return false;
        }
        prefs.edit().putString("active", prefs.getString("pending", "")).remove("pending").apply();
        return true;
    }

    /** In the background: look for a newer game and download it. At most one at a time, and not more often than every few minutes. */
    void checkAsync() {
        if (System.currentTimeMillis() - prefs.getLong("checked", 0) < CHECK_EVERY_MS) return;
        if (!busy.compareAndSet(false, true)) return;
        Thread thread = new Thread(() -> {
            try {
                check();
            } catch (IOException e) {
                Log.i(TAG, "update check failed: " + e);
            } finally {
                busy.set(false);
            }
        }, "game-updater");
        thread.setDaemon(true);
        thread.start();
    }

    private void check() throws IOException {
        String latest = latestCommit();
        prefs.edit().putLong("checked", System.currentTimeMillis()).apply();
        String current = currentVersion();
        if (latest.equals(current)) return;
        if (!unmetered()) { Log.i(TAG, "update " + latest + " waits for an unmetered network"); prefs.edit().putLong("checked", 0).apply(); return; }
        if (!comesAfter(latest, current)) { Log.i(TAG, "keeping game " + current + ": main (" + latest + ") is not newer"); return; }
        Log.i(TAG, "downloading game " + latest);
        deleteTree(partial);
        try {
            int files = download(latest);
            if (!new File(partial, "index.html").isFile()) throw new IOException("no index.html among " + files + " files");
        } catch (IOException e) {
            deleteTree(partial);
            throw e;
        }
        synchronized (this) { // never while promote() is moving the previous download
            deleteTree(pending);
            if (!partial.renameTo(pending)) throw new IOException("cannot keep the download");
            prefs.edit().putString("pending", latest).apply();
        }
        Listener listener = this.listener;
        if (listener != null) listener.onDownloaded();
    }

    /** The commit that the newest downloaded game, else the bundled one, was made from. */
    private String currentVersion() {
        String version = prefs.getString("pending", null);
        if (version == null) version = prefs.getString("active", null);
        if (version == null) {
            try (InputStream in = context.getAssets().open("game-version.txt")) {
                version = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            } catch (IOException e) {
                version = "unknown";
            }
        }
        return version;
    }

    private static String latestCommit() throws IOException {
        HttpURLConnection connection = open("https://api.github.com/repos/" + REPO + "/commits/main", "application/vnd.github.sha");
        try (InputStream in = connection.getInputStream()) {
            String sha = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            if (!SHA.matcher(sha).matches()) throw new IOException("unexpected answer: " + sha);
            return sha;
        } finally {
            connection.disconnect();
        }
    }

    /**
     * Whether {@code latest} is a later commit than {@code current} on the
     * repository. A commit GitHub doesn't know (a local build of an unpushed
     * game) answers 404, which fails the check and keeps that game.
     */
    private static boolean comesAfter(String latest, String current) throws IOException {
        if (!SHA.matcher(current).matches()) return true; // a build that doesn't know its game
        HttpURLConnection connection = open("https://api.github.com/repos/" + REPO + "/compare/" + current + "..." + latest + "?per_page=1", "application/vnd.github+json");
        try (InputStream in = connection.getInputStream()) {
            return "ahead".equals(new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8)).optString("status"));
        } catch (JSONException e) {
            throw new IOException(e);
        } finally {
            connection.disconnect();
        }
    }

    /** Unpacks the game files of one commit into {@link #partial}; returns how many files the commit has. */
    private int download(String sha) throws IOException {
        HttpURLConnection connection = open("https://codeload.github.com/" + REPO + "/zip/" + sha, "application/zip");
        connection.setReadTimeout(60_000);
        int total = 0;
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(connection.getInputStream()))) {
            String root = partial.getCanonicalPath() + File.separator;
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                if (entry.isDirectory()) continue;
                total++;
                int slash = entry.getName().indexOf('/'); // the archive wraps everything in "<repo>-<sha>/"
                String path = slash < 0 ? entry.getName() : entry.getName().substring(slash + 1);
                if (!GAME_FILE.matcher(path).matches() || path.contains("..") || path.endsWith(".blend") || path.endsWith(".blend1")) continue;
                File target = new File(partial, path);
                if (!target.getCanonicalPath().startsWith(root)) throw new IOException("bad path " + path);
                File parent = target.getParentFile();
                if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("cannot create " + parent);
                try (OutputStream out = new FileOutputStream(target)) { zip.transferTo(out); }
            }
        } finally {
            connection.disconnect();
        }
        return total;
    }

    private static HttpURLConnection open(String url, String accept) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(15_000);
        connection.setRequestProperty("User-Agent", "endless-drive-android");
        connection.setRequestProperty("Accept", accept);
        if (connection.getResponseCode() != 200) throw new IOException("HTTP " + connection.getResponseCode() + " for " + url);
        return connection;
    }

    /** The game is tens of megabytes: keep it off mobile data. */
    private boolean unmetered() {
        ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
        NetworkCapabilities capabilities = manager.getNetworkCapabilities(manager.getActiveNetwork());
        return capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
    }

    private static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        file.delete();
    }
}
