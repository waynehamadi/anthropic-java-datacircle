package dev.datacircle.anthropic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.ObjectMappers;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.helpers.BetaToolRunner;
import com.anthropic.models.beta.messages.BetaContentBlock;
import com.anthropic.models.beta.messages.BetaMessage;
import com.anthropic.models.beta.messages.BetaMessageParam;
import com.anthropic.models.beta.messages.BetaRawMessageStreamEvent;
import com.anthropic.models.beta.messages.BetaToolResultBlockParam;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The SDK's own paths to the tools, on a scripted Messages API and a fake Datacircle API. */
class SdkPathsTest {

    static final String MODEL = "claude-sonnet-5-5";
    static final String FOUR = "CALL datacircle_list_apis {\"query\": \"email\"} "
            + "CALL datacircle_best_plans {\"api\": \"email-finder\", \"limit\": 2} "
            + "CALL datacircle_search_plans {\"api\": null, \"query\": \"hunter\", \"sort\": \"price\", "
            + "\"limit\": 1, \"offset\": 1} "
            + "CALL datacircle_provider {\"provider\": \"Bright Data\"} ";

    private ScriptedClaude claude;
    private AnthropicClient client;
    private DatacircleTools tools;

    /** An app's own tool beside ours. */
    @JsonClassDescription("Get the weather in a city")
    static final class GetWeather implements Supplier<String> {
        @JsonPropertyDescription("The city")
        public String city;

        @Override
        public String get() {
            return "Sunny in " + city;
        }
    }

    @BeforeAll
    static void startApi() throws IOException {
        FakeDatacircle.start();
    }

    @AfterAll
    static void stopApi() {
        FakeDatacircle.stop();
        DatacircleTools.share(null);
    }

    @BeforeEach
    void setUp() throws IOException {
        claude = new ScriptedClaude();
        client = AnthropicOkHttpClient.builder().baseUrl(claude.baseUrl()).apiKey("test").build();
        tools = FakeDatacircle.tools();
    }

    @AfterEach
    void tearDown() {
        client.close();
        claude.stop();
    }

    @Test
    void theToolRunnerRunsTheArgumentClassesBesideAnAppsOwn() {
        BetaToolRunner runner = client.beta().messages().toolRunner(DatacircleTools.addTo(
                        com.anthropic.models.beta.messages.MessageCreateParams.builder()
                                .model(MODEL)
                                .maxTokens(1024)
                                .addUserMessage(FOUR + "CALL get_weather {\"city\": \"Paris\"} "
                                        + "CALL datacircle_best_plans {\"api\": \"nope\", \"limit\": null}"))
                .addTool(GetWeather.class)
                .build());
        List<BetaMessage> messages = new ArrayList<>();
        runner.forEach(messages::add);

        assertEquals(7, messages.size());
        String done = text(messages.get(6));
        assertFourAnswered(done);
        assertTrue(done.contains("| Sunny in Paris | ERR Error: Datacircle API request failed: HTTP 404. "
                + "no API has the slug nope. Call datacircle_list_apis for every API's slug."), done);

        JsonNode sent = claude.requests.get(0).tools;
        assertEquals(List.of("datacircle_list_apis", "datacircle_best_plans", "datacircle_search_plans",
                "datacircle_provider", "get_weather"), names(sent));
        JsonNode best = sent.get(1);
        assertTrue(best.path("strict").asBoolean());
        assertEquals("[\"api\",\"limit\"]", best.at("/input_schema/required").toString());
        assertFalse(best.at("/input_schema/additionalProperties").asBoolean(true));
        assertEquals("[\"integer\",\"null\"]", best.at("/input_schema/properties/limit/type").toString());
        assertEquals("The API's slug, as datacircle_list_apis gives it, like email-finder",
                best.at("/input_schema/properties/api/description").asText());
        assertEquals(DatacircleTools.definitions().get(1).description().get(), best.path("description").asText());
        assertEquals(List.of("/v1/apis", "/v1/apis/email-finder/best?limit=2",
                "/v1/plans?sort=price&limit=1&offset=1&query=hunter", "/v1/providers/bright-data",
                "/v1/apis/nope/best?limit=10"), FakeDatacircle.requests);
    }

    @Test
    void theToolRunnerStreamsAndRunsParallelCalls() {
        BetaToolRunner runner = client.beta().messages().toolRunner(DatacircleTools.addTo(
                        com.anthropic.models.beta.messages.MessageCreateParams.builder()
                                .model(MODEL)
                                .maxTokens(1024)
                                .addUserMessage(FOUR + "PARALLEL"))
                .build());
        StringBuilder text = new StringBuilder();
        int turns = 0;
        for (StreamResponse<BetaRawMessageStreamEvent> stream : runner.streaming()) {
            turns++;
            stream.stream().forEach(event -> event.contentBlockDelta()
                    .flatMap(delta -> delta.delta().text())
                    .ifPresent(delta -> text.append(delta.text())));
        }
        assertEquals(2, turns);
        assertFourAnswered(text.toString());
        assertTrue(claude.requests.stream().allMatch(request -> request.stream));
        assertEquals(4, claude.requests.get(1).results.size());
    }

    @Test
    void theToolRunnerRunsTheRunnableToolsOfAnInstance() {
        DatacircleTools.share(null);
        com.anthropic.models.beta.messages.MessageCreateParams.Builder params =
                com.anthropic.models.beta.messages.MessageCreateParams.builder()
                        .model(MODEL)
                        .maxTokens(1024)
                        .addUserMessage(FOUR + "CALL get_weather {\"city\": \"Oslo\"}")
                        .addTool(GetWeather.class);
        tools.runnableTools().forEach(params::addTool);
        List<BetaMessage> messages = new ArrayList<>();
        client.beta().messages().toolRunner(params.build()).forEach(messages::add);
        String done = text(messages.get(messages.size() - 1));
        assertFourAnswered(done);
        assertTrue(done.endsWith("| Sunny in Oslo"), done);
        assertEquals(List.of("get_weather", "datacircle_list_apis", "datacircle_best_plans",
                "datacircle_search_plans", "datacircle_provider"), names(claude.requests.get(0).tools));
        assertEquals(4, FakeDatacircle.requests.size());
    }

    @Test
    void anAppsOwnLoopOnMessagesAnswersWithResult() {
        MessageCreateParams.Builder params = DatacircleTools.addTo(MessageCreateParams.builder()
                .model(MODEL)
                .maxTokens(1024)
                .addUserMessage(FOUR + "CALL get_weather {\"city\": \"Rome\"} "
                        + "CALL datacircle_search_plans {\"api\": \"nope\", \"sort\": \"price\"} PARALLEL"));
        String done = null;
        for (int turn = 0; turn < 4 && done == null; turn++) {
            Message message = client.messages().create(params.build());
            params.addMessage(message);
            List<ToolResultBlockParam> results = new ArrayList<>();
            for (ContentBlock block : message.content()) {
                block.toolUse().ifPresent(use -> results.add(tools.result(use).orElseGet(() ->
                        ToolResultBlockParam.builder().toolUseId(use.id()).content("Sunny in Rome").build())));
                block.text().ifPresent(text -> assertTrue(results.isEmpty()));
            }
            if (results.isEmpty()) {
                done = message.content().get(0).text().get().text();
            } else {
                params.addMessage(user(results, MessageParam.class));
            }
        }
        assertFourAnswered(done);
        assertTrue(done.endsWith("| Sunny in Rome | ERR Error: Datacircle API request failed: HTTP 400. "
                + "api must be one of: Employee email. Call datacircle_list_apis for every API's name and slug."),
                done);
        JsonNode sent = claude.requests.get(0).tools;
        assertEquals(DatacircleTools.NAMES, names(sent));
        assertTrue(sent.get(0).path("strict").isMissingNode());
        assertEquals("[\"provider\"]", sent.get(3).at("/input_schema/required").toString());
    }

    @Test
    void anAppsOwnLoopOnBetaMessagesAnswersWithResult() {
        com.anthropic.models.beta.messages.MessageCreateParams.Builder params = DatacircleTools.addTo(
                com.anthropic.models.beta.messages.MessageCreateParams.builder()
                        .model(MODEL)
                        .maxTokens(1024)
                        .addUserMessage(FOUR));
        String done = null;
        for (int turn = 0; turn < 6 && done == null; turn++) {
            BetaMessage message = client.beta().messages().create(params.build());
            params.addMessage(message);
            List<BetaToolResultBlockParam> results = new ArrayList<>();
            for (BetaContentBlock block : message.content()) {
                block.toolUse().ifPresent(use -> results.add(tools.result(use).get()));
            }
            if (results.isEmpty()) {
                done = text(message);
            } else {
                params.addMessage(user(results, BetaMessageParam.class));
            }
        }
        assertFourAnswered(done);
        assertNull(claude.requests.get(4).thread);
    }

    private static void assertFourAnswered(String done) {
        assertTrue(done.startsWith("DONE - Employee email (email-finder): 5,444 plans from 300 providers."), done);
        assertTrue(done.contains("| Employee email (email-finder): the best 1 of 5,444 plans."), done);
        assertTrue(done.contains("| Found 2 plans; showing 2 to 2."), done);
        assertTrue(done.contains("| Bright Data (https://brightdata.com) sells 2 APIs."), done);
        assertFalse(done.contains("ERR Error: Datacircle API request failed: HTTP 429"), done);
    }

    private static String text(BetaMessage message) {
        return message.content().stream()
                .flatMap(block -> block.text().stream())
                .map(block -> block.text())
                .collect(Collectors.joining());
    }

    private static List<String> names(JsonNode tools) {
        List<String> names = new ArrayList<>();
        tools.forEach(tool -> names.add(tool.path("name").asText()));
        return names;
    }

    /** A user message of tool results, made the same way on every version of the SDK. */
    private static <T> T user(List<?> results, Class<T> type) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "user");
        message.put("content", results);
        return ObjectMappers.jsonMapper().convertValue(message, type);
    }
}
