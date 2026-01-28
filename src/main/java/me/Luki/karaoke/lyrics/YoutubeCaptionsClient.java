package me.Luki.karaoke.lyrics;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.Luki.karaoke.Karaoke;
import me.Luki.karaoke.util.ProgressListener;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetches YouTube captions (manual or auto) for a video and converts them into LRC.
 *
 * Notes:
 * - Captions availability depends on the specific video.
 * - The implementation relies on data embedded in the YouTube watch page.
 */
public final class YoutubeCaptionsClient {

    private static final Pattern VTT_TIME = Pattern.compile("^(\\d{2}:\\d{2}(?::\\d{2})?[\\.,]\\d{3})\\s*-->\\s*(\\d{2}:\\d{2}(?::\\d{2})?[\\.,]\\d{3}).*$");
    private static final Pattern XML_TEXT_CUE = Pattern.compile("<text[^>]*?\\bstart=\"([0-9.]+)\"[^>]*>(.*?)</text>", Pattern.DOTALL);
    private static final Pattern XML_P_CUE = Pattern.compile("<p[^>]*?\\bt=\"(\\d+)\"[^>]*>(.*?)</p>", Pattern.DOTALL);

    private final Karaoke plugin;
    private final HttpClient http;

    public YoutubeCaptionsClient(Karaoke plugin) {
        this.plugin = plugin;
        int timeoutSeconds = Math.max(1, plugin.getConfig().getInt("lyrics.youtubeCaptions.timeoutSeconds", plugin.getConfig().getInt("lyrics.timeoutSeconds", 15)));
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public String fetchLrc(String videoUrl, List<String> preferredLanguages, boolean allowFallbackLanguage, ProgressListener progress) {
        try {
            return fetchLrcInternal(videoUrl, preferredLanguages, allowFallbackLanguage, progress);
        } catch (Exception e) {
            plugin.debug().warn("YouTube captions fetch failed; ignoring", e);
            return null;
        }
    }

    private String fetchLrcInternal(String videoUrl, List<String> preferredLanguages, boolean allowFallbackLanguage, ProgressListener progress) throws Exception {
        String id = extractVideoId(videoUrl);
        if (id == null || id.isBlank()) {
            return null;
        }

        // Try watch page first (gives us the exact captionTracks baseUrl list).
        // Some environments (GDPR/consent pages) may hide ytInitialPlayerResponse.
        String watchUrl = "https://www.youtube.com/watch?v=" + id + "&hl=en&gl=US";
        String html = httpGetToString(watchUrl, progress, 3L * 1024L * 1024L);
        if (html == null || html.isBlank()) {
            // Fallback: try timedtext direct.
            return fetchDirectTimedTextLrc(id, preferredLanguages, allowFallbackLanguage, progress);
        }

        String playerJson = extractPlayerResponseJson(html);
        if (playerJson == null || playerJson.isBlank()) {
            return fetchDirectTimedTextLrc(id, preferredLanguages, allowFallbackLanguage, progress);
        }

        JsonObject root;
        try {
            root = JsonParser.parseString(playerJson).getAsJsonObject();
        } catch (Exception e) {
            return null;
        }

        JsonObject captions = getObj(root, "captions");
        JsonObject tracklist = captions != null ? getObj(captions, "playerCaptionsTracklistRenderer") : null;
        JsonArray tracks = tracklist != null ? getArr(tracklist, "captionTracks") : null;
        if (tracks == null || tracks.size() == 0) {
            return fetchDirectTimedTextLrc(id, preferredLanguages, allowFallbackLanguage, progress);
        }

        CaptionTrack chosen = chooseTrack(tracks, preferredLanguages, allowFallbackLanguage);
        if (chosen == null || chosen.baseUrl == null || chosen.baseUrl.isBlank()) {
            return null;
        }

        String captionsUrl = forceFmtVtt(chosen.baseUrl);
        String body = httpGetToString(captionsUrl, progress, 2L * 1024L * 1024L);
        String lrc = parseCaptionsBodyToLrc(body);
        if (lrc != null && !lrc.isBlank()) {
            return lrc;
        }

        // Some signed captionTrack URLs can return HTML/empty/blocked responses.
        // Try direct timedtext endpoints as a last resort.
        return fetchDirectTimedTextLrc(id, preferredLanguages, allowFallbackLanguage, progress);
    }

    /**
     * Fallback path that doesn't require parsing the watch HTML.
     * Tries YouTube timedtext endpoints directly for preferred languages (manual first, then ASR).
     */
    private String fetchDirectTimedTextLrc(String videoId, List<String> preferredLanguages, boolean allowFallbackLanguage, ProgressListener progress) throws Exception {
        List<String> prefs = preferredLanguages != null ? preferredLanguages : List.of();
        List<String> langs = new ArrayList<>();
        for (String p : prefs) {
            String n = normalizeLang(p);
            if (n != null && !n.isBlank() && !langs.contains(n)) {
                langs.add(n);
            }
        }
        if (langs.isEmpty()) {
            // Sensible defaults for this plugin.
            langs.add("pl");
            langs.add("en");
        }

        // Try manual then ASR for preferred languages.
        for (String lang : langs) {
            String vtt = tryTimedText(videoId, lang, false, progress);
            if (looksLikeVtt(vtt)) {
                String lrc = vttToLrc(vtt);
                if (lrc != null && !lrc.isBlank()) {
                    return lrc;
                }
            }
            vtt = tryTimedText(videoId, lang, true, progress);
            if (looksLikeVtt(vtt)) {
                String lrc = vttToLrc(vtt);
                if (lrc != null && !lrc.isBlank()) {
                    return lrc;
                }
            }
        }

        if (!allowFallbackLanguage) {
            return null;
        }

        // Fallback: try a couple of common languages even if not preferred.
        for (String lang : List.of("en", "pl")) {
            if (langs.contains(lang)) {
                continue;
            }
            String vtt = tryTimedText(videoId, lang, true, progress);
            if (looksLikeVtt(vtt)) {
                String lrc = vttToLrc(vtt);
                if (lrc != null && !lrc.isBlank()) {
                    return lrc;
                }
            }
        }

        return null;
    }

    private String tryTimedText(String videoId, String lang, boolean asr, ProgressListener progress) throws Exception {
        String base = "https://www.youtube.com/api/timedtext?v=" + videoId + "&lang=" + lang;
        if (asr) {
            base += "&kind=asr";
        }
        base += "&fmt=vtt";
        return httpGetToString(base, progress, 2L * 1024L * 1024L);
    }

    private static boolean looksLikeVtt(String body) {
        if (body == null) {
            return false;
        }
        String t = body.trim();
        if (t.isBlank()) {
            return false;
        }
        // Many VTT responses start with WEBVTT, but sometimes there may be leading newlines.
        if (t.startsWith("WEBVTT")) {
            return true;
        }
        // Heuristic: contains at least one cue timestamp arrow.
        return t.contains("-->");
    }

    private String parseCaptionsBodyToLrc(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }

        // Prefer VTT parse.
        if (looksLikeVtt(body)) {
            return vttToLrc(body);
        }

        // Handle timedtext XML formats (transcript/srv3) when fmt=vtt is ignored.
        String xmlLrc = timedTextXmlToLrc(body);
        if (xmlLrc != null && !xmlLrc.isBlank()) {
            return xmlLrc;
        }

        // Detect consent / HTML blocks; return null to trigger fallbacks.
        String t = body.trim();
        if (t.startsWith("<!DOCTYPE html") || t.startsWith("<html") || t.contains("consent.youtube.com")) {
            plugin.debug().debug(() -> "YouTube captions response looks like HTML/consent page; will fallback");
            return null;
        }

        return null;
    }

    private static String timedTextXmlToLrc(String xml) {
        if (xml == null) {
            return null;
        }
        String text = xml.trim();
        if (text.isBlank()) {
            return null;
        }
        // Fast sanity check: timedtext responses are XML and usually contain <text ...> or <p ...>
        if (!text.contains("<text") && !text.contains("<p")) {
            return null;
        }

        StringBuilder out = new StringBuilder();
        int emitted = 0;

        // Format 1: <text start="1.23" dur="...">Hello</text>
        Matcher m = XML_TEXT_CUE.matcher(text);
        while (m.find()) {
            if (emitted >= 200) {
                break;
            }
            double startSec = safeDouble(m.group(1));
            long startMs = (long) Math.max(0D, startSec * 1000D);
            String cueRaw = m.group(2);
            String cue = cleanupCaptionText(cueRaw);
            if (cue.isBlank()) {
                continue;
            }
            out.append(formatLrcTimestamp(startMs)).append(cue).append('\n');
            emitted++;
        }

        if (emitted > 0) {
            String lrc = out.toString();
            return lrc.isBlank() ? null : lrc;
        }

        // Format 2 (srv3-like): <p t="1234" d="..."> ... </p>
        Matcher m2 = XML_P_CUE.matcher(text);
        while (m2.find()) {
            if (emitted >= 200) {
                break;
            }
            long startMs = safeLong(m2.group(1));
            String cueRaw = m2.group(2);
            String cue = cleanupCaptionText(cueRaw);
            if (cue.isBlank()) {
                continue;
            }
            out.append(formatLrcTimestamp(startMs)).append(cue).append('\n');
            emitted++;
        }

        String lrc = out.toString();
        return lrc.isBlank() ? null : lrc;
    }

    private CaptionTrack chooseTrack(JsonArray tracks, List<String> preferredLanguages, boolean allowFallbackLanguage) {
        List<CaptionTrack> parsed = new ArrayList<>();
        for (JsonElement el : tracks) {
            if (el == null || !el.isJsonObject()) {
                continue;
            }
            JsonObject o = el.getAsJsonObject();
            String baseUrl = getString(o, "baseUrl");
            String lang = getString(o, "languageCode");
            String kind = getString(o, "kind"); // "asr" for auto
            if (baseUrl == null || baseUrl.isBlank()) {
                continue;
            }
            parsed.add(new CaptionTrack(baseUrl, lang, kind));
        }
        if (parsed.isEmpty()) {
            return null;
        }

        List<String> prefs = preferredLanguages != null ? preferredLanguages : List.of();
        for (String pref : prefs) {
            String p = normalizeLang(pref);
            if (p == null || p.isBlank()) {
                continue;
            }

            CaptionTrack bestManual = null;
            CaptionTrack bestAuto = null;
            for (CaptionTrack t : parsed) {
                String tl = normalizeLang(t.languageCode);
                if (!p.equals(tl)) {
                    continue;
                }
                if (!isAsr(t.kind)) {
                    bestManual = t;
                    break;
                }
                if (bestAuto == null) {
                    bestAuto = t;
                }
            }
            if (bestManual != null) {
                return bestManual;
            }
            if (bestAuto != null) {
                return bestAuto;
            }
        }

        if (!allowFallbackLanguage) {
            return null;
        }

        // Fallback: any manual first, else any.
        for (CaptionTrack t : parsed) {
            if (!isAsr(t.kind)) {
                return t;
            }
        }
        return parsed.get(0);
    }

    private static boolean isAsr(String kind) {
        return kind != null && kind.equalsIgnoreCase("asr");
    }

    private static String normalizeLang(String lang) {
        if (lang == null) {
            return null;
        }
        String l = lang.trim().toLowerCase(Locale.ROOT);
        if (l.isBlank()) {
            return l;
        }
        int dash = l.indexOf('-');
        if (dash > 0) {
            l = l.substring(0, dash);
        }
        return l;
    }

    private String forceFmtVtt(String baseUrl) {
        if (baseUrl == null) {
            return null;
        }
        String url = baseUrl;
        // Remove existing fmt=... to avoid getting srv3/xml when we expect vtt.
        url = url.replaceAll("([?&])fmt=[^&]*", "$1");
        // Clean up any accidental '&&' or trailing '?'/'&'
        url = url.replace("&&", "&");
        if (url.endsWith("?") || url.endsWith("&")) {
            url = url.substring(0, url.length() - 1);
        }
        if (!url.contains("fmt=")) {
            url = url + (url.contains("?") ? "&" : "?") + "fmt=vtt";
        }
        return url;
    }

    private String httpGetToString(String url, ProgressListener progress, long maxBytes) throws Exception {
        int timeoutSeconds = Math.max(1, plugin.getConfig().getInt("lyrics.youtubeCaptions.timeoutSeconds", plugin.getConfig().getInt("lyrics.timeoutSeconds", 15)));
        int retries = Math.max(0, plugin.getConfig().getInt("lyrics.youtubeCaptions.retries", plugin.getConfig().getInt("lyrics.retries", 1)));
        int backoffMs = Math.max(0, plugin.getConfig().getInt("lyrics.youtubeCaptions.retryBackoffMs", plugin.getConfig().getInt("lyrics.retryBackoffMs", 250)));

        URI uri;
        try {
            uri = URI.create(url);
        } catch (Exception e) {
            return null;
        }

        HttpRequest req = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("User-Agent", getUserAgent())
                .header("Accept-Language", "pl,en-US;q=0.8,en;q=0.6")
                .GET()
                .build();

        int attempts = 1 + retries;
        HttpResponse<InputStream> res = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            plugin.debug().http("GET " + uri + (attempts > 1 ? " (attempt " + attempt + "/" + attempts + ")" : ""));
            try {
                res = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
                int code = res.statusCode();
                if (code == 200) {
                    break;
                }
                if (attempt < attempts && (code == 408 || code == 429 || (code >= 500 && code <= 599))) {
                    sleepBackoff(backoffMs, attempt);
                    continue;
                }
                return null;
            } catch (java.io.IOException e) {
                if (attempt < attempts) {
                    sleepBackoff(backoffMs, attempt);
                    continue;
                }
                throw e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            }
        }

        if (res == null || res.statusCode() != 200) {
            return null;
        }

        long contentLen = res.headers().firstValueAsLong("Content-Length").orElse(-1L);
        if (progress != null) {
            progress.onProgress(0L, contentLen);
        }

        try (InputStream in = res.body(); ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            long total = 0L;
            int r;
            while ((r = in.read(buf)) != -1) {
                total += r;
                if (total > maxBytes) {
                    throw new IllegalStateException("Response too large");
                }
                baos.write(buf, 0, r);
                if (progress != null) {
                    progress.onProgress(total, contentLen);
                }
            }
            return baos.toString(StandardCharsets.UTF_8);
        }
    }

    private String getUserAgent() {
        String ua = String.valueOf(plugin.getConfig().getString("http.userAgent", "")).trim();
        if (!ua.isBlank()) {
            return ua;
        }
        String legacy = String.valueOf(plugin.getConfig().getString("cache.userAgent", "")).trim();
        return legacy.isBlank() ? "KaraokePlugin/1.0" : legacy;
    }

    private static void sleepBackoff(int baseMs, int attempt) {
        if (baseMs <= 0) {
            return;
        }
        long delay = (long) baseMs * (1L << Math.max(0, attempt - 1));
        delay = Math.min(delay, 2000L);
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String extractVideoId(String url) {
        if (url == null) {
            return null;
        }
        String u = url.trim();
        if (u.isBlank()) {
            return null;
        }

        // Try parsing with URI first
        try {
            URI uri = URI.create(u);
            String host = uri.getHost() != null ? uri.getHost().toLowerCase(Locale.ROOT) : "";
            String path = uri.getPath() != null ? uri.getPath() : "";

            if (host.contains("youtu.be")) {
                String p = path.startsWith("/") ? path.substring(1) : path;
                int slash = p.indexOf('/');
                if (slash > 0) {
                    p = p.substring(0, slash);
                }
                return sanitizeVideoId(p);
            }

            if (host.contains("youtube.com") || host.contains("music.youtube.com")) {
                String query = uri.getRawQuery();
                String v = getQueryParam(query, "v");
                if (v != null && !v.isBlank()) {
                    return sanitizeVideoId(v);
                }
                // Shorts: /shorts/<id>
                if (path != null) {
                    String[] seg = path.split("/");
                    for (int i = 0; i < seg.length - 1; i++) {
                        if ("shorts".equalsIgnoreCase(seg[i])) {
                            return sanitizeVideoId(seg[i + 1]);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }

        // Fallback: raw contains v=
        int idx = u.indexOf("v=");
        if (idx >= 0) {
            String rest = u.substring(idx + 2);
            int amp = rest.indexOf('&');
            if (amp >= 0) {
                rest = rest.substring(0, amp);
            }
            return sanitizeVideoId(rest);
        }

        return null;
    }

    private static String sanitizeVideoId(String id) {
        if (id == null) {
            return null;
        }
        String v = id.trim();
        if (v.isBlank()) {
            return null;
        }
        // IDs are typically 11 chars of [A-Za-z0-9_-]
        v = v.replaceAll("[^A-Za-z0-9_-]", "");
        if (v.length() < 6) {
            return null;
        }
        if (v.length() > 20) {
            v = v.substring(0, 20);
        }
        return v;
    }

    private static String getQueryParam(String rawQuery, String key) {
        if (rawQuery == null || rawQuery.isBlank() || key == null || key.isBlank()) {
            return null;
        }
        String[] parts = rawQuery.split("&");
        for (String p : parts) {
            if (p == null || p.isBlank()) {
                continue;
            }
            int eq = p.indexOf('=');
            String k = eq >= 0 ? p.substring(0, eq) : p;
            if (!key.equals(k)) {
                continue;
            }
            String val = eq >= 0 ? p.substring(eq + 1) : "";
            try {
                return URLDecoder.decode(val, StandardCharsets.UTF_8);
            } catch (Exception e) {
                return val;
            }
        }
        return null;
    }

    private static JsonObject getObj(JsonObject parent, String key) {
        if (parent == null || key == null) {
            return null;
        }
        try {
            JsonElement e = parent.get(key);
            if (e != null && e.isJsonObject()) {
                return e.getAsJsonObject();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static JsonArray getArr(JsonObject parent, String key) {
        if (parent == null || key == null) {
            return null;
        }
        try {
            JsonElement e = parent.get(key);
            if (e != null && e.isJsonArray()) {
                return e.getAsJsonArray();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String getString(JsonObject obj, String key) {
        if (obj == null || key == null) {
            return null;
        }
        try {
            JsonElement e = obj.get(key);
            if (e == null || e.isJsonNull()) {
                return null;
            }
            return e.getAsString();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String extractPlayerResponseJson(String html) {
        // Common patterns:
        // 1) var ytInitialPlayerResponse = {...};
        // 2) "ytInitialPlayerResponse": {...}
        int idx = html.indexOf("ytInitialPlayerResponse");
        if (idx < 0) {
            return null;
        }

        int braceStart = -1;
        for (int i = idx; i < html.length(); i++) {
            char c = html.charAt(i);
            if (c == '{') {
                braceStart = i;
                break;
            }
        }
        if (braceStart < 0) {
            return null;
        }

        return extractBalancedJsonObject(html, braceStart);
    }

    private static String extractBalancedJsonObject(String text, int startIdx) {
        if (text == null || startIdx < 0 || startIdx >= text.length() || text.charAt(startIdx) != '{') {
            return null;
        }
        int depth = 0;
        boolean inString = false;
        boolean escape = false;

        for (int i = startIdx; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escape) {
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }

            if (c == '"') {
                inString = true;
                continue;
            }
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(startIdx, i + 1);
                }
            }
        }
        return null;
    }

    private static String vttToLrc(String vtt) {
        if (vtt == null) {
            return null;
        }

        String text = vtt.replace("\r\n", "\n").replace("\r", "\n");
        String[] lines = text.split("\n");

        StringBuilder out = new StringBuilder();
        int i = 0;
        int emitted = 0;
        while (i < lines.length) {
            String line = lines[i] != null ? lines[i].trim() : "";
            i++;

            if (line.isBlank()) {
                continue;
            }
            if (line.startsWith("WEBVTT")) {
                continue;
            }

            // Optional cue identifier line; next line should be time
            Matcher m = VTT_TIME.matcher(line);
            if (!m.matches() && i < lines.length) {
                String maybeTime = lines[i] != null ? lines[i].trim() : "";
                Matcher m2 = VTT_TIME.matcher(maybeTime);
                if (m2.matches()) {
                    // skip identifier
                    line = maybeTime;
                    i++;
                    m = m2;
                }
            }

            if (!m.matches()) {
                continue;
            }

            long startMs = parseVttTimeToMs(m.group(1));

            // Gather cue text until blank line
            StringBuilder cue = new StringBuilder();
            while (i < lines.length) {
                String t = lines[i] != null ? lines[i].trim() : "";
                i++;
                if (t.isBlank()) {
                    break;
                }
                if (cue.length() > 0) {
                    cue.append(' ');
                }
                cue.append(t);
            }

            String cueText = cleanupCaptionText(cue.toString());
            if (cueText.isBlank()) {
                continue;
            }

            out.append(formatLrcTimestamp(startMs)).append(cueText).append('\n');
            emitted++;
            if (emitted >= 200) {
                break;
            }
        }

        String lrc = out.toString();
        return lrc.isBlank() ? null : lrc;
    }

    private static String cleanupCaptionText(String s) {
        if (s == null) {
            return "";
        }
        String t = s;
        // Strip common HTML tags used in captions
        t = t.replaceAll("<[^>]+>", "");
        t = decodeEntities(t);
        // Collapse whitespace
        t = t.replaceAll("\\s+", " ").trim();
        return t;
    }

    private static String decodeEntities(String s) {
        String t = s;
        t = t.replace("&amp;", "&");
        t = t.replace("&lt;", "<");
        t = t.replace("&gt;", ">");
        t = t.replace("&quot;", "\"");
        t = t.replace("&#39;", "'");
        return t;
    }

    private static long parseVttTimeToMs(String time) {
        if (time == null) {
            return 0L;
        }
        String t = time.trim().replace(',', '.');
        String[] parts = t.split(":");
        int h = 0;
        int m;
        String secPart;
        if (parts.length == 3) {
            h = safeInt(parts[0]);
            m = safeInt(parts[1]);
            secPart = parts[2];
        } else if (parts.length == 2) {
            m = safeInt(parts[0]);
            secPart = parts[1];
        } else {
            return 0L;
        }

        int dot = secPart.indexOf('.');
        int s = dot >= 0 ? safeInt(secPart.substring(0, dot)) : safeInt(secPart);
        int ms = 0;
        if (dot >= 0 && dot + 1 < secPart.length()) {
            String frac = secPart.substring(dot + 1);
            if (frac.length() > 3) {
                frac = frac.substring(0, 3);
            }
            while (frac.length() < 3) {
                frac = frac + "0";
            }
            ms = safeInt(frac);
        }

        long totalMs = (h * 3600_000L) + (m * 60_000L) + (s * 1000L) + ms;
        return Math.max(0L, totalMs);
    }

    private static int safeInt(String v) {
        try {
            return Integer.parseInt(v);
        } catch (Exception e) {
            return 0;
        }
    }

    private static long safeLong(String v) {
        try {
            return Long.parseLong(v);
        } catch (Exception e) {
            return 0L;
        }
    }

    private static double safeDouble(String v) {
        try {
            return Double.parseDouble(v);
        } catch (Exception e) {
            return 0D;
        }
    }

    private static String formatLrcTimestamp(long ms) {
        long totalSeconds = Math.max(0L, ms) / 1000L;
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        long centis = (Math.max(0L, ms) % 1000L) / 10L;
        return String.format(Locale.ROOT, "[%02d:%02d.%02d]", minutes, seconds, centis);
    }

    private record CaptionTrack(String baseUrl, String languageCode, String kind) {
    }
}
