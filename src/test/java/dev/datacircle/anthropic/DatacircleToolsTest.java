package dev.datacircle.anthropic;

import static dev.datacircle.anthropic.FakeDatacircle.requests;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.anthropic.core.ObjectMappers;
import com.anthropic.models.beta.messages.BetaToolResultBlockParam;
import com.anthropic.models.beta.messages.BetaToolUseBlock;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DatacircleToolsTest {

    private DatacircleTools tools;

    @BeforeAll
    static void start() throws IOException {
        FakeDatacircle.start();
    }

    @AfterAll
    static void stop() {
        FakeDatacircle.stop();
        DatacircleTools.share(null);
    }

    @BeforeEach
    void setUp() {
        tools = FakeDatacircle.tools();
    }

    @Test
    void listsApisAndFiltersThemByWords() {
        assertEquals(
                "- Employee email (email-finder): 5,444 plans from 300 providers. Best: Emelia, 100,000 credits per "
                        + "month, score 76.6/100, $4.99 per 1,000.\n"
                        + "- Company profile (linkedin-company): 1 plan from 1 provider.\n"
                        + "2 of 2 APIs. Pass a slug to datacircle_best_plans or datacircle_search_plans.",
                tools.listApis(new DatacircleTools.ListApis()));
        assertTrue(text("datacircle_list_apis", "{\"query\": \"EMAIL finder\"}").contains("1 of 2 APIs."));
        assertTrue(text("datacircle_list_apis", "{\"query\": null}").contains("2 of 2 APIs."));
        assertEquals("No API's name or slug holds every word of \"zzz\". Leave the query empty to list 2 APIs.",
                text("datacircle_list_apis", "{\"query\": \" zzz \"}"));
    }

    @Test
    void givesTheBestPlansOfAnApiByItsSlugOrName() {
        String best = "Employee email (email-finder): the best 1 of 5,444 plans. "
                + "Sheet: https://datacircle.dev/email-finder-api-pricing\n"
                + "1. Emelia, Pro: score 76.6/100, $4.99 per 1,000 calls, $0 per 1,000 not found, monthly, "
                + "median 812 ms. https://datacircle.dev/find-email-apis/emelia/pro";
        assertEquals(best, text("datacircle_best_plans", "{\"api\": \" Email-Finder \", \"limit\": 3}"));
        assertEquals(best, text("datacircle_best_plans", "{\"api\": \"Email finder API\", \"limit\": null}"));
        assertEquals(List.of("/v1/apis/email-finder/best?limit=3", "/v1/apis/email-finder/best?limit=10"), requests);
    }

    @Test
    void searchesPlansAndSaysWhatItShows() {
        DatacircleTools.SearchPlans args = new DatacircleTools.SearchPlans();
        args.query = Optional.of("Hunter");
        args.sort = Optional.of("Price");
        args.offset = Optional.of(1);
        assertEquals("Found 2 plans; showing 2 to 2.\n"
                + "- Hunter, Starter (People search): rank 18, not scored, no listed price. https://datacircle.dev/p/hunter",
                tools.searchPlans(args));
        assertEquals(List.of("/v1/plans?sort=price&limit=10&offset=1&query=Hunter"), requests);
    }

    @Test
    void givesAProvidersRanksByItsName() {
        DatacircleTools.Provider args = new DatacircleTools.Provider();
        args.provider = "Bright Data";
        assertEquals("Bright Data (https://brightdata.com) sells 2 APIs. Its rank among each API's providers:\n"
                + "- Employee profile (linkedin-profile): rank 3, 2 plans. Best: Growth, score 71.3/100, "
                + "$1.05 per 1,000 records.\n"
                + "- SERP (serp): not ranked, 0 plans.", tools.provider(args));
        assertEquals(List.of("/v1/providers/bright-data"), requests);
    }

    @Test
    void throwsWhatTheModelCanActOn() {
        DatacircleTools.BestPlans nope = new DatacircleTools.BestPlans();
        nope.api = "nope";
        DatacircleException notFound = assertThrows(DatacircleException.class, () -> tools.bestPlans(nope));
        assertEquals(404, notFound.statusCode());
        assertEquals("Datacircle API request failed: HTTP 404. no API has the slug nope. "
                + "Call datacircle_list_apis for every API's slug.", notFound.getMessage());

        DatacircleTools.SearchPlans busy = new DatacircleTools.SearchPlans();
        busy.query = Optional.of("busy");
        assertEquals("Datacircle API request failed: HTTP 429. too many calls. The API allows 60 calls a minute "
                + "from one address. Retry in a minute.",
                assertThrows(DatacircleException.class, () -> tools.searchPlans(busy)).getMessage());

        DatacircleTools.BestPlans tooMany = new DatacircleTools.BestPlans();
        tooMany.api = "email-finder";
        tooMany.limit = Optional.of(51);
        assertEquals("limit must be between 1 and 50",
                assertThrows(IllegalArgumentException.class, () -> tools.bestPlans(tooMany)).getMessage());
        assertTrue(requests.size() == 2);

        DatacircleTools.BestPlans broken = new DatacircleTools.BestPlans();
        broken.api = "broken";
        assertEquals("The Datacircle API returned invalid JSON.",
                assertThrows(DatacircleException.class, () -> tools.bestPlans(broken)).getMessage());
    }

    @Test
    void saysWhenTheApiCannotBeReached() throws IOException {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        DatacircleTools down = new DatacircleTools("http://127.0.0.1:" + port + "/v1", Duration.ofSeconds(2));
        assertEquals("The Datacircle API refused the connection. Check the network and the base URL.",
                assertThrows(DatacircleException.class, () -> down.listApis(new DatacircleTools.ListApis()))
                        .getMessage());
    }

    @Test
    void theArgumentClassesRunOnTheSharedTools() {
        DatacircleTools.BestPlans args = new DatacircleTools.BestPlans();
        args.api = "email-finder";
        args.limit = Optional.of(2);
        assertTrue(args.get().startsWith("Employee email (email-finder): the best 1 of 5,444 plans."));
        assertEquals(List.of("/v1/apis/email-finder/best?limit=2"), requests);
        assertEquals(DatacircleTools.NAMES, DatacircleTools.classes().stream()
                .map(type -> type.getAnnotation(com.fasterxml.jackson.annotation.JsonTypeName.class).value())
                .collect(Collectors.toList()));
    }

    @Test
    void answersAToolUseBlockWithItsResult() throws IOException {
        ToolResultBlockParam ok = tools.result(toolUse("toolu_1", "datacircle_provider",
                "{\"provider\": \"bright-data\", \"extra\": 1}")).get();
        assertEquals("toolu_1", ok.toolUseId());
        assertFalse(ok.isError().orElse(false));
        assertTrue(ok.content().get().string().get().startsWith("Bright Data (https://brightdata.com) sells 2 APIs."));

        ToolResultBlockParam wrong = tools.result(toolUse("toolu_2", "datacircle_best_plans",
                "{\"api\": \"nope\"}")).get();
        assertTrue(wrong.isError().get());
        assertEquals("Error: Datacircle API request failed: HTTP 404. no API has the slug nope. "
                + "Call datacircle_list_apis for every API's slug.", wrong.content().get().string().get());

        ToolResultBlockParam empty = tools.result(toolUse("toolu_3", "datacircle_provider", "{}")).get();
        assertEquals("Error: provider must not be empty", empty.content().get().string().get());
        assertTrue(empty.isError().get());

        ToolResultBlockParam typed = tools.result(toolUse("toolu_4", "datacircle_best_plans",
                "{\"api\": \"email-finder\", \"limit\": \"many\"}")).get();
        assertTrue(typed.isError().get());
        assertTrue(typed.content().get().string().get()
                .startsWith("Error: the input of datacircle_best_plans does not fit its schema: "));

        assertTrue(tools.result(toolUse("toolu_5", "get_weather", "{}")).isEmpty());
    }

    @Test
    void answersABetaToolUseBlockWithItsResult() throws IOException {
        BetaToolUseBlock use = ObjectMappers.jsonMapper().readValue("{\"type\": \"tool_use\", \"id\": \"toolu_9\", "
                + "\"name\": \"datacircle_search_plans\", \"input\": {\"api\": null, \"query\": \"hunter\", "
                + "\"sort\": \"bogus\", \"limit\": null, \"offset\": null}}", BetaToolUseBlock.class);
        BetaToolResultBlockParam result = tools.result(use).get();
        assertEquals("toolu_9", result.toolUseId());
        assertTrue(result.isError().get());
        assertEquals("Error: sort must be one of [price, provider, score]", result.content().get().string().get());
    }

    @Test
    void definesTheToolsForMessages() throws IOException {
        List<Tool> definitions = DatacircleTools.definitions();
        assertEquals(DatacircleTools.NAMES, definitions.stream().map(Tool::name).collect(Collectors.toList()));
        JsonNode search = ObjectMappers.jsonMapper().readTree(
                ObjectMappers.jsonMapper().writeValueAsString(definitions.get(2)));
        assertEquals("object", search.at("/input_schema/type").asText());
        assertEquals("[\"score\",\"provider\",\"price\"]", search.at("/input_schema/properties/sort/enum").toString());
        assertEquals(50, search.at("/input_schema/properties/limit/maximum").asInt());
        assertEquals(0, search.at("/input_schema/required").size());
        JsonNode best = ObjectMappers.jsonMapper().readTree(
                ObjectMappers.jsonMapper().writeValueAsString(definitions.get(1)));
        assertEquals("[\"api\"]", best.at("/input_schema/required").toString());
        assertTrue(best.at("/description").asText().startsWith("Get the best plans of one GTM API"));
    }

    private String text(String name, String input) {
        try {
            return tools.result(toolUse("toolu_0", name, input)).get().content().get().string().get();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static ToolUseBlock toolUse(String id, String name, String input) throws IOException {
        return ObjectMappers.jsonMapper().readValue("{\"type\": \"tool_use\", \"id\": \"" + id + "\", \"name\": \""
                + name + "\", \"input\": " + input + "}", ToolUseBlock.class);
    }
}
