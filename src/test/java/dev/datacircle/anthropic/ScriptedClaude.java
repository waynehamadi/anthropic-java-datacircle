package dev.datacircle.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A scripted Messages API on 127.0.0.1: POST /v1/messages, plain or streamed, beta or not.
 *
 * <p>The first user message holds lines {@code CALL <tool> <json input>}. Each turn the model asks for the next call
 * not yet answered, or for all of them at once when the message holds PARALLEL. Once every call has its tool_result,
 * it answers {@code DONE} and each result, {@code ERR } before one with is_error. Every request is logged: its tools as
 * sent, whether it streams, its thread and the results it carries. It keeps message threads as the SDK sends them: a
 * request whose thread is {@code {type: continue, previous_message_id}} sends only its new messages and no tools.
 */
final class ScriptedClaude {

    static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern CALL = Pattern.compile("CALL (\\w+) (\\{.*?\\})(?=\\s*(?:CALL|PARALLEL|$))",
            Pattern.DOTALL);

    /** One request as it came. */
    static final class Request {
        final boolean stream;
        final String thread;
        final JsonNode tools;
        final List<JsonNode> results;

        Request(boolean stream, String thread, JsonNode tools, List<JsonNode> results) {
            this.stream = stream;
            this.thread = thread;
            this.tools = tools;
            this.results = results;
        }
    }

    final List<Request> requests = new CopyOnWriteArrayList<>();
    private final Map<String, ObjectNode> threads = new ConcurrentHashMap<>();
    private final AtomicInteger ids = new AtomicInteger();
    private final HttpServer server;

    ScriptedClaude() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/v1/messages", this::handle);
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        JsonNode body = JSON.readTree(exchange.getRequestBody());
        ArrayNode messages = JSON.createArrayNode();
        JsonNode tools = body.path("tools");
        JsonNode thread = body.path("thread");
        if ("continue".equals(thread.path("type").asText())) {
            ObjectNode earlier = threads.get(thread.path("previous_message_id").asText());
            messages.addAll((ArrayNode) earlier.get("messages"));
            tools = earlier.get("tools");
        }
        body.path("messages").forEach(messages::add);

        String first = text(messages.get(0).path("content"));
        List<String[]> calls = new ArrayList<>();
        Matcher matcher = CALL.matcher(first);
        while (matcher.find()) {
            calls.add(new String[] {matcher.group(1), matcher.group(2)});
        }
        List<JsonNode> results = new ArrayList<>();
        for (JsonNode message : messages) {
            if ("user".equals(message.path("role").asText()) && message.path("content").isArray()) {
                for (JsonNode part : message.path("content")) {
                    if ("tool_result".equals(part.path("type").asText())) {
                        results.add(part);
                    }
                }
            }
        }
        requests.add(new Request(body.path("stream").asBoolean(false),
                thread.isMissingNode() ? null : thread.path("type").asText(), tools, results));

        ArrayNode content = JSON.createArrayNode();
        if (results.size() >= calls.size()) {
            List<String> done = new ArrayList<>();
            for (JsonNode result : results) {
                done.add((result.path("is_error").asBoolean(false) ? "ERR " : "") + text(result.path("content")));
            }
            content.addObject().put("type", "text").put("text", "DONE " + String.join(" | ", done));
        } else {
            int last = first.contains("PARALLEL") ? calls.size() : results.size() + 1;
            for (int i = results.size(); i < last; i++) {
                content.addObject()
                        .put("type", "tool_use")
                        .put("id", String.format("toolu_%02d", i + 1))
                        .put("name", calls.get(i)[0])
                        .set("input", JSON.readTree(calls.get(i)[1]));
            }
        }
        String stop = "tool_use".equals(content.get(0).path("type").asText()) ? "tool_use" : "end_turn";
        String id = String.format("msg_%04d", ids.incrementAndGet());
        ObjectNode message = JSON.createObjectNode()
                .put("id", id)
                .put("type", "message")
                .put("role", "assistant")
                .put("model", body.path("model").asText());
        message.set("content", content);
        message.put("stop_reason", stop).putNull("stop_sequence");
        message.putObject("usage").put("input_tokens", 1).put("output_tokens", 1);

        ObjectNode held = JSON.createObjectNode();
        ArrayNode history = messages.deepCopy();
        history.addObject().put("role", "assistant").set("content", content);
        held.set("messages", history);
        held.set("tools", tools);
        threads.put(id, held);

        if (!body.path("stream").asBoolean(false)) {
            send(exchange, "application/json", JSON.writeValueAsString(message));
            return;
        }
        StringBuilder events = new StringBuilder();
        ObjectNode start = message.deepCopy();
        start.set("content", JSON.createArrayNode());
        start.putNull("stop_reason");
        event(events, "message_start", JSON.createObjectNode().set("message", start));
        for (int index = 0; index < content.size(); index++) {
            JsonNode block = content.get(index);
            ObjectNode empty = block.deepCopy();
            String whole;
            String deltaType;
            String field;
            if ("text".equals(block.path("type").asText())) {
                empty.put("text", "");
                whole = block.path("text").asText();
                deltaType = "text_delta";
                field = "text";
            } else {
                empty.putObject("input");
                whole = JSON.writeValueAsString(block.get("input"));
                deltaType = "input_json_delta";
                field = "partial_json";
            }
            event(events, "content_block_start",
                    JSON.createObjectNode().put("index", index).set("content_block", empty));
            int cut = Math.min(7, whole.length());
            for (String piece : new String[] {whole.substring(0, cut), whole.substring(cut)}) {
                ObjectNode delta = JSON.createObjectNode().put("index", index);
                delta.putObject("delta").put("type", deltaType).put(field, piece);
                event(events, "content_block_delta", delta);
            }
            event(events, "content_block_stop", JSON.createObjectNode().put("index", index));
        }
        ObjectNode end = JSON.createObjectNode();
        end.putObject("delta").put("stop_reason", stop).putNull("stop_sequence");
        end.putObject("usage").put("output_tokens", 1);
        event(events, "message_delta", end);
        event(events, "message_stop", JSON.createObjectNode());
        send(exchange, "text/event-stream", events.toString());
    }

    private static void event(StringBuilder events, String type, ObjectNode data) throws IOException {
        ObjectNode typed = JSON.createObjectNode().put("type", type);
        typed.setAll(data);
        events.append("event: ").append(type).append("\ndata: ").append(JSON.writeValueAsString(typed)).append("\n\n");
    }

    private static String text(JsonNode content) {
        if (content.isTextual()) {
            return content.asText();
        }
        StringBuilder text = new StringBuilder();
        for (JsonNode part : content) {
            JsonNode value = part.has("text") ? part.get("text") : part.path("content");
            if (text.length() > 0) {
                text.append(' ');
            }
            text.append(text(value));
        }
        return text.toString();
    }

    private static void send(HttpExchange exchange, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", type);
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
