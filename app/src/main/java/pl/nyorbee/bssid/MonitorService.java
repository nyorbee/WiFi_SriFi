package pl.nyorbee.bssid;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.service.quicksettings.TileService;
import android.widget.RemoteViews;

import java.util.List;

/**
 * Monitorowanie w tle: powiadomienie z aktualnym AP, dźwięk/wibracja przy zmianie AP
 * i (opcjonalnie) ostrzeżenie o „przyklejeniu” do słabszego AP. Działa też przy wygaszonym ekranie.
 */
public class MonitorService extends Service {

    static final String CHANNEL = "monitor";
    static final int NOTIF_ID = 7;
    static final String ACTION_STOP = "pl.nyorbee.bssid.STOP";
    static final long POLL_MS = 2000;
    static final long SCAN_MS = 60000;

    private Handler handler;
    private WifiManager wm;
    private PowerManager.WakeLock wakeLock;
    private MediaPlayer player;
    private String lastBssid;
    private String stickyFor;
    private long lastScan;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            try {
                poll();
            } catch (Exception ignored) {
            }
            handler.postDelayed(this, POLL_MS);
        }
    };

    static boolean en(Context c) {
        return "en".equals(ApResolver.prefs(c).getString("lang", "pl"));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL,
                    en(this) ? "Wi-Fi monitoring" : "Monitorowanie Wi-Fi", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            ch.setSound(null, null);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WiFiSriFi:monitor");
            wakeLock.setReferenceCounted(false);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            ApResolver.prefs(this).edit().putBoolean("monitor", false).apply();
            stopSelf();
            return START_NOT_STICKY;
        }
        Notification n = build(en(this) ? "Starting…" : "Uruchamianie…", "", null);
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            } else {
                startForeground(NOTIF_ID, n);
            }
        } catch (Exception e) {
            // np. brak uprawnienia do lokalizacji albo start z tła na Androidzie 14+
            ApResolver.prefs(this).edit().putBoolean("monitor", false).apply();
            stopSelf();
            return START_NOT_STICKY;
        }
        ApResolver.prefs(this).edit().putBoolean("running", true).apply();
        if (wakeLock != null) wakeLock.acquire(12 * 60 * 60 * 1000L);
        handler.removeCallbacks(tick);
        handler.post(tick);
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(tick);
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (player != null) {
            player.release();
            player = null;
        }
        ApResolver.prefs(this).edit().putBoolean("running", false).putLong("svcTs", 0).apply();
        refreshTile();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void poll() {
        SharedPreferences p = ApResolver.prefs(this);
        boolean en = en(this);
        ApResolver r = new ApResolver(this);
        WifiInfo wi = wm.getConnectionInfo();
        String h = wi == null ? null : ApResolver.norm(wi.getBSSID());
        if (h == null || h.equals("020000000000") || wi.getFrequency() <= 0) {
            notify(build(en ? "No Wi-Fi connection" : "Brak połączenia Wi-Fi",
                    en ? "Waiting for Wi-Fi…" : "Czekam na Wi-Fi…", null));
            p.edit().putLong("svcTs", 0).apply();
            refreshTile();
            return;
        }
        int freq = wi.getFrequency(), rssi = wi.getRssi(), pct = ApResolver.percent(rssi);
        String band = ApResolver.band(freq);
        String name = r.nameFor(h, band);
        String ssid = wi.getSSID() == null ? "" : wi.getSSID().replaceAll("^\"|\"$", "");

        if (lastBssid != null && !h.equals(lastBssid)) {
            alert(p.getBoolean("sound", true), p.getBoolean("vib", true));
        }
        lastBssid = h;

        // „przyklejenie”: ta sama sieć na innym urządzeniu jest mocniejsza o 12 dB lub więcej
        String sticky = null;
        if (p.getBoolean("sticky", false)) {
            long now = SystemClock.elapsedRealtime();
            if (now - lastScan > SCAN_MS) {
                lastScan = now;
                try {
                    wm.startScan();
                } catch (Exception ignored) {
                }
            }
            ScanResult best = null;
            try {
                List<ScanResult> list = wm.getScanResults();
                long nowUs = now * 1000;
                for (ScanResult s : list) {
                    String x = ApResolver.norm(s.BSSID);
                    if (x == null || s.SSID == null || !s.SSID.equals(ssid)) continue;
                    if (nowUs - s.timestamp > 90_000_000L) continue;
                    if (ApResolver.sameDevice(x, h)) continue;
                    String xn = r.nameFor(x, ApResolver.band(s.frequency));
                    if (name != null && name.equals(xn)) continue;
                    if (best == null || s.level > best.level) best = s;
                }
            } catch (Exception ignored) {
            }
            if (best != null && rssi <= -65 && best.level - rssi >= 12) {
                String bx = ApResolver.norm(best.BSSID);
                String bn = r.nameFor(bx, ApResolver.band(best.frequency));
                sticky = (en ? "Stronger AP nearby: " : "Silniejszy AP w pobliżu: ")
                        + (bn != null ? bn : best.BSSID.toUpperCase()) + " (" + best.level + " dBm, +" + (best.level - rssi) + " dB)";
                if (!h.equals(stickyFor)) {
                    stickyFor = h;
                    if (p.getBoolean("vib", true)) vibrate(new long[]{0, 300});
                }
            } else {
                stickyFor = null;
            }
        }

        String bandTxt = "24".equals(band) ? (en ? "2.4 GHz" : "2,4 GHz") : "5".equals(band) ? "5 GHz" : "6".equals(band) ? "6 GHz" : freq + " MHz";
        String title = name != null ? name : (en ? "Unknown AP" : "Nieznany AP");
        String text = bandTxt + " · " + (en ? "ch " : "kanał ") + ApResolver.channel(freq) + " · " + pct + "% (" + rssi + " dBm)"
                + (ssid.isEmpty() ? "" : " · " + ssid);
        Notification n = build(title, text, sticky);
        try {
            n = buildCard(title, name != null, band, freq, ssid, rssi, pct, sticky, text);
        } catch (Exception ignored) {
            // gdyby własny wygląd się nie udał, zostaje zwykłe powiadomienie tekstowe
        }
        notify(n);

        p.edit().putString("svcName", title).putString("svcBand", bandTxt).putInt("svcPct", pct)
                .putInt("svcRssi", rssi).putLong("svcTs", System.currentTimeMillis()).apply();
        refreshTile();
    }

    private Notification build(String title, String text, String sticky) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pOpen = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent stop = new Intent(this, MonitorService.class).setAction(ACTION_STOP);
        PendingIntent pStop = PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String body = sticky == null ? text : text + "\n⚠ " + sticky;
        b.setSmallIcon(R.drawable.ic_stat_wifi)
                .setContentTitle(title)
                .setContentText(sticky == null ? text : "⚠ " + sticky)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setContentIntent(pOpen)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_STATUS)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_stat_wifi),
                        en(this) ? "Stop" : "Zatrzymaj", pStop).build());
        if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_LOW);
        return b.build();
    }

    /** Szacowana odległość (ten sam wzór co w apce). */
    static double distM(int rssi, int freq) {
        return Math.pow(10, (27.55 - 20 * Math.log10(freq) + Math.abs(rssi)) / 20);
    }

    static String distTxt(double d, boolean en) {
        if (d < 1) return "≈ < 1 m";
        String v = d < 10 ? String.format(java.util.Locale.ROOT, "%.1f", d) : String.valueOf(Math.round(d));
        return "≈ " + (en ? v : v.replace('.', ',')) + " m";
    }

    static String distLabel(double d, boolean en) {
        if (d < 1) return en ? "Fucking close" : "W chuj blisko";
        if (d < 3) return en ? "Close" : "Blisko";
        if (d < 10) return en ? "Medium" : "Średnio";
        if (d < 25) return en ? "Far" : "Daleko";
        return en ? "Far as fuck" : "Daleko jak ja pierdolę";
    }

    /** Powiadomienie wyglądające jak karta „Na żywo” w apce. */
    private Notification buildCard(String title, boolean known, String band, int freq, String ssid, int rssi, int pct,
                                   String sticky, String plainText) {
        boolean en = en(this);
        int qualColor = pct >= 80 ? 0xFF3DDC84 : pct >= 60 ? 0xFFA6E05A : pct >= 40 ? 0xFFFFCC4D : 0xFFFF5A5A;
        String qual = pct >= 80 ? (en ? "excellent" : "świetny") : pct >= 60 ? (en ? "good" : "dobry")
                : pct >= 40 ? (en ? "weak" : "słaby") : (en ? "very weak" : "bardzo słaby");
        int wifiRes = pct >= 75 ? R.drawable.wifi_lvl3 : pct >= 45 ? R.drawable.wifi_lvl2 : R.drawable.wifi_lvl1;
        int barId = pct >= 80 ? R.id.bar_green : pct >= 60 ? R.id.bar_lime : pct >= 40 ? R.id.bar_yellow : R.id.bar_red;
        double d = distM(rssi, freq);
        int dpos = (int) Math.round(Math.max(0, Math.min(1, (Math.log10(Math.max(0.5, Math.min(50, d))) - Math.log10(0.5)) / 2)) * 100);
        int nameColor = known ? 0xFF3DDC84 : 0xFF8B95A1;
        int bandId = "24".equals(band) ? R.id.band24 : "5".equals(band) ? R.id.band5 : "6".equals(band) ? R.id.band6 : 0;
        String bandLabel = "24".equals(band) ? (en ? "2.4 GHz" : "2,4 GHz") : "5".equals(band) ? "5 GHz" : "6 GHz";

        RemoteViews small = new RemoteViews(getPackageName(), R.layout.notif_small);
        small.setImageViewResource(R.id.wifi, wifiRes);
        small.setTextViewText(R.id.name, title);
        small.setTextColor(R.id.name, nameColor);
        small.setTextViewText(R.id.pct, pct + "%");
        small.setTextColor(R.id.pct, qualColor);
        small.setTextViewText(R.id.dist, distTxt(d, en));
        if (bandId != 0) {
            small.setViewVisibility(bandId, android.view.View.VISIBLE);
            small.setTextViewText(bandId, bandLabel);
        }

        RemoteViews big = new RemoteViews(getPackageName(), R.layout.notif_big);
        big.setTextViewText(R.id.name, title);
        big.setTextColor(R.id.name, nameColor);
        big.setTextViewText(R.id.sub, (en ? "channel " : "kanał ") + ApResolver.channel(freq) + (ssid.isEmpty() ? "" : " · " + ssid));
        big.setTextViewText(R.id.pct, pct + "%");
        big.setTextColor(R.id.pct, qualColor);
        big.setTextViewText(R.id.qual, qual);
        big.setTextColor(R.id.qual, qualColor);
        big.setImageViewResource(R.id.wifi, wifiRes);
        big.setTextViewText(R.id.dbm, rssi + " dBm");
        big.setViewVisibility(barId, android.view.View.VISIBLE);
        big.setProgressBar(barId, 100, pct, false);
        big.setProgressBar(R.id.distbar, 100, dpos, false);
        big.setTextViewText(R.id.dist, distTxt(d, en));
        big.setTextViewText(R.id.distlabel, distLabel(d, en));
        if (bandId != 0) {
            big.setViewVisibility(bandId, android.view.View.VISIBLE);
            big.setTextViewText(bandId, bandLabel);
        }
        if (sticky != null) {
            big.setViewVisibility(R.id.sticky, android.view.View.VISIBLE);
            big.setTextViewText(R.id.sticky, "⚠ " + sticky);
        }

        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pOpen = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent stop = new Intent(this, MonitorService.class).setAction(ACTION_STOP);
        PendingIntent pStop = PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        b.setSmallIcon(R.drawable.ic_stat_wifi)
                .setContentTitle(title)
                .setContentText(plainText)
                .setCustomContentView(small)
                .setCustomBigContentView(big)
                .setStyle(new Notification.DecoratedCustomViewStyle())
                .setContentIntent(pOpen)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_STATUS)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_stat_wifi),
                        en ? "Stop" : "Zatrzymaj", pStop).build());
        return b.build();
    }

    private void notify(Notification n) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ID, n);
    }

    private void alert(boolean sound, boolean vib) {
        if (sound) {
            try {
                if (player != null) player.release();
                player = MediaPlayer.create(this, R.raw.roam, new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(), 0);
                if (player != null) {
                    player.setOnCompletionListener(mp -> {
                        mp.release();
                        if (player == mp) player = null;
                    });
                    player.start();
                }
            } catch (Exception ignored) {
            }
        }
        if (vib) vibrate(new long[]{0, 70, 90, 70});
    }

    private void vibrate(long[] pattern) {
        try {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null || !v.hasVibrator()) return;
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createWaveform(pattern, -1));
            else v.vibrate(pattern, -1);
        } catch (Exception ignored) {
        }
    }

    private void refreshTile() {
        try {
            TileService.requestListeningState(this, new ComponentName(this, ApTileService.class));
        } catch (Exception ignored) {
        }
    }
}
