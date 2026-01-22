package me.Luki.karaoke.lyrics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LrcParser {

    private static final Pattern TAG = Pattern.compile("\\[(\\d{1,2}):(\\d{2})(?:\\.(\\d{1,3}))?]\\s*(.*)");

    private LrcParser() {
    }

    public static List<LrcLine> parse(String lrc) {
        List<LrcLine> out = new ArrayList<>();
        if (lrc == null || lrc.isBlank()) {
            return out;
        }

        String[] lines = lrc.replace("\r", "").split("\n");
        for (String raw : lines) {
            if (raw == null) {
                continue;
            }
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }

            // Support multiple timestamps in one line: [00:10.00][00:12.00] text
            int idx = 0;
            List<Long> times = new ArrayList<>();
            while (true) {
                int open = line.indexOf('[', idx);
                int close = line.indexOf(']', idx);
                if (open != idx || close < 0) {
                    break;
                }
                String tag = line.substring(open, close + 1);
                Matcher m = TAG.matcher(tag + " ");
                if (!m.find()) {
                    break;
                }
                long t = parseTimeToMs(m.group(1), m.group(2), m.group(3));
                times.add(t);
                idx = close + 1;
            }
            String text = line.substring(Math.min(idx, line.length())).trim();
            if (times.isEmpty()) {
                continue;
            }
            for (long t : times) {
                out.add(new LrcLine(t, text));
            }
        }

        out.sort(Comparator.comparingLong(LrcLine::timeMs));
        return out;
    }

    private static long parseTimeToMs(String mm, String ss, String frac) {
        int m = safeInt(mm);
        int s = safeInt(ss);
        int ms = 0;
        if (frac != null && !frac.isBlank()) {
            String f = frac.toLowerCase(Locale.ROOT).trim();
            if (f.length() == 1) {
                ms = safeInt(f) * 100;
            } else if (f.length() == 2) {
                ms = safeInt(f) * 10;
            } else {
                ms = safeInt(f.substring(0, Math.min(3, f.length())));
            }
        }
        return (m * 60_000L) + (s * 1000L) + ms;
    }

    private static int safeInt(String v) {
        try {
            return Integer.parseInt(v);
        } catch (Exception e) {
            return 0;
        }
    }

    public record LrcLine(long timeMs, String text) {
    }
}
