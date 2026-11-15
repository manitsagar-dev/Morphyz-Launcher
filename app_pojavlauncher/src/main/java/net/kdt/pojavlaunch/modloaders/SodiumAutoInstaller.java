package net.kdt.pojavlaunch.modloaders;

import net.kdt.pojavlaunch.Logger;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.modloaders.modpacks.api.ModrinthApi;
import net.kdt.pojavlaunch.modloaders.modpacks.models.Constants;
import net.kdt.pojavlaunch.modloaders.modpacks.models.ModDetail;
import net.kdt.pojavlaunch.modloaders.modpacks.models.ModItem;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.utils.DownloadUtils;

import java.io.File;
import java.util.Locale;

/**
 * One-tap Sodium for newly created Fabric/Quilt instances.
 *
 * The create-profile screen shows a toggle ("Auto-install Sodium") for
 * Fabric/Quilt loaders. When it is on (the default), the loader download
 * task calls {@link #install(File, String, String)} right after the new
 * profile has been written — before handing control back to the UI — so the
 * freshly created instance opens with a matching Sodium build already in its
 * mods/ folder.
 *
 * Everything here is best-effort: a network failure, an unsupported MC
 * version, or a missing Sodium build results in a log line, never a crash —
 * the instance itself is always created.
 */
public final class SodiumAutoInstaller {

    /** Shared pref backing the create-screen toggle (default: enabled). */
    public static final String PREF_KEY_SODIUM_AUTO_INSTALL = "sodium_auto_install_create";

    private SodiumAutoInstaller() {}

    /** Whether the create-screen Sodium toggle is on (default: on). */
    public static boolean isEnabled() {
        return LauncherPreferences.DEFAULT_PREF.getBoolean(PREF_KEY_SODIUM_AUTO_INSTALL, true);
    }

    public static void setEnabled(boolean enabled) {
        LauncherPreferences.DEFAULT_PREF.edit()
                .putBoolean(PREF_KEY_SODIUM_AUTO_INSTALL, enabled)
                .apply();
    }

    /**
     * Resolve the newest Sodium build for the given Minecraft version and
     * loader from Modrinth and place the jar into {@code <gameDir>/mods}.
     * Runs on the calling thread — call from a background thread.
     *
     * @param gameDir    absolute game directory of the freshly created instance
     * @param mcVersion  Minecraft version id, e.g. "1.21.1"
     * @param loaderName "Fabric" / "Quilt" (case-insensitive); anything else is a no-op
     * @return true when the jar was downloaded into mods/
     */
    public static boolean install(File gameDir, String mcVersion, String loaderName) {
        if (gameDir == null || mcVersion == null || loaderName == null) return false;
        final String loader = loaderName.trim().toLowerCase(Locale.ROOT);
        if (!loader.equals("fabric") && !loader.equals("quilt")) return false;
        try {
            ModItem sodium = new ModItem(Constants.SOURCE_MODRINTH, false, "sodium",
                    "Sodium", "CaffeineMC", null, "The fast rendering engine mod", null);
            ModDetail detail = new ModrinthApi().getModDetails(sodium, mcVersion, loader);
            if (detail == null || detail.versionUrls == null || detail.versionUrls.length == 0
                    || detail.versionUrls[0] == null || detail.versionUrls[0].isEmpty()) {
                Logger.appendToLog("Sodium auto-install: no " + loader + " build found for MC " + mcVersion);
                return false;
            }
            final String url = detail.versionUrls[0];
            File modsDir = new File(gameDir, "mods");
            if (!modsDir.isDirectory() && !modsDir.mkdirs()) {
                Logger.appendToLog("Sodium auto-install: could not create " + modsDir.getAbsolutePath());
                return false;
            }
            String filename = url.substring(url.lastIndexOf('/') + 1);
            if (filename.isEmpty() || !filename.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                filename = "sodium-" + mcVersion + "-" + loader + ".jar";
            }
            File dest = new File(modsDir, filename);
            Logger.appendToLog("Sodium auto-install: downloading " + url + " -> " + dest.getAbsolutePath());
            DownloadUtils.downloadFile(url, dest);
            if (!dest.isFile() || dest.length() == 0) {
                //noinspection ResultOfMethodCallIgnored
                dest.delete();
                Logger.appendToLog("Sodium auto-install: download produced an empty/missing jar");
                return false;
            }
            Logger.appendToLog("Sodium auto-install: installed " + filename);
            return true;
        } catch (Throwable t) {
            Logger.appendToLog("Sodium auto-install failed: " + t);
            return false;
        }
    }

    /** Convenience for the loader task: resolve the profile dir, install, notify. Never throws. */
    public static void installForNewProfile(String profileKey,
                                            net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile profile,
                                            String mcVersion, String loaderName) {
        try {
            File gameDir = Tools.getGameDirPath(profile);
            boolean ok = install(gameDir, mcVersion, loaderName);
            if (ok) {
                Tools.runOnUiThread(() -> net.kdt.pojavlaunch.notifications.CsNotifier
                        .success("Sodium installed", "Added a matching build to the new instance"));
            }
        } catch (Throwable t) {
            Logger.appendToLog("Sodium auto-install: " + t);
        }
    }
}
