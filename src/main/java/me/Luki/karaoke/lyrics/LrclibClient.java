package me.Luki.karaoke.lyrics;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.Luki.karaoke.Karaoke;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public final class LrclibClient {

    private final Karaoke plugin;
    private final HttpClient http;

    public LrclibClient(Karaoke plugin) {
        this.plugin = plugin;
        int timeoutSeconds = Math.max(1, plugin.getConfig().getInt("lyrics.timeoutSeconds", 15));
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public String searchSyncedLrc(String query) throws Exception {
        String q = query == null ? "" : query.trim();
        if (q.isBlank()) {
            return null;
        }

        String endpoint = "https://lrclib.net/api/search?q=" + URLEncoder.encode(q, StandardCharsets.UTF_8);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofSeconds(Math.max(1, plugin.getConfig().getInt("lyrics.timeoutSeconds", 15))))
                .header("User-Agent", "KaraokePlugin/1.0")
                .GET()
                .build();

        plugin.debug().http("GET " + endpoint);
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (res.statusCode() != 200) {
            return null;
        }

        JsonArray arr;
        try {
            arr = JsonParser.parseString(res.body()).getAsJsonArray();
        } catch (Exception e) {
            return null;
        }

        // Choose first non-empty syncedLyrics
        for (int i = 0; i < arr.size(); i++) {
            JsonObject obj = arr.get(i).getAsJsonObject();
            if (obj == null) {
                continue;
            }
            if (obj.has("syncedLyrics") && !obj.get("syncedLyrics").isJsonNull()) {
                String lrc = obj.get("syncedLyrics").getAsString();
                if (lrc != null && !lrc.isBlank()) {
                    return lrc;
                }
            }
        }

        return null;
    }
}
