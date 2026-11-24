package pl.nyorbee.bssid;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rozpoznawanie AP po BSSID w Javie (dla usługi w tle i kafelka).
 * Ta sama logika co identify() w index.html. Lista i ustawienia przychodzą ze strony przez WifiBridge.syncState().
 */
public class ApResolver {

    static final String PREFS = "apid";

    private static final Pattern ADDR = Pattern.compile(
            "(?i)(?<![0-9a-f])(?:[0-9a-f?]{2}[:\\-.]?){5}(?:[0-9a-f?]{2}|[0-9a-f]\\*)(?![0-9a-f])");

    static class Entry {
        String name;
        boolean bssid;      // true: "XX:XX:XX:XX:XX:X*" (rdzeń BSSID), false: MAC urządzenia / dokładny BSSID
        String core;        // 11 cyfr hex (dla bssid)
        String hex;         // 12 znaków, może zawierać '?' (dla mac)
    }

    final List<Entry> entries = new ArrayList<>();
    final boolean aruba;

    ApResolver(Context ctx) {
        SharedPreferences p = prefs(ctx);
        aruba = "aruba".equals(p.getString("mode", "universal"));
        parse(p.getString("list", ""));
    }

    static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private void parse(String text) {
        for (String line : text.split("\\r?\\n")) {
            Matcher m = ADDR.matcher(line);
            if (!m.find()) continue;
            String tok = m.group().replaceAll("[:\\-.]", "").toUpperCase(Locale.ROOT);
            String name = line.replace(m.group(), "")
                    .replaceAll("^[\\s,;\\t\"']+|[\\s,;\\t\"']+$", "")
                    .replaceAll("[\\s,;\\t\"']{2,}", " ");
            if (name.isEmpty()) name = "(?)";
            Entry e = new Entry();
            e.name = name;
            if (tok.endsWith("*")) {
                if (tok.length() != 12 || !tok.substring(0, 11).matches("[0-9A-F]{11}")) continue;
                e.bssid = true;
                e.core = tok.substring(0, 11);
            } else {
                if (tok.length() != 12) continue;
                e.hex = tok;
            }
            entries.add(e);
        }
    }

    /** BSSID w postaci 12 cyfr hex (wielkie litery) albo null. */
    static String norm(String bssid) {
        if (bssid == null) return null;
        String h = bssid.replaceAll("[^0-9a-fA-F]", "").toUpperCase(Locale.ROOT);
        return h.length() == 12 ? h : null;
    }

    static String band(int freq) {
        if (freq >= 2400 && freq < 2500) return "24";
        if (freq >= 4900 && freq < 5900) return "5";
        if (freq >= 5925 && freq < 7200) return "6";
        return null;
    }

    static int channel(int f) {
        if (f >= 2412 && f <= 2472) return (f - 2407) / 5;
        if (f == 2484) return 14;
        if (f >= 5000 && f < 5900) return (f - 5000) / 5;
        if (f >= 5955) return (f - 5950) / 5;
        return 0;
    }

    static int percent(int rssi) {
        return Math.max(0, Math.min(100, Math.round(2f * (rssi + 100))));
    }

    /** Nazwa AP z listy albo null. */
    String nameFor(String h, String band) {
        if (h == null) return null;
        if (aruba && ("24".equals(band) || "5".equals(band))) {
            int known = ((Integer.parseInt(h.substring(6), 16) >> 4) ^ 0x80000) & 0xFFFFF;
            int want = "24".equals(band) ? known : (known - 1) & 0xFFFFF;
            for (Entry e : entries) {
                if (e.bssid || !e.hex.substring(0, 6).equals(h.substring(0, 6))) continue;
                int low = Integer.parseInt(e.hex.substring(6).replace('?', '0'), 16) & 0xFFFFF;
                if (low == want) return e.name;
            }
        }
        for (Entry e : entries) {
            if (e.bssid && h.startsWith(e.core)) return e.name;
            if (!e.bssid && e.hex.indexOf('?') < 0 && e.hex.equals(h)) return e.name;
        }
        return null;
    }

    /** Czy dwa BSSID-y wyglądają na to samo urządzenie (jak w oknie nazywania na stronie). */
    static boolean sameDevice(String a, String b) {
        return a.substring(0, 11).equals(b.substring(0, 11)) || a.substring(2, 10).equals(b.substring(2, 10));
    }
}
