package pl.nyorbee.bssid;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.DhcpInfo;
import android.net.Uri;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.MediaStore;
import android.provider.Settings;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Most między stroną (index.html) a Androidem. Każda metoda jest wołana z JavaScriptu jako Android.nazwa(). */
public class WifiBridge {

    private final MainActivity act;
    private final WifiManager wm;
    private MediaPlayer player;

    WifiBridge(MainActivity act) {
        this.act = act;
        this.wm = (WifiManager) act.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
    }

    void release() {
        if (player != null) {
            player.release();
            player = null;
        }
    }

    private static String ip(int a) {
        if (a == 0) return "";
        return (a & 0xff) + "." + ((a >> 8) & 0xff) + "." + ((a >> 16) & 0xff) + "." + ((a >> 24) & 0xff);
    }

    /** Stan aplikacji: uprawnienie, lokalizacja, Wi-Fi, wersja Androida. */
    @JavascriptInterface
    public String status() {
        try {
            JSONObject o = new JSONObject();
            o.put("perm", act.hasLocationPermission());
            boolean loc = true;
            if (Build.VERSION.SDK_INT >= 28) {
                LocationManager lm = (LocationManager) act.getSystemService(Context.LOCATION_SERVICE);
                loc = lm != null && lm.isLocationEnabled();
            }
            o.put("location", loc);
            o.put("wifi", wm != null && wm.isWifiEnabled());
            o.put("sdk", Build.VERSION.SDK_INT);
            o.put("version", BuildConfig.VERSION_NAME);
            o.put("maker", Build.MANUFACTURER == null ? "" : Build.MANUFACTURER);
            o.put("brand", Build.BRAND == null ? "" : Build.BRAND);
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    /** Aktualne połączenie Wi-Fi jako JSON. */
    @JavascriptInterface
    public String info() {
        try {
            JSONObject o = new JSONObject();
            WifiInfo wi = wm.getConnectionInfo();
            if (wi == null) return "{}";
            o.put("bssid", wi.getBSSID() == null ? "" : wi.getBSSID());
            o.put("ssid", wi.getSSID() == null ? "" : wi.getSSID());
            o.put("freq", wi.getFrequency());
            o.put("rssi", wi.getRssi());
            o.put("link", wi.getLinkSpeed());
            if (Build.VERSION.SDK_INT >= 29) {
                o.put("tx", wi.getTxLinkSpeedMbps());
                o.put("rx", wi.getRxLinkSpeedMbps());
            }
            if (Build.VERSION.SDK_INT >= 30) {
                o.put("maxTx", wi.getMaxSupportedTxLinkSpeedMbps());
                o.put("maxRx", wi.getMaxSupportedRxLinkSpeedMbps());
                o.put("std", wi.getWifiStandard());
            }
            o.put("ip", ip(wi.getIpAddress()));
            DhcpInfo d = wm.getDhcpInfo();
            if (d != null) {
                o.put("gw", ip(d.gateway));
                o.put("dns", ip(d.dns1));
            }
            return o.toString();
        } catch (Exception e) {
            return "{\"error\":" + JSONObject.quote(e.toString()) + "}";
        }
    }

    /** Prosi system o nowy skan sieci. Android ogranicza to do ok. 4 skanów na 2 minuty. */
    @JavascriptInterface
    public boolean scan() {
        try {
            return wm.startScan();
        } catch (Exception e) {
            return false;
        }
    }

    /** Wyniki ostatniego skanu jako tablica JSON. */
    @JavascriptInterface
    public String scanResults() {
        try {
            JSONArray a = new JSONArray();
            List<ScanResult> list = wm.getScanResults();
            long nowUs = android.os.SystemClock.elapsedRealtime() * 1000;
            for (ScanResult r : list) {
                JSONObject o = new JSONObject();
                o.put("b", r.BSSID);
                o.put("s", r.SSID == null ? "" : r.SSID);
                o.put("f", r.frequency);
                o.put("l", r.level);
                o.put("w", r.channelWidth);
                o.put("age", Math.max(0, (nowUs - r.timestamp) / 1000000));
                if (Build.VERSION.SDK_INT >= 30) o.put("std", r.getWifiStandard());
                a.put(o);
            }
            return a.toString();
        } catch (Exception e) {
            return "[]";
        }
    }

    /** Dźwięk i/lub wibracja przy przełączeniu na inny AP. */
    @JavascriptInterface
    public void roamAlert(boolean sound, boolean vibrate) {
        act.runOnUiThread(() -> {
            if (sound) {
                try {
                    release();
                    player = MediaPlayer.create(act, R.raw.roam, new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(), 0);
                    if (player != null) {
                        player.setOnCompletionListener(mp -> release());
                        player.start();
                    }
                } catch (Exception ignored) {
                }
            }
            if (vibrate) {
                try {
                    Vibrator v = (Vibrator) act.getSystemService(Context.VIBRATOR_SERVICE);
                    long[] pattern = {0, 70, 90, 70};
                    if (v != null && v.hasVibrator()) {
                        if (Build.VERSION.SDK_INT >= 26) {
                            v.vibrate(VibrationEffect.createWaveform(pattern, -1));
                        } else {
                            v.vibrate(pattern, -1);
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        });
    }

    /** Ekran nie gaśnie, dopóki apka jest na wierzchu. */
    @JavascriptInterface
    public void keepScreenOn(boolean on) {
        act.runOnUiThread(() -> {
            if (on) act.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            else act.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        });
    }

    /** Zapisuje tekst jako plik w folderze Pobrane (Android 10+). Zwraca "OK", "OLD" albo opis błędu. */
    @JavascriptInterface
    public String saveTxt(String name, String content) {
        if (Build.VERSION.SDK_INT < 29) return "OLD";
        try {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/");
            ContentResolver cr = act.getContentResolver();
            Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) return "insert failed";
            try (OutputStream out = cr.openOutputStream(uri)) {
                if (out == null) return "open failed";
                out.write(content.getBytes(StandardCharsets.UTF_8));
            }
            return "OK";
        } catch (Exception e) {
            return e.toString();
        }
    }

    /** Systemowe „Udostępnij” z tekstem listy. */
    @JavascriptInterface
    public void shareTxt(String content) {
        act.runOnUiThread(() -> {
            try {
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("text/plain");
                i.putExtra(Intent.EXTRA_TEXT, content);
                i.putExtra(Intent.EXTRA_SUBJECT, "AP list");
                act.startActivity(Intent.createChooser(i, null));
            } catch (Exception ignored) {
            }
        });
    }

    /** Wybór pliku .txt do importu (wynik trafia do importTxt() na stronie). */
    @JavascriptInterface
    public void pickTxt() {
        act.runOnUiThread(act::pickTxt);
    }

    /** Lista AP i ustawienia ze strony -> pamięć Androida (dla usługi w tle i kafelka). */
    @JavascriptInterface
    public void syncState(String list, String settingsJson) {
        try {
            JSONObject o = new JSONObject(settingsJson);
            ApResolver.prefs(act).edit()
                    .putString("list", list == null ? "" : list)
                    .putString("mode", o.optString("mode", "universal"))
                    .putString("lang", o.optString("lang", "pl"))
                    .putBoolean("sound", o.optBoolean("sound", true))
                    .putBoolean("vib", o.optBoolean("vib", true))
                    .putBoolean("sticky", o.optBoolean("sticky", false))
                    .apply();
        } catch (Exception ignored) {
        }
    }

    /** Włącza / wyłącza monitorowanie w tle (usługa z powiadomieniem). */
    @JavascriptInterface
    public void setMonitoring(boolean on) {
        act.runOnUiThread(() -> {
            ApResolver.prefs(act).edit().putBoolean("monitor", on).apply();
            Intent i = new Intent(act, MonitorService.class);
            if (on) {
                if (Build.VERSION.SDK_INT >= 33
                        && act.checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
                    act.requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, MainActivity.REQ_NOTIF);
                }
                try {
                    if (Build.VERSION.SDK_INT >= 26) act.startForegroundService(i);
                    else act.startService(i);
                } catch (Exception e) {
                    ApResolver.prefs(act).edit().putBoolean("monitor", false).apply();
                }
            } else {
                act.stopService(i);
            }
        });
    }

    /** Czy monitorowanie w tle jest włączone (np. po zatrzymaniu z powiadomienia). */
    @JavascriptInterface
    public boolean isMonitoring() {
        return ApResolver.prefs(act).getBoolean("monitor", false);
    }

    /** Ponowna prośba o uprawnienie albo, gdy odmówiono na stałe, ustawienia aplikacji. */
    @JavascriptInterface
    public void askPermission() {
        act.runOnUiThread(() -> {
            if (act.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)
                    || act.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                act.requestPermissions(new String[]{
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION}, MainActivity.REQ_PERM);
            } else {
                openSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, true);
            }
        });
    }

    @JavascriptInterface
    public void openLocationSettings() {
        act.runOnUiThread(() -> openSettings(Settings.ACTION_LOCATION_SOURCE_SETTINGS, false));
    }

    @JavascriptInterface
    public void openWifiSettings() {
        act.runOnUiThread(() -> openSettings(Settings.ACTION_WIFI_SETTINGS, false));
    }

    private void openSettings(String action, boolean forThisApp) {
        try {
            Intent i = new Intent(action);
            if (forThisApp) i.setData(Uri.parse("package:" + act.getPackageName()));
            act.startActivity(i);
        } catch (Exception ignored) {
        }
    }
}
