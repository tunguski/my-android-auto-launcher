package pl.matsuo.navi;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;

/**
 * Records the driven route in the background.
 *
 * <p>It has to be a foreground service: from Android 8 on, an app that is not visible gets only a
 * few location updates an hour, and the whole point is to keep recording while Google Maps is in
 * front.
 */
public class TripService extends Service {

    private static final String CHANNEL = "trip";
    private static final int NOTIFICATION_ID = 1;

    /** One fix every ten seconds. */
    private static final long INTERVAL_MS = 10_000L;

    private LocationManager lm;
    private LocationListener listener;
    private TripDb db;
    private android.content.SharedPreferences prefs;

    /**
     * Imports the CSV trips of earlier versions, if any are still there.
     *
     * <p>On a thread of its own: it is quick, but it is file work, and nothing about starting to
     * record should wait for it. It costs one directory listing once everything has been imported.
     */
    private void importOldTripsInTheBackground() {
        new Thread(
                        () -> {
                            try {
                                Migration.run(Migration.csvDir(TripService.this), db);
                            } catch (Throwable ignored) {
                                // Old files are a bonus; failing to read them must
                                // never stop the unit from recording today's drive.
                            }
                        },
                        "csv-import")
                .start();
    }

    public static void start(Context c) {
        Intent i = new Intent(c, TripService.class);
        try {
            c.startForegroundService(i);
        } catch (Exception ignored) {
            // Some ROMs refuse; the launcher must keep working regardless.
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        db = TripDb.of(this);
        prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        Trip.resume(db, System.currentTimeMillis()); // carry on after a process restart
        createChannel();
        startInForeground();
        importOldTripsInTheBackground();

        lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) {
            return;
        }
        // An anonymous class, not a lambda: on API 26 LocationListener has no
        // default methods yet.
        listener =
                new LocationListener() {
                    @Override
                    public void onLocationChanged(Location location) {
                        Trip.record(db, location);
                        updateNotification();
                        // Only finished trips are ever uploaded, so this never
                        // touches the drive in progress, and it never blocks:
                        // it hands the work to its own thread or does nothing.
                        BackupRunner.trigger(TripService.this, prefs);
                    }

                    @Override
                    public void onProviderEnabled(String p) {}

                    @Override
                    public void onProviderDisabled(String p) {}

                    @Override
                    public void onStatusChanged(String p, int status, Bundle extras) {}
                };
        try {
            lm.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, INTERVAL_MS, 0, listener, Looper.getMainLooper());
        } catch (SecurityException | IllegalArgumentException ignored) {
            // No permission or no GPS provider on this unit.
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (lm != null && listener != null) {
            try {
                lm.removeUpdates(listener);
            } catch (Exception ignored) {
                // Already gone.
            }
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) {
            return;
        }
        NotificationChannel ch =
                new NotificationChannel(
                        CHANNEL,
                        getString(R.string.notif_channel),
                        NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    private void startInForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                    NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(NOTIFICATION_ID, notification());
        }
    }

    private void updateNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, notification());
        }
    }

    private Notification notification() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(
                        getString(
                                R.string.notif_text,
                                Format.distance(Trip.meters),
                                Format.duration(
                                        Trip.startedAt == 0
                                                ? 0
                                                : System.currentTimeMillis() - Trip.startedAt)))
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }
}
