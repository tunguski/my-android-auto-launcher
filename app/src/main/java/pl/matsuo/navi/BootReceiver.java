package pl.matsuo.navi;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Brings the launcher up when the unit finishes booting.
 *
 * <p>Two things go wrong without this, and they turn out to be the same thing. A head unit that has
 * been parked for days cold-boots rather than waking, and these ROMs start their own radio app once
 * Android is up, so the black screen is not what the driver sees first. Worse, the GPS engine only
 * starts when {@link MainActivity} starts the recording service - so if the driver pulls away
 * before reaching the launcher, the first minutes of the drive are never recorded at all.
 *
 * <p>Starting the activity, rather than the service directly, is deliberate. From Android 14 a
 * foreground service of type {@code location} started with no visible activity has no business
 * accessing location, and would be refused; started by a visible activity, as it always has been,
 * it keeps recording while the navigation app is in front. So the activity comes up and starts the
 * service exactly as it does when the driver opens it by hand - only sooner, while the seatbelt is
 * still being fastened.
 *
 * <p>Whether a ROM lets a background app start an activity at all is another matter. Android has
 * restricted that since version 10, and these units vary. If it is refused, nothing happens and
 * nothing breaks, which is why every call here is guarded and why the unit's own power-on app
 * setting is the more reliable lever.
 */
public class BootReceiver extends BroadcastReceiver {

    /** Lets the driver stop the launcher from claiming the screen after a boot. */
    static final String PREF_BOOT_HOME = "boot_home";

    @Override
    public void onReceive(Context c, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }
        if (!c.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
                .getBoolean(PREF_BOOT_HOME, true)) {
            return;
        }
        try {
            Intent home = new Intent(c, MainActivity.class);
            home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(home);
        } catch (Throwable ignored) {
            // A ROM that refuses background activity starts leaves the driver
            // where they would have been anyway.
        }
    }
}
