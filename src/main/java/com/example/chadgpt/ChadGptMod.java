package com.example.chadgpt;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

@Mod("chadgpt")
public class ChadGptMod {
    private static final Logger LOG = LogManager.getLogger();

    // Background workers; keep HTTP off the server thread.
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "ChadGPT-Worker");
        t.setDaemon(true);
        return t;
    });

    // Rolling chat buffer; oldest at head.
    private static final Deque<ChatLine> HISTORY = new ConcurrentLinkedDeque<>();
    private static final int HISTORY_TO_SEND = clamp(
            Integer.parseInt(System.getProperty("chadgpt.history", "20")), 0, 20);
    private static final int HISTORY_CAP = Math.max(HISTORY_TO_SEND + 1,
            Integer.parseInt(System.getProperty("chadgpt.history_cap", "200")));

    // Endpoints and auth.
    private static final String RESPONSES_URL = System.getProperty("chadgpt.responses_url", "https://api.openai.com/v1/responses");
    private static final String API_KEY_ENV    = System.getProperty("chadgpt.env", "OPENAI_API_KEY");

    // Default model; override with -Dchadgpt.model=...
    private static final String MODEL = System.getProperty("chadgpt.model", "gpt-5-nano");

    // Vector store id for Silent Gear assister.
    private static final String VECTOR_STORE_ID = System.getProperty(
            "chadgpt.vector_store_id", "vs_689926f2f0608191b014742e99e012c5");

    // Output settings.
    private static final String PREFIX = System.getProperty("chadgpt.prefix", "[ChadGPT] ");
    private static final int    MAX_SAY_CHARS = Integer.parseInt(System.getProperty("chadgpt.max_chars", "500")); // only for regular path
    private static final long   COOLDOWN_MS   = Long.parseLong(System.getProperty("chadgpt.cooldown_ms", "3000"));

    // HTTP timeouts and retry; tune via -D args.
    private static final int RESP_CONNECT_MS = Integer.parseInt(System.getProperty("chadgpt.responses_connect_ms", "20000"));
    private static final int RESP_READ_MS    = Integer.parseInt(System.getProperty("chadgpt.responses_read_ms",    "120000"));
    private static final int HTTP_RETRIES    = Integer.parseInt(System.getProperty("chadgpt.http_retries", "2"));
    private static final int RETRY_BASE_MS   = Integer.parseInt(System.getProperty("chadgpt.retry_base_ms", "750"));

    private final AtomicLong lastCallMs = new AtomicLong(0);

    public ChadGptMod() {
        MinecraftForge.EVENT_BUS.register(this);
        LOG.info("ChadGPT Forge mod loaded.");
    }

    @SubscribeEvent
    public void onChat(ServerChatEvent event) {
        String raw = event.getMessage();
        if (raw == null) return;

        // Record every line; evict when over cap.
        String author = "Unknown";
        try {
            if (event.getPlayer() != null && event.getPlayer().getGameProfile() != null) {
                author = event.getPlayer().getGameProfile().getName();
            }
        } catch (Throwable ignored) {}
        appendHistory(author, raw);

        String lower = raw.toLowerCase(Locale.ROOT);
        boolean hasChadGpt = lower.contains("chadgpt");
        if (!hasChadGpt) return;

        boolean hasSilent = lower.contains("silent");

        long now = System.currentTimeMillis();
        if (now - lastCallMs.get() < COOLDOWN_MS) return;
        lastCallMs.set(now);

        MinecraftServer server = event.getPlayer() != null ? event.getPlayer().getServer() : null;
        if (server == null) return;

        // Snapshot the last N previous lines; exclude the current line which was just appended.
        List<ChatLine> context = snapshotPrevious(HISTORY_TO_SEND);
        String latestUserMessage = raw; // send the exact player message; requirement 1

        if (hasSilent) {
            // Silent Gear assister; Responses API with file_search over your vector store; no display limit.
            POOL.submit(() -> {
                String line = responsesWithFileSearch(context, latestUserMessage);
                String say  = sanitizeNoTruncate(PREFIX + line);
                server.execute(() -> {
                    try {
                        server.getCommands().performCommand(server.createCommandSourceStack(), "say " + say);
                    } catch (Throwable t) {
                        LOG.warn("Failed to dispatch /say", t);
                    }
                });
            });
        } else {
            // Regular ChadGPT; Responses API; only model, instructions, input.
            POOL.submit(() -> {
                String line = responsesSimple(context, latestUserMessage);
                String say  = sanitizeWithLimit(PREFIX + line, MAX_SAY_CHARS);
                server.execute(() -> {
                    try {
                        server.getCommands().performCommand(server.createCommandSourceStack(), "say " + say);
                    } catch (Throwable t) {
                        LOG.warn("Failed to dispatch /say", t);
                    }
                });
            });
        }
    }

    // Store and snapshot history.
    private static void appendHistory(String author, String text) {
        HISTORY.addLast(new ChatLine(author, text));
        while (HISTORY.size() > HISTORY_CAP) {
            HISTORY.pollFirst();
        }
    }

    private static List<ChatLine> snapshotPrevious(int maxCount) {
        List<ChatLine> all = new ArrayList<>(HISTORY);
        if (all.isEmpty()) return all;
        int lastIdx = all.size() - 1; // current line
        int from = Math.max(0, lastIdx - maxCount);
        return new ArrayList<>(all.subList(from, lastIdx)); // oldest first
    }

    // ---------------------------
    // Regular route; Responses API with minimal body: model; instructions; input.
    // ---------------------------
    private String responsesSimple(List<ChatLine> previous, String latestUserMessage) {
        String apiKey = System.getenv(API_KEY_ENV);
        if (apiKey == null || apiKey.isEmpty()) {
            return "Set the " + API_KEY_ENV + " environment variable for ChadGPT.";
        }

        String instructions =
                "You are ChadGPT; a chaotic Gen Alpha brainrot minecraft player assisting other players on a server. " +
                "Speak in extreme brainrot style; meme-heavy; zoomer slang; absurd energy; lowercase only; minimal punctuation. " +
                "No emojis. No markdown. No links. No new lines. " +
                "You are an in-game assistant; everything is in one continuous text string. " +
                "Use recent player chat for context but always respond to the last message. " +
                "Lean into chaotic over-the-top minecraft kid energy; overuse slang; abbreviations; random hype words; and gaming inside jokes.";

        // Build input with context then the latest message.
        StringBuilder in = new StringBuilder();
        if (!previous.isEmpty()) {
            in.append("Recent chat context:\n");
            for (ChatLine c : previous) {
                in.append(c.author).append(": ").append(c.text).append("\n");
            }
        }
        in.append(latestUserMessage);
        String input = in.toString();

        try {
            JsonObject body = new JsonObject();
            body.addProperty("model", MODEL);
            body.addProperty("instructions", instructions);
            body.addProperty("input", input);

            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            String resp = httpPostResponses(payload, apiKey);
            if (resp == null) {
                return "The muse is muted; check server logs.";
            }
            return extractResponsesOutputText(resp);
        } catch (Exception ex) {
            LOG.warn("OpenAI Responses call failed", ex);
            return "Network gremlins; try again soon.";
        }
    }

    // ---------------------------
    // Silent Gear route; Responses API plus file_search tool with your vector store id.
    // Request body includes only: model; instructions; input; tools.
    // ---------------------------
    private String responsesWithFileSearch(List<ChatLine> previous, String latestUserMessage) {
        String apiKey = System.getenv(API_KEY_ENV);
        if (apiKey == null || apiKey.isEmpty()) {
            return "Set the " + API_KEY_ENV + " environment variable for ChadGPT.";
        }

        // Your data-focused prompt; unchanged.
        String instructions =
                "You are ChadGPT; a minecraft player assisting other players on a server. The players in this minecraft server do not have access to your knowledgebase files or other in-game files. Do not expose the existence of those files. " +
                "You are an in-game assistant; everything is in one continuous text string. You cannot use Markdown elements or new lines because of this. " +
                "Additionally, you should limit your responses to 300 words. " +
                "You should respond purely with information from the json files. Use recent player chat for context but always respond to the last message. " +
                "Use the attached files to answer the user's questions about Silent gear materials and traits.";

        // Build input with context then the latest message.
        StringBuilder in = new StringBuilder();
        if (!previous.isEmpty()) {
            in.append("Recent chat context:\n");
            for (ChatLine c : previous) {
                in.append(c.author).append(": ").append(c.text).append("\n");
            }
        }
        in.append(latestUserMessage);
        String input = in.toString();

        try {
            JsonObject body = new JsonObject();
            body.addProperty("model", MODEL);
            body.addProperty("instructions", instructions);
            body.addProperty("input", input);

            // Tools: file_search with your vector store id; nothing else added.
            JsonArray tools = new JsonArray();
            JsonObject tool = new JsonObject();
            tool.addProperty("type", "file_search");
            JsonArray vsIds = new JsonArray();
            vsIds.add(VECTOR_STORE_ID);
            tool.add("vector_store_ids", vsIds);
            tools.add(tool);
            body.add("tools", tools);

            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            String resp = httpPostResponses(payload, apiKey);
            if (resp == null) {
                return "The muse is muted; check server logs.";
            }
            return extractResponsesOutputText(resp);
        } catch (Exception ex) {
            LOG.warn("OpenAI Responses call failed", ex);
            return "Network gremlins; try again soon.";
        }
    }

    // Shared HTTP POST with retries and higher timeouts for Responses API.
    private String httpPostResponses(byte[] payload, String apiKey) {
        String resp = null;
        int code = -1;

        for (int attempt = 0; attempt <= HTTP_RETRIES; attempt++) {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(RESPONSES_URL).openConnection();
                conn.setConnectTimeout(RESP_CONNECT_MS);
                conn.setReadTimeout(RESP_READ_MS);
                conn.setDoOutput(true);
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Authorization", "Bearer " + apiKey);
                conn.setRequestProperty("User-Agent", "ChadGPT-Forge/1.5");

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(payload);
                }

                code = conn.getResponseCode();
                BufferedReader reader = new BufferedReader(new InputStreamReader(
                        code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream(),
                        StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                for (String line; (line = reader.readLine()) != null; ) sb.append(line);
                resp = sb.toString();

                if (code / 100 == 2) break; // success
            } catch (Exception e) {
                LOG.warn("Responses API attempt {} failed: {}", attempt + 1, e.toString());
            } finally {
                if (conn != null) conn.disconnect();
            }
            if (attempt < HTTP_RETRIES) backoffSleep(attempt);
        }

        if (code / 100 != 2 || resp == null) {
            LOG.warn("OpenAI Responses API error {}; giving up", code);
            return null;
        }
        return resp;
    }

    // Responses parsing; prefer output[..].content[..].text; fallback to output_text.
    private static String extractResponsesOutputText(String respJson) {
        try {
            JsonObject root = new JsonParser().parse(respJson).getAsJsonObject();
            StringBuilder out = new StringBuilder();
            JsonArray output = root.getAsJsonArray("output");
            if (output != null) {
                for (JsonElement elem : output) {
                    if (!elem.isJsonObject()) continue;
                    JsonObject obj = elem.getAsJsonObject();
                    String type = obj.has("type") ? obj.get("type").getAsString() : "";
                    if (!"message".equals(type)) continue;
                    JsonArray content = obj.getAsJsonArray("content");
                    if (content == null) continue;
                    for (JsonElement ce : content) {
                        if (!ce.isJsonObject()) continue;
                        JsonObject co = ce.getAsJsonObject();
                        if (co.has("text")) {
                            out.append(co.get("text").getAsString());
                        }
                    }
                }
            }
            String s = out.toString().trim();
            if (!s.isEmpty()) return s.replace("\r", " ").replace("\n", " ").trim();
            if (root.has("output_text")) {
                return root.get("output_text").getAsString().replace("\r", " ").replace("\n", " ").trim();
            }
        } catch (Throwable t) {
            LOG.warn("Failed to parse Responses API JSON", t);
        }
        return "Silence. Try again.";
    }

    // Helpers.
    private static void backoffSleep(int attempt) {
        try {
            long wait = (long)(RETRY_BASE_MS * Math.pow(2, attempt));
            Thread.sleep(Math.min(wait, 5000));
        } catch (InterruptedException ignored) {}
    }

    private static String sanitizeWithLimit(String s, int maxLen) {
        if (s == null) return "";
        String out = s.replace('\n', ' ').replace('\r', ' ');
        out = out.replaceAll("\\s+", " ").trim();
        out = out.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "");
        out = out.replaceAll("[|<>^`]", "");
        if (out.length() > maxLen) out = out.substring(0, maxLen);
        return out;
    }

    private static String sanitizeNoTruncate(String s) {
        if (s == null) return "";
        String out = s.replace('\n', ' ').replace('\r', ' ');
        out = out.replaceAll("\\s+", " ").trim();
        out = out.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "");
        out = out.replaceAll("[|<>^`]", "");
        return out;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static final class ChatLine {
        final String author;
        final String text;
        ChatLine(String author, String text) { this.author = author; this.text = text; }
    }
}
