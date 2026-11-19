package net.kdt.pojavlaunch.profiles;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;

/**
 * One latest launch log per launcher profile. A profile launch overwrites only
 * its own latestlog.txt; no unbounded session history is retained.
 */
public final class ProfileLogStore {
    private static final String ROOT_DIR = "profile_logs";
    private static final String LATEST_NAME = "latestlog.txt";
    private static volatile String sActiveProfileKey;

    private ProfileLogStore() {}

    /** Pins the exact profile key passed across the launcher → game process boundary. */
    public static void setActiveProfileKey(@Nullable String profileKey) {
        sActiveProfileKey = profileKey;
    }

    @NonNull
    public static File forCurrentProfile(@NonNull Context context) {
        String key = sActiveProfileKey;
        if (key == null || key.isEmpty()) {
            key = LauncherPreferences.DEFAULT_PREF.getString(
                    LauncherPreferences.PREF_KEY_CURRENT_PROFILE, "default");
        }
        return forProfile(key);
    }

    @NonNull
    public static File forProfile(@Nullable String profileKey) {
        String safe = sanitize(profileKey == null ? "default" : profileKey);
        return new File(new File(new File(Tools.DIR_GAME_HOME, ROOT_DIR), safe), LATEST_NAME);
    }

    @NonNull
    public static File prepareForCurrentProfile(@NonNull Context context) throws IOException {
        File file = forCurrentProfile(context);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("Unable to create profile log directory");
        }
        if (!file.exists() && !file.createNewFile()) {
            throw new IOException("Unable to create profile log");
        }
        return file;
    }

    /**
     * Copies the current latestlog.txt to a sibling {@code last_crash.log} right before
     * the native Logger.begin() truncates it. Without this, a session that died without
     * a clean exit (SIGKILL / LMK, native SIGSEGV) was unrecoverable: the next app start
     * wiped the only copy and the log screen came back empty. Empty files (a launcher-only
     * session that never reached the game) are skipped so they can never overwrite a
     * previously preserved session.
     */
    public static void preservePreviousSession(@NonNull File latest) {
        try {
            if (!latest.isFile() || latest.length() == 0) return;
            File parent = latest.getParentFile();
            if (parent == null) return;
            File preserved = new File(parent, Tools.LAST_CRASH_LOG_NAME);
            try (FileInputStream in = new FileInputStream(latest);
                 FileOutputStream out = new FileOutputStream(preserved, false)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
        } catch (IOException ignored) {
            // Best effort: never block the launch because preservation failed.
        }
    }

    public static long countLines(@NonNull File file) {
        if (!file.isFile() || file.length() == 0) return 0;
        long count = 0;
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            while (reader.readLine() != null) count++;
        } catch (IOException ignored) {}
        return count;
    }

    @NonNull
    private static String sanitize(@NonNull String key) {
        String safe = key.replaceAll("[^A-Za-z0-9._-]", "_");
        if (safe.length() > 80) safe = safe.substring(0, 80);
        return safe.isEmpty() ? "default" : safe;
    }
}