package com.example.chadgpt;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonPrimitive;
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
import java.util.regex.Pattern;

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

    // HTTP timeouts and retry; tune via -D args.
    private static final int RESP_CONNECT_MS = Integer.parseInt(System.getProperty("chadgpt.responses_connect_ms", "20000"));
    private static final int RESP_READ_MS    = Integer.parseInt(System.getProperty("chadgpt.responses_read_ms",    "120000"));
    private static final int HTTP_RETRIES    = Integer.parseInt(System.getProperty("chadgpt.http_retries", "2"));
    private static final int RETRY_BASE_MS   = Integer.parseInt(System.getProperty("chadgpt.retry_base_ms", "750"));

    // Cooldown between triggers; shared by both routes.
    private static final long COOLDOWN_MS = Long.parseLong(System.getProperty("chadgpt.cooldown_ms", "3000"));

    // Follow-up window; if >0 and a player says a line containing the word "you", regular ChadGPT replies.
    private int followWindowRemaining = 0;
    private static final Pattern YOU_WORD = Pattern.compile("(?i)(^|\\W)you(\\W|$)");

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
        boolean explicitHasChadGpt = lower.contains("chadgpt");
        boolean explicitHasSilent  = explicitHasChadGpt && lower.contains("silent");

        boolean followupYou = !explicitHasChadGpt
                && followWindowRemaining > 0
                && YOU_WORD.matcher(lower).find();

        boolean shouldTrigger = explicitHasChadGpt || followupYou;

        long now = System.currentTimeMillis();
        boolean cooldownOk = now - lastCallMs.get() >= COOLDOWN_MS;
        MinecraftServer server = event.getPlayer() != null ? event.getPlayer().getServer() : null;
        if (server == null) return;

        if (shouldTrigger && cooldownOk) {
            lastCallMs.set(now);

            // Ack; immediate; one clean line.
            String ackCmd = buildAckTellraw("Message received.");
            server.execute(() -> {
                server.getCommands().performCommand(server.createCommandSourceStack(), ackCmd);
                LOG.info("[ChadGPT ack] Message received.");
            });

            // Snapshot the last N previous lines; exclude the current line which was just appended.
            List<ChatLine> context = snapshotPrevious(HISTORY_TO_SEND);
            String latestUserMessage = raw; // send the exact player message

            if (explicitHasSilent) {
                // Silent Gear assister; Responses API with file_search; /tellraw output.
                POOL.submit(() -> {
                    String cmdFromModel = responsesWithFileSearch(context, latestUserMessage);
                    // Build one or more tellraw commands; inject identifier; split on "\n"
                    List<String> cmds = buildTellrawCommandsWithIdentifier(cmdFromModel);
                    server.execute(() -> {
                        for (String c : cmds) {
                            server.getCommands().performCommand(server.createCommandSourceStack(), c);
                            LOG.info("[ChadGPT out] " + extractPlainTextFromCommand(c));
                        }
                        // Start a new two-message follow-up window after the final response is sent.
                        followWindowRemaining = 2;
                    });
                });
            } else {
                // Regular ChadGPT; Responses API; /tellraw output.
                POOL.submit(() -> {
                    String cmdFromModel = responsesSimple(context, latestUserMessage);
                    List<String> cmds = buildTellrawCommandsWithIdentifier(cmdFromModel);
                    server.execute(() -> {
                        for (String c : cmds) {
                            server.getCommands().performCommand(server.createCommandSourceStack(), c);
                            LOG.info("[ChadGPT out] " + extractPlainTextFromCommand(c));
                        }
                        followWindowRemaining = 2;
                    });
                });
            }
        }

        // Count down any existing follow-up window on every player chat line.
        if (followWindowRemaining > 0) {
            followWindowRemaining--;
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
    // Includes your tellraw policy; colors themed by content.
    // ---------------------------
    private String responsesSimple(List<ChatLine> previous, String latestUserMessage) {
        String apiKey = System.getenv(API_KEY_ENV);
        if (apiKey == null || apiKey.isEmpty()) {
            return fallbackTellraw("Set the " + API_KEY_ENV + " environment variable for ChadGPT.");
        }

        String instructions =
                // Voice and behavior.
                "You are ChadGPT; a chaotic Gen Alpha brainrot minecraft player assisting other players on a server. " +
                "Speak in extreme brainrot style; meme-heavy; zoomer slang; absurd energy; lowercase only; minimal punctuation. " +
                "No emojis. No links. No new lines in the physical output. Use recent player chat for context but answer the last message. " +
                "Color segments based on theme; for example if the subject mentions emerald, use an emerald tone; if it mentions rainbow, vary colors across the line.\n" +
                // /tellraw policy block; verbatim rules embedded.
                "You format messages for Minecraft Java 1.16.5 using /tellraw.\n" +
                "Output policy:\n" +
                "- Emit exactly one physical line per response; no literal newlines; no commentary; no code fences.\n" +
                "- Command shape must be: /tellraw @a <Component>\n" +
                "- Use Raw JSON Text; not SNBT; not Bedrock rawtext; not section symbol codes.\n" +
                "- Only these keys are allowed on objects: \"text\", \"color\", \"bold\", \"italic\".\n" +
                "- To compose multiple segments, use a top-level JSON array of components.\n" +
                "- For visual line breaks, include the literal string \"\\n\" as an array element or inside a \"text\" string.\n" +
                "- Never invent other keys; never use hoverEvent; clickEvent; extra; score; selector; translate; nbt.\n" +
                "Colors:\n" +
                "- Allowed color names for \"color\" plus their canonical hex equivalents:\n" +
                "  black #000000; dark_blue #0000AA; dark_green #00AA00; dark_aqua #00AAAA; dark_red #AA0000; dark_purple #AA00AA; gold #FFAA00; gray #AAAAAA; dark_gray #555555; blue #5555FF; green #55FF55; aqua #55FFFF; red #FF5555; light_purple #FF55FF; yellow #FFFF55; white #FFFFFF.\n" +
                "- You may also use 6-digit hex strings like \"#00ff88\".\n" +
                "Formatting rules:\n" +
                "- Bold: \"bold\": true\n" +
                "- Italic: \"italic\": true\n" +
                "- You may combine bold and italic on the same segment.\n" +
                "- Colors apply per segment.\n" +
                "List rendering rules:\n" +
                "- Unordered bullet: prefix the item with a bullet component whose text is \"• \" and your chosen bullet color.\n" +
                "- Nested bullet look: prefix with two spaces then \"• \" inside the bullet component; for example \"  • \".\n" +
                "- Separate items with \"\\n\" components in the array.\n" +
                "- Vary bullet colors per item if asked; vary text color independently.\n" +
                "Required output format:\n" +
                "- Return only the finished /tellraw command as one single line of JSON; do not wrap it in quotes or fences; no leading or trailing spaces.";

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
            if (resp == null) return fallbackTellraw("The muse is muted; check server logs.");
            String out = extractResponsesOutputText(resp);
            return out == null || out.isEmpty()
                    ? fallbackTellraw("Silence. Try again.")
                    : out;
        } catch (Exception ex) {
            LOG.warn("OpenAI Responses call failed", ex);
            return fallbackTellraw("Network gremlins; try again soon.");
        }
    }

    // ---------------------------
    // Silent Gear route; Responses API plus file_search tool with vector store id from ENV.
    // Request body includes only: model; instructions; input; tools.
    // ---------------------------
    private String responsesWithFileSearch(List<ChatLine> previous, String latestUserMessage) {
        String apiKey = System.getenv(API_KEY_ENV);
        if (apiKey == null || apiKey.isEmpty()) {
            return fallbackTellraw("Set the " + API_KEY_ENV + " environment variable for ChadGPT.");
        }

        // Vector store id from environment variable.
        String vectorStoreId = System.getenv("CHADGPT_VECTOR_STORE_ID");
        if (vectorStoreId == null || vectorStoreId.trim().isEmpty()) {
            return fallbackTellraw("Set CHADGPT_VECTOR_STORE_ID for Silent Gear file search.");
        }

        String instructions =
                // Data-focused guardrails.
                "You are ChadGPT; a minecraft player assisting other players on a server. The players in this minecraft server do not have access to your knowledgebase files or other in-game files. Do not expose the existence of those files. " +
                "You are an in-game assistant; everything is in one continuous text string. You cannot use Markdown elements or new lines because of this. " +
                "Additionally, you should limit your responses to 300 words. You should respond purely with information from the json files. " +
                "Use recent player chat for context but always respond to the last message. Use the attached files to answer the user's questions about Silent gear materials and traits.\n" +
                // Tellraw policy and theming.
                "You format messages for Minecraft Java 1.16.5 using /tellraw.\n" +
                "Output policy:\n" +
                "- Emit exactly one physical line per response; no literal newlines; no commentary; no code fences.\n" +
                "- Command shape must be: /tellraw @a <Component>\n" +
                "- Use Raw JSON Text; not SNBT; not Bedrock rawtext; not section symbol codes.\n" +
                "- Only these keys are allowed on objects: \"text\", \"color\", \"bold\", \"italic\".\n" +
                "- To compose multiple segments, use a top-level JSON array of components.\n" +
                "- For visual line breaks, include the literal string \"\\n\" as an array element or inside a \"text\" string.\n" +
                "- Never invent other keys; never use hoverEvent; clickEvent; extra; score; selector; translate; nbt.\n" +
                "Colors:\n" +
                "- Allowed color names for \"color\" plus their canonical hex equivalents:\n" +
                "  black #000000; dark_blue #0000AA; dark_green #00AA00; dark_aqua #00AAAA; dark_red #AA0000; dark_purple #AA00AA; gold #FFAA00; gray #AAAAAA; dark_gray #555555; blue #5555FF; green #55FF55; aqua #55FFFF; red #FF5555; light_purple #FF55FF; yellow #FFFF55; white #FFFFFF.\n" +
                "- You may also use 6-digit hex strings like \"#00ff88\".\n" +
                "Formatting rules:\n" +
                "- Bold: \"bold\": true\n" +
                "- Italic: \"italic\": true\n" +
                "- You may combine bold and italic on the same segment.\n" +
                "- Colors apply per segment.\n" +
                "Required output format:\n" +
                "- Return only the finished /tellraw command as one single line of JSON; do not wrap it in quotes or fences; no leading or trailing spaces.\n" +
                "Color segments based on the discussed material or trait; e.g., emerald-like materials use an emerald tone; gems use gemstone hues; rainbows vary across allowed colors.";

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

            // Tools: file_search with vector store id from ENV; nothing else added.
            JsonArray tools = new JsonArray();
            JsonObject tool = new JsonObject();
            tool.addProperty("type", "file_search");
            JsonArray vsIds = new JsonArray();
            vsIds.add(vectorStoreId.trim());
            tool.add("vector_store_ids", vsIds);
            tools.add(tool);
            body.add("tools", tools);

            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            String resp = httpPostResponses(payload, apiKey);
            if (resp == null) return fallbackTellraw("The muse is muted; check server logs.");
            String out = extractResponsesOutputText(resp);
            return out == null || out.isEmpty()
                    ? fallbackTellraw("Silence. Try again.")
                    : out;
        } catch (Exception ex) {
            LOG.warn("OpenAI Responses call failed", ex);
            return fallbackTellraw("Network gremlins; try again soon.");
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
                conn.setRequestProperty("User-Agent", "ChadGPT-Forge/1.7");

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
        return "";
    }

    // ----- Tellraw assembly and utilities -----

    // Build ack: tellraw @a ["", {"text":"<ChadGPT> ","color":"gold","bold":true}, {"text":"Message received.","color":"gray"}]
    private static String buildAckTellraw(String message) {
        String safe = message == null ? "" : message.replace("\"", "'").replace("\r", " ").replace("\n", " ").trim();
        JsonArray arr = new JsonArray();
        arr.add(new JsonPrimitive(""));
        JsonObject tag = new JsonObject();
        tag.addProperty("text", "<ChadGPT> ");
        tag.addProperty("color", "gold");
        tag.addProperty("bold", true);
        arr.add(tag);
        JsonObject msg = new JsonObject();
        msg.addProperty("text", safe);
        msg.addProperty("color", "gray");
        arr.add(msg);
        return "tellraw @a " + arr.toString();
    }

    // Turn the model's single-line /tellraw into one or more tellraw commands:
    // - validate prefix
    // - parse JSON
    // - split on "\n"
    // - inject <ChadGPT> identifier at the start of each line
    private static List<String> buildTellrawCommandsWithIdentifier(String modelOutput) {
        List<String> out = new ArrayList<>();
        if (modelOutput == null) {
            out.add(fallbackTellraw("Empty model output."));
            return out;
        }
        String s = modelOutput.replace("\r", " ").trim();
        String lower = s.toLowerCase(Locale.ROOT);

        // Accept with or without leading slash.
        if (lower.startsWith("/tellraw ")) s = s.substring(1);
        else if (!lower.startsWith("tellraw ")) {
            out.add(fallbackTellraw("Expected /tellraw output."));
            return out;
        }

        String lowerCmd = s.toLowerCase(Locale.ROOT);
        if (!lowerCmd.startsWith("tellraw @a ")) {
            out.add(fallbackTellraw("Command target must be @a."));
            return out;
        }

        int idx = "tellraw @a ".length();
        String jsonPart = s.substring(idx).trim();
        if (jsonPart.isEmpty()) {
            out.add(fallbackTellraw("Missing JSON component."));
            return out;
        }

        try {
            JsonElement root = new JsonParser().parse(jsonPart);
            List<JsonArray> lines = splitIntoLines(ensureArrayComponent(root));

            for (JsonArray line : lines) {
                JsonArray withId = prependIdentifier(line);
                String cmd = "tellraw @a " + withId.toString();
                out.add(cmd);
            }
            return out;
        } catch (Throwable t) {
            LOG.warn("Failed to parse model tellraw JSON; using fallback", t);
            out.add(fallbackTellraw("Formatting error; try again."));
            return out;
        }
    }

    // If the top-level component is an object, wrap it into an array for uniform processing.
    private static JsonArray ensureArrayComponent(JsonElement comp) {
        if (comp == null) {
            JsonArray arr = new JsonArray();
            arr.add(new JsonPrimitive(""));
            return arr;
        }
        if (comp.isJsonArray()) return comp.getAsJsonArray();
        JsonArray arr = new JsonArray();
        arr.add(deepCopy(comp));
        return arr;
    }

    // Split a component array into visual lines on any "\n" boundaries.
    private static List<JsonArray> splitIntoLines(JsonArray arr) {
        List<JsonArray> lines = new ArrayList<>();
        JsonArray current = new JsonArray();

        for (JsonElement el : arr) {
            if (el == null) continue;

            if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
                String s = el.getAsString();
                String[] parts = s.split("\\n", -1);
                for (int i = 0; i < parts.length; i++) {
                    String part = parts[i];
                    if (!part.isEmpty()) {
                        current.add(new JsonPrimitive(part));
                    }
                    if (i < parts.length - 1) {
                        // newline boundary; finalize current
                        lines.add(current);
                        current = new JsonArray();
                    }
                }
                continue;
            }

            if (el.isJsonObject()) {
                JsonObject obj = el.getAsJsonObject();
                if (obj.has("text") && obj.get("text").isJsonPrimitive()) {
                    String text = obj.get("text").getAsString();
                    String[] parts = text.split("\\n", -1);
                    for (int i = 0; i < parts.length; i++) {
                        JsonObject copy = new JsonObject();
                        copy.addProperty("text", parts[i]);
                        if (obj.has("color"))  copy.add("color",  obj.get("color"));
                        if (obj.has("bold"))   copy.add("bold",   obj.get("bold"));
                        if (obj.has("italic")) copy.add("italic", obj.get("italic"));
                        if (!parts[i].isEmpty()) current.add(copy);
                        if (i < parts.length - 1) {
                            lines.add(current);
                            current = new JsonArray();
                        }
                    }
                } else {
                    // Object without text; keep as-is.
                    current.add(deepCopy(obj));
                }
                continue;
            }

            if (el.isJsonArray()) {
                // Flatten nested array.
                List<JsonArray> subLines = splitIntoLines(el.getAsJsonArray());
                for (int i = 0; i < subLines.size(); i++) {
                    if (i == 0) {
                        // append into current
                        for (JsonElement se : subLines.get(0)) current.add(se);
                    } else {
                        lines.add(current);
                        current = new JsonArray();
                        for (JsonElement se : subLines.get(i)) current.add(se);
                    }
                }
                continue;
            }
        }

        lines.add(current);
        // Normalize empty lines to at least an empty string component.
        for (int i = 0; i < lines.size(); i++) {
            JsonArray ln = lines.get(i);
            if (ln.size() == 0) {
                ln.add(new JsonPrimitive(""));
            }
        }
        return lines;
    }

    // Prepend the <ChadGPT> tag to a line; avoid double-tagging if it is already present.
    private static JsonArray prependIdentifier(JsonArray line) {
        // Detect if the line already starts with "<ChadGPT>"
        String firstText = firstTextOf(line);
        boolean alreadyTagged = firstText != null && firstText.startsWith("<ChadGPT>");

        JsonArray out = new JsonArray();
        out.add(new JsonPrimitive("")); // standard anchor

        if (!alreadyTagged) {
            JsonObject tag = new JsonObject();
            tag.addProperty("text", "<ChadGPT> ");
            tag.addProperty("color", "gold");
            tag.addProperty("bold", true);
            out.add(tag);
        }

        for (JsonElement e : line) {
            out.add(deepCopy(e));

    }

    // Gson's JsonElement#deepCopy is not public in many versions; provide our own.
    private static JsonElement deepCopy(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return JsonNull.INSTANCE;
        }
        if (element.isJsonPrimitive()) {
            JsonPrimitive p = element.getAsJsonPrimitive();
            if (p.isString()) return new JsonPrimitive(p.getAsString());
            if (p.isBoolean()) return new JsonPrimitive(p.getAsBoolean());
            if (p.isNumber()) return new JsonPrimitive(p.getAsNumber());
            // Fallback to string representation
            return new JsonPrimitive(p.getAsString());
        }
        if (element.isJsonArray()) {
            JsonArray in = element.getAsJsonArray();
            JsonArray out = new JsonArray();
            for (JsonElement e : in) {
                out.add(deepCopy(e));
            }
            return out;
        }
        // JsonObject
        JsonObject inObj = element.getAsJsonObject();
        JsonObject outObj = new JsonObject();
        for (var entry : inObj.entrySet()) {
            outObj.add(entry.getKey(), deepCopy(entry.getValue()));
        }
        return outObj;
        }
        return out;
    }

    private static String firstTextOf(JsonArray line) {
        if (line == null) return null;
        for (JsonElement e : line) {
            if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
                String s = e.getAsString();
                if (!s.isEmpty()) return s;
            }
            if (e.isJsonObject()) {
                JsonObject o = e.getAsJsonObject();
                if (o.has("text")) {
                    String s = o.get("text").getAsString();
                    if (s != null && !s.isEmpty()) return s;
                }
            }
        }
        return null;
    }

    // Extract a plain-text preview from a tellraw command for logging.
    private static String extractPlainTextFromCommand(String cmd) {
        try {
            String s = cmd.trim();
            String lower = s.toLowerCase(Locale.ROOT);
            if (lower.startsWith("/tellraw ")) s = s.substring(1);
            int idx = s.toLowerCase(Locale.ROOT).indexOf("tellraw @a ");
            if (idx < 0) return cmd;
            String json = s.substring(idx + "tellraw @a ".length()).trim();
            JsonElement root = new JsonParser().parse(json);
            StringBuilder sb = new StringBuilder();
            collectText(root, sb);
            return sb.toString().replaceAll("\\s+", " ").trim();
        } catch (Throwable t) {
            return cmd;
        }
    }

    private static void collectText(JsonElement el, StringBuilder sb) {
        if (el == null) return;
        if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
            sb.append(el.getAsString()).append(' ');
            return;
        }
        if (el.isJsonObject()) {
            JsonObject o = el.getAsJsonObject();
            if (o.has("text")) sb.append(o.get("text").getAsString()).append(' ');
            return;
        }
        if (el.isJsonArray()) {
            for (JsonElement e : el.getAsJsonArray()) collectText(e, sb);
        }
    }

    // Build a minimal, policy-compliant tellraw fallback as a one-line command.
    private static String fallbackTellraw(String message) {
        String safe = message == null ? "Unknown error." : message.replace("\"", "'").replace("\r", " ").replace("\n", " ").trim();
        JsonArray arr = new JsonArray();
        arr.add(new JsonPrimitive(""));
        JsonObject tag = new JsonObject();
        tag.addProperty("text", "<ChadGPT> ");
        tag.addProperty("color", "gold");
        tag.addProperty("bold", true);
        arr.add(tag);
        JsonObject msg = new JsonObject();
        msg.addProperty("text", safe);
        msg.addProperty("color", "red");
        arr.add(msg);
        return "tellraw @a " + arr.toString();
    }

    // Helpers.
    private static void backoffSleep(int attempt) {
        try {
            long wait = (long)(RETRY_BASE_MS * Math.pow(2, attempt));
            Thread.sleep(Math.min(wait, 5000));
        } catch (InterruptedException ignored) {}
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
