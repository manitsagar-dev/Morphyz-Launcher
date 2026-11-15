package net.kdt.pojavlaunch;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import androidx.annotation.NonNull;

/**
 * The launcher's community links in ONE place.
 *
 * <p>The About page and the home brand cluster all
 * point at the same channels; keeping the URLs here means one edit updates
 * every button. {@link #open} tries the native app first (the Discord app
 * claims the invite URL) and falls back to the browser.
 */
public final class CsLinks {

    /** Official Discord invite. */
    public static final String DISCORD = "https://discord.gg/DRw67Y7PhY";
    /** Official website. */
    public static final String WEBSITE = "https://morphyzlauncher.vercel.app";
    /** Source repository. */
    public static final String GITHUB = "https://github.com/manitsagar-dev/Morphyz-Launcher";

    private CsLinks() {}

    public static void open(@NonNull Context ctx, @NonNull String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(ctx, "No app can open this link", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(ctx, "Could not open link", Toast.LENGTH_SHORT).show();
        }
    }
}
