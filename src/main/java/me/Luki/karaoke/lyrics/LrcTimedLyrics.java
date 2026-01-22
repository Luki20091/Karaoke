package me.Luki.karaoke.lyrics;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.List;

public final class LrcTimedLyrics implements TimedLyrics {

    private final List<LrcParser.LrcLine> lines;

    public LrcTimedLyrics(List<LrcParser.LrcLine> lines) {
        this.lines = lines == null ? List.of() : List.copyOf(lines);
    }

    @Override
    public RenderState render(long elapsedMs, NamedTextColor highlight, NamedTextColor past, NamedTextColor future) {
        if (lines.isEmpty()) {
            return new RenderState(
                    Component.text("(brak tekstu)", future),
                    Component.text("", future),
                    Component.text("", future)
            );
        }

        int idx = findCurrentIndex(elapsedMs);

        String prev = idx > 0 ? lines.get(idx - 1).text() : "";
        String cur = idx >= 0 && idx < lines.size() ? lines.get(idx).text() : "";
        String next = (idx + 1) < lines.size() ? lines.get(idx + 1).text() : "";

        Component top = Component.text(prev, past);
        Component mid = progressiveLine(elapsedMs, idx, cur, highlight, future);
        Component bot = Component.text(next, future);
        return new RenderState(top, mid, bot);
    }

    private int findCurrentIndex(long elapsedMs) {
        int lo = 0;
        int hi = lines.size() - 1;
        int best = 0;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            long t = lines.get(mid).timeMs();
            if (t <= elapsedMs) {
                best = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return best;
    }

    private Component progressiveLine(long elapsedMs, int idx, String text, NamedTextColor highlight, NamedTextColor future) {
        if (text == null) {
            text = "";
        }
        if (text.isEmpty()) {
            return Component.text("", future);
        }

        long start = lines.get(Math.max(0, Math.min(idx, lines.size() - 1))).timeMs();
        long end = (idx + 1) < lines.size() ? lines.get(idx + 1).timeMs() : (start + 4000L);
        long span = Math.max(250L, end - start);
        long pos = Math.max(0L, Math.min(span, elapsedMs - start));

        double pct = pos / (double) span;
        int cut = (int) Math.round(pct * text.length());
        cut = Math.max(0, Math.min(text.length(), cut));

        String left = text.substring(0, cut);
        String right = text.substring(cut);

        return Component.text(left, highlight).append(Component.text(right, future));
    }
}
