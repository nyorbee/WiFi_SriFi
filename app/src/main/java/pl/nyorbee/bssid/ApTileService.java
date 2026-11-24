package pl.nyorbee.bssid;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Kafelek w szybkich ustawieniach: nazwa AP, pasmo i siła sygnału. Dotknięcie otwiera apkę. */
public class ApTileService extends TileService {

    @Override
    public void onStartListening() {
        super.onStartListening();
        update();
    }

    @Override
    public void onClick() {
        super.onClick();
        Intent i = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(PendingIntent.getActivity(this, 0, i,
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
            } else {
                startActivityAndCollapse(i);
            }
        } catch (Exception ignored) {
        }
    }

    @SuppressWarnings("deprecation")
    private void update() {
        Tile t = getQsTile();
        if (t == null) return;
        boolean en = MonitorService.en(this);
        String label = null, sub = null;

        // 1. bezpośredni odczyt (działa, gdy system pozwala apce odczytać Wi-Fi)
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            WifiInfo wi = wm.getConnectionInfo();
            String h = wi == null ? null : ApResolver.norm(wi.getBSSID());
            if (h != null && !h.equals("020000000000") && wi.getFrequency() > 0) {
                String band = ApResolver.band(wi.getFrequency());
                String name = new ApResolver(this).nameFor(h, band);
                String ssid = wi.getSSID() == null ? "" : wi.getSSID().replaceAll("^\"|\"$", "");
                label = name != null ? name : (ssid.isEmpty() || ssid.equals("<unknown ssid>") ? (en ? "Unknown AP" : "Nieznany AP") : ssid);
                String bandTxt = "24".equals(band) ? (en ? "2.4 GHz" : "2,4 GHz") : "5".equals(band) ? "5 GHz" : "6".equals(band) ? "6 GHz" : "";
                sub = bandTxt + " · " + ApResolver.percent(wi.getRssi()) + "%";
            }
        } catch (Exception ignored) {
        }

        // 2. ostatni stan z monitorowania w tle (świeży = z ostatnich 10 s)
        if (label == null) {
            SharedPreferences p = ApResolver.prefs(this);
            if (System.currentTimeMillis() - p.getLong("svcTs", 0) < 10000) {
                label = p.getString("svcName", null);
                sub = p.getString("svcBand", "") + " · " + p.getInt("svcPct", 0) + "%";
            }
        }

        if (label != null) {
            t.setState(Tile.STATE_ACTIVE);
            t.setLabel(label);
            if (Build.VERSION.SDK_INT >= 29) t.setSubtitle(sub);
        } else {
            t.setState(Tile.STATE_INACTIVE);
            t.setLabel(getString(R.string.app_name));
            if (Build.VERSION.SDK_INT >= 29) t.setSubtitle(en ? "Tap to open" : "Dotknij, aby otworzyć");
        }
        t.updateTile();
    }
}
