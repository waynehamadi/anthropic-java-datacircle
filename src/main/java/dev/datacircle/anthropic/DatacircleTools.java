package dev.datacircle.anthropic;

import com.anthropic.core.JsonValue;
import com.anthropic.core.ObjectMappers;
import com.anthropic.helpers.BetaRunnableTool;
import com.anthropic.models.beta.messages.BetaToolResultBlockParam;
import com.anthropic.models.beta.messages.BetaToolUseBlock;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Tools for the <a href="https://github.com/anthropics/anthropic-sdk-java">Anthropic Java SDK</a> on the open
 * <a href="https://datacircle.dev">Datacircle</a> API, which benchmarks the GTM APIs (email finders, company
 * enrichment, LinkedIn data and more) and ranks every plan of each by its score out of 100 and its price per 1,000.
 *
 * <p>Each tool is an argument class the SDK's {@code addTool(Class)} takes: {@link ListApis}, {@link BestPlans},
 * {@link SearchPlans} and {@link Provider}. The beta tool runner runs them beside your own tools. For a loop of your
 * own, {@link #addTo(MessageCreateParams.Builder)} gives the model the four tools and {@link #result(ToolUseBlock)}
 * answers each call. The API needs no key and no account. It allows 60 calls a minute from one address. Its spec is
 * at {@code https://api.datacircle.dev/v1/openapi.json}.
 *
 * <pre>{@code
 * BetaToolRunner runner = client.beta().messages().toolRunner(DatacircleTools.addTo(
 *         com.anthropic.models.beta.messages.MessageCreateParams.builder()
 *                 .model("claude-sonnet-5-5")
 *                 .maxTokens(2048)
 *                 .addUserMessage("Which email finder API is best?"))
 *         .build());
 * }</pre>
 */
public final class DatacircleTools {

    /** The four tools' names, as the model calls them. */
    public static final List<String> NAMES = Collections.unmodifiableList(Arrays.asList(
            "datacircle_list_apis", "datacircle_best_plans", "datacircle_search_plans", "datacircle_provider"));

    private static final int MAX_TEXT_LENGTH = 120;
    private static final int MAX_PROVIDER_APIS = 100;
    private static final String NEW_LINE = "\n";

    /** Reads a tool's input into its argument class, as the SDK's tool runner does. */
    private static final ObjectMapper INPUT = JsonMapper.builder().addModule(new Jdk8Module()).build();

    /** The tools the argument classes run on when the tool runner calls them. */
    private static volatile DatacircleTools shared;

    private final DatacircleClient client;

    /** Tools on {@code https://api.datacircle.dev/v1}, with a 30 s timeout. */
    public DatacircleTools() {
        this(DatacircleClient.DEFAULT_BASE_URL, Duration.ofSeconds(30));
    }

    /**
     * Tools on another address of the API, or with another timeout. The argument classes keep running on
     * {@code https://api.datacircle.dev/v1}: give the tool runner {@link #runnableTools()} of these instead.
     *
     * @param baseUrl the API's base URL, {@code https://api.datacircle.dev/v1} when null
     * @param timeout the connection and answer timeout
     */
    public DatacircleTools(String baseUrl, Duration timeout) {
        this.client = new DatacircleClient(baseUrl, timeout);
    }

    /** The arguments of {@code datacircle_list_apis}. The tool runner calls {@link #get()}. */
    @JsonTypeName("datacircle_list_apis")
    @JsonClassDescription("List the GTM APIs that Datacircle ranks (email finder, company enrichment, LinkedIn "
            + "profile and more), each with its slug, how many plans and providers it has, and its best plan.")
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class ListApis implements Supplier<String> {
        @JsonPropertyDescription("Words that must all appear in the API's name or slug, like email or linkedin. "
                + "Null to list every API")
        public Optional<String> query = Optional.empty();

        /** Calls the API: the APIs, one a line. Throws {@link DatacircleException} when it fails. */
        @Override
        public String get() {
            return shared().listApis(this);
        }
    }

    /** The arguments of {@code datacircle_best_plans}. The tool runner calls {@link #get()}. */
    @JsonTypeName("datacircle_best_plans")
    @JsonClassDescription("Get the best plans of one GTM API, best first by Datacircle's score out of 100, with each "
            + "plan's price per 1,000 and its page.")
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class BestPlans implements Supplier<String> {
        @JsonPropertyDescription("The API's slug, as datacircle_list_apis gives it, like email-finder")
        public String api;

        @JsonPropertyDescription("Number of plans from 1 through 50. Null for 10")
        public Optional<Integer> limit = Optional.empty();

        /** Calls the API: the ranked plans, one a line. Throws {@link DatacircleException} when it fails. */
        @Override
        public String get() {
            return shared().bestPlans(this);
        }
    }

    /** The arguments of {@code datacircle_search_plans}. The tool runner calls {@link #get()}. */
    @JsonTypeName("datacircle_search_plans")
    @JsonClassDescription("Search the plans of the GTM APIs on Datacircle by API, and by words in the provider's or "
            + "plan's name, sorted by score (best first), provider (A to Z) or price (lowest $ per 1,000 first).")
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class SearchPlans implements Supplier<String> {
        @JsonPropertyDescription("The API's name or slug, like email-finder. A provider's name goes in query. "
                + "Null for every API")
        public Optional<String> api = Optional.empty();

        @JsonPropertyDescription("Words that must all appear in the provider's or plan's name, like hunter. "
                + "Null for every plan")
        public Optional<String> query = Optional.empty();

        @JsonPropertyDescription("Sort: score, provider or price. Null for score")
        public Optional<String> sort = Optional.empty();

        @JsonPropertyDescription("Number of plans from 1 through 50. Null for 10")
        public Optional<Integer> limit = Optional.empty();

        @JsonPropertyDescription("Number of found plans to skip first. Null for 0")
        public Optional<Integer> offset = Optional.empty();

        /** Calls the API: the plans found, one a line. Throws {@link DatacircleException} when it fails. */
        @Override
        public String get() {
            return shared().searchPlans(this);
        }
    }

    /** The arguments of {@code datacircle_provider}. The tool runner calls {@link #get()}. */
    @JsonTypeName("datacircle_provider")
    @JsonClassDescription("Get one provider's rank on each GTM API it sells on Datacircle, with its best plan there. "
            + "A provider is a company that sells the APIs, like Hunter or Bright Data.")
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Provider implements Supplier<String> {
        @JsonPropertyDescription("The provider's name or slug, like Bright Data or bright-data")
        public String provider;

        /** Calls the API: the provider's ranks, one API a line. Throws {@link DatacircleException} when it fails. */
        @Override
        public String get() {
            return shared().provider(this);
        }
    }

    /** The four argument classes, in the order of {@link #NAMES}. */
    public static List<Class<? extends Supplier<String>>> classes() {
        return Collections.unmodifiableList(Arrays.asList(
                ListApis.class, BestPlans.class, SearchPlans.class, Provider.class));
    }

    /**
     * Adds the four argument classes to beta Messages params, for {@code client.beta().messages().toolRunner(...)},
     * which runs them, or for a loop of your own that answers them with {@link #result(BetaToolUseBlock)}.
     *
     * @param params the params' builder
     * @return the same builder
     */
    public static com.anthropic.models.beta.messages.MessageCreateParams.Builder addTo(
            com.anthropic.models.beta.messages.MessageCreateParams.Builder params) {
        return params.addTool(ListApis.class)
                .addTool(BestPlans.class)
                .addTool(SearchPlans.class)
                .addTool(Provider.class);
    }

    /**
     * Adds the four tools' definitions to Messages params, for a loop of your own on {@code client.messages()} that
     * answers them with {@link #result(ToolUseBlock)}.
     *
     * @param params the params' builder
     * @return the same builder
     */
    public static MessageCreateParams.Builder addTo(MessageCreateParams.Builder params) {
        for (Tool tool : definitions()) {
            params.addTool(tool);
        }
        return params;
    }

    /**
     * The four tools' definitions for {@code client.messages()}, in the order of {@link #NAMES}.
     *
     * @return a new list of the four, one you can add your own tools to
     */
    public static List<Tool> definitions() {
        String limit = "Number of plans from 1 through 50. 10 when left out";
        List<Tool> tools = new ArrayList<>();
        tools.add(definition(ListApis.class, List.of(),
                property("query", "string", "Words that must all appear in the API's name or slug, like email or "
                        + "linkedin. Left out to list every API")));
        tools.add(definition(BestPlans.class, List.of("api"),
                property("api", "string", "The API's slug, as datacircle_list_apis gives it, like email-finder"),
                bounded("limit", limit, 1, 50)));
        Map<String, Object> sort = property("sort", "string", "Sort: score, provider or price. score when left out");
        sort.put("enum", List.of("score", "provider", "price"));
        tools.add(definition(SearchPlans.class, List.of(),
                property("api", "string", "The API's name or slug, like email-finder. A provider's name goes in "
                        + "query. Left out for every API"),
                property("query", "string", "Words that must all appear in the provider's or plan's name, like "
                        + "hunter. Left out for every plan"),
                sort,
                bounded("limit", limit, 1, 50),
                bounded("offset", "Number of found plans to skip first. 0 when left out", 0, null)));
        tools.add(definition(Provider.class, List.of("provider"),
                property("provider", "string", "The provider's name or slug, like Bright Data or bright-data")));
        return tools;
    }

    /**
     * The four tools for the beta tool runner, run on this instance's address and timeout, for
     * {@code addTool(BetaRunnableTool)} on beta Messages params beside your own. Needs anthropic-java 2.64.0 or
     * later, the first with {@code BetaRunnableTool}: on an older one, add the argument classes instead.
     *
     * @return a new list of the four, in the order of {@link #NAMES}
     */
    public List<BetaRunnableTool> runnableTools() {
        List<BetaRunnableTool> tools = new ArrayList<>();
        tools.add(BetaRunnableTool.of(ListApis.class,
                args -> BetaToolResultBlockParam.Content.ofString(listApis(args))));
        tools.add(BetaRunnableTool.of(BestPlans.class,
                args -> BetaToolResultBlockParam.Content.ofString(bestPlans(args))));
        tools.add(BetaRunnableTool.of(SearchPlans.class,
                args -> BetaToolResultBlockParam.Content.ofString(searchPlans(args))));
        tools.add(BetaRunnableTool.of(Provider.class,
                args -> BetaToolResultBlockParam.Content.ofString(provider(args))));
        return tools;
    }

    /**
     * Whether a tool's name is one of the four.
     *
     * @param name the tool's name
     * @return true for one of {@link #NAMES}
     */
    public static boolean handles(String name) {
        return name != null && NAMES.contains(name);
    }

    /**
     * Answers one tool call of the model on {@code client.messages()}, when it calls one of the four.
     *
     * @param toolUse a tool_use block of the model's message
     * @return its tool_result: the answer as text, or the reason as text with {@code is_error} when the call fails.
     *     Empty when the call is of another tool.
     */
    public Optional<ToolResultBlockParam> result(ToolUseBlock toolUse) {
        if (toolUse == null || !handles(toolUse.name())) {
            return Optional.empty();
        }
        Answer answer = answer(toolUse.name(), toolUse._input());
        ToolResultBlockParam.Builder result = ToolResultBlockParam.builder()
                .toolUseId(toolUse.id())
                .content(answer.text);
        if (answer.error) {
            result.isError(true);
        }
        return Optional.of(result.build());
    }

    /**
     * Answers one tool call of the model on {@code client.beta().messages()}, when it calls one of the four.
     *
     * @param toolUse a tool_use block of the model's message
     * @return its tool_result: the answer as text, or the reason as text with {@code is_error} when the call fails.
     *     Empty when the call is of another tool.
     */
    public Optional<BetaToolResultBlockParam> result(BetaToolUseBlock toolUse) {
        if (toolUse == null || !handles(toolUse.name())) {
            return Optional.empty();
        }
        Answer answer = answer(toolUse.name(), toolUse._input());
        BetaToolResultBlockParam.Builder result = BetaToolResultBlockParam.builder()
                .toolUseId(toolUse.id())
                .content(answer.text);
        if (answer.error) {
            result.isError(true);
        }
        return Optional.of(result.build());
    }

    /**
     * {@code datacircle_list_apis}: the GTM APIs Datacircle ranks, each with its slug, its counts and its best plan.
     *
     * @param args the call's arguments
     * @return the APIs, one a line
     * @throws DatacircleException when the API answers an error or does not answer
     */
    public String listApis(ListApis args) {
        return formatApis(client.listApis(), value(args.query));
    }

    /**
     * {@code datacircle_best_plans}: one API's best plans, best first by score.
     *
     * @param args the call's arguments
     * @return the ranked plans, one a line
     * @throws DatacircleException when the API answers an error or does not answer
     * @throws IllegalArgumentException when an argument is out of its range
     */
    public String bestPlans(BestPlans args) {
        try {
            return formatBestPlans(client.bestPlans(args.api, value(args.limit)));
        } catch (DatacircleException e) {
            throw e.statusCode() == 404 ? e.withHint("Call datacircle_list_apis for every API's slug.") : e;
        }
    }

    /**
     * {@code datacircle_search_plans}: the plans of every API, or of one, by provider or plan name.
     *
     * @param args the call's arguments
     * @return the plans found, one a line
     * @throws DatacircleException when the API answers an error or does not answer
     * @throws IllegalArgumentException when an argument is out of its range
     */
    public String searchPlans(SearchPlans args) {
        String api = value(args.api);
        Integer offset = value(args.offset);
        try {
            return formatSearch(
                    client.searchPlans(api, value(args.query), value(args.sort), value(args.limit), offset),
                    offset == null ? 0 : offset);
        } catch (DatacircleException e) {
            boolean unknownApi = api != null && !api.isBlank() && (e.statusCode() == 400 || e.statusCode() == 404);
            throw unknownApi ? e.withHint("Call datacircle_list_apis for every API's name and slug.") : e;
        }
    }

    /**
     * {@code datacircle_provider}: one provider's rank on each API it sells.
     *
     * @param args the call's arguments
     * @return the provider's ranks, one API a line
     * @throws DatacircleException when the API answers an error or does not answer
     * @throws IllegalArgumentException when the name is empty or has no Latin letter or digit
     */
    public String provider(Provider args) {
        return formatProvider(client.provider(args.provider));
    }

    /** Sets the tools the argument classes run on. For the tests, on a fake of the API. */
    static void share(DatacircleTools tools) {
        shared = tools;
    }

    private static DatacircleTools shared() {
        DatacircleTools tools = shared;
        if (tools == null) {
            synchronized (DatacircleTools.class) {
                if (shared == null) {
                    shared = new DatacircleTools();
                }
                tools = shared;
            }
        }
        return tools;
    }

    /** A call's answer, or its reason when it fails, as the SDK's tool runner words it. */
    private static final class Answer {
        final String text;
        final boolean error;

        Answer(String text, boolean error) {
            this.text = text;
            this.error = error;
        }
    }

    private Answer answer(String name, JsonValue input) {
        Object args;
        try {
            args = INPUT.readValue(ObjectMappers.jsonMapper().writeValueAsString(input), classOf(name));
        } catch (JsonProcessingException e) {
            return new Answer("Error: the input of " + name + " does not fit its schema: "
                    + e.getOriginalMessage(), true);
        }
        try {
            if (args instanceof ListApis) {
                return new Answer(listApis((ListApis) args), false);
            }
            if (args instanceof BestPlans) {
                return new Answer(bestPlans((BestPlans) args), false);
            }
            if (args instanceof SearchPlans) {
                return new Answer(searchPlans((SearchPlans) args), false);
            }
            return new Answer(provider((Provider) args), false);
        } catch (RuntimeException e) {
            String message = e.getMessage();
            return new Answer("Error: " + (message == null || message.isBlank()
                    ? "the Datacircle API request failed." : message), true);
        }
    }

    private static Class<?> classOf(String name) {
        switch (name) {
            case "datacircle_list_apis":
                return ListApis.class;
            case "datacircle_best_plans":
                return BestPlans.class;
            case "datacircle_search_plans":
                return SearchPlans.class;
            default:
                return Provider.class;
        }
    }

    @SafeVarargs
    private static Tool definition(Class<?> type, List<String> required, Map<String, Object>... properties) {
        Map<String, Object> props = new LinkedHashMap<>();
        for (Map<String, Object> property : properties) {
            props.put((String) property.remove("name"), property);
        }
        return Tool.builder()
                .name(type.getAnnotation(JsonTypeName.class).value())
                .description(type.getAnnotation(JsonClassDescription.class).value())
                .inputSchema(Tool.InputSchema.builder()
                        .properties(JsonValue.from(props))
                        .required(required)
                        .build())
                .build();
    }

    private static Map<String, Object> property(String name, String type, String description) {
        Map<String, Object> property = new LinkedHashMap<>();
        property.put("name", name);
        property.put("type", type);
        property.put("description", description);
        return property;
    }

    private static Map<String, Object> bounded(String name, String description, int minimum, Integer maximum) {
        Map<String, Object> property = property(name, "integer", description);
        property.put("minimum", minimum);
        if (maximum != null) {
            property.put("maximum", maximum);
        }
        return property;
    }

    private static <T> T value(Optional<T> optional) {
        return optional == null ? null : optional.orElse(null);
    }

    private static String formatApis(Object apis, String query) {
        if (!(apis instanceof List)) {
            throw unexpected("the list of APIs is not a list");
        }
        List<?> list = (List<?>) apis;
        if (list.isEmpty()) {
            return "The Datacircle API returned no APIs.";
        }
        List<String> words = words(query);
        List<String> lines = new ArrayList<>();
        for (Object api : list) {
            String name = required(api, "api");
            String slug = required(api, "slug");
            if (!matchesAll(name + " " + slug, words)) {
                continue;
            }
            StringBuilder line = new StringBuilder("- ")
                    .append(truncate(name))
                    .append(" (")
                    .append(slug)
                    .append("): ")
                    .append(count(field(api, "plans"), "plan"))
                    .append(" from ")
                    .append(count(field(api, "providers"), "provider"))
                    .append('.');
            Object best = field(api, "best");
            if (best instanceof Map) {
                line.append(" Best: ")
                        .append(truncate(required(best, "provider")))
                        .append(", ")
                        .append(truncate(required(best, "plan")))
                        .append(", ")
                        .append(score(field(best, "score")))
                        .append(", ")
                        .append(price(field(best, "perThousand"), null))
                        .append('.');
            }
            lines.add(line.toString());
        }
        if (lines.isEmpty()) {
            return "No API's name or slug holds every word of \"" + query.trim() + "\". Leave the query empty to list "
                    + list.size() + " APIs.";
        }
        return String.join(NEW_LINE, lines) + NEW_LINE + lines.size() + " of " + list.size()
                + " APIs. Pass a slug to datacircle_best_plans or datacircle_search_plans.";
    }

    private static String formatBestPlans(Object response) {
        List<?> plans = list(response, "plans");
        StringBuilder result = new StringBuilder()
                .append(required(response, "api"))
                .append(" (")
                .append(required(response, "slug"))
                .append("): ");
        if (plans.isEmpty()) {
            return result.append("no plans found.").toString();
        }
        result.append("the best ")
                .append(plans.size())
                .append(" of ")
                .append(count(field(response, "total"), "plan"))
                .append('.');
        appendIfPresent(result, "Sheet", text(response, "sheetPage"));
        for (Object plan : plans) {
            result.append(NEW_LINE).append(formatPlan(plan, false));
        }
        return result.toString();
    }

    private static String formatSearch(Object response, int requestedOffset) {
        List<?> plans = list(response, "plans");
        Object total = field(response, "total");
        Object offsetField = field(response, "offset");
        int offset = offsetField instanceof BigDecimal ? ((BigDecimal) offsetField).intValue() : requestedOffset;
        if (plans.isEmpty()) {
            if (total instanceof BigDecimal && ((BigDecimal) total).signum() > 0) {
                return "Found " + count(total, "plan") + ", but the offset " + offset
                        + " is past the last one. Use an offset below " + ((BigDecimal) total).toPlainString() + ".";
            }
            return "No plans found.";
        }
        StringBuilder result = new StringBuilder("Found ")
                .append(count(total, "plan"))
                .append("; showing ")
                .append(offset + 1)
                .append(" to ")
                .append(offset + plans.size())
                .append('.');
        appendIfPresent(result, "Sheet", text(response, "sheetPage"));
        for (Object plan : plans) {
            result.append(NEW_LINE).append(formatPlan(plan, true));
        }
        return result.toString();
    }

    private static String formatProvider(Object response) {
        List<?> apis = list(response, "apis");
        StringBuilder result = new StringBuilder(required(response, "provider"));
        String website = text(response, "website");
        if (!website.isEmpty()) {
            result.append(" (").append(website).append(')');
        }
        if (apis.isEmpty()) {
            return result.append(" sells no API on Datacircle.").toString();
        }
        result.append(" sells ").append(plural(apis.size(), "API")).append(". Its rank among each API's providers:");
        int shown = 0;
        for (Object api : apis) {
            if (shown == MAX_PROVIDER_APIS) {
                result.append(NEW_LINE)
                        .append("And ")
                        .append(plural(apis.size() - shown, "more API"))
                        .append('.');
                break;
            }
            shown++;
            Object rank = field(api, "rank");
            List<?> plans = list(api, "plans");
            result.append(NEW_LINE)
                    .append("- ")
                    .append(truncate(required(api, "api")))
                    .append(" (")
                    .append(required(api, "slug"))
                    .append("): ")
                    .append(rank instanceof BigDecimal ? "rank " + ((BigDecimal) rank).intValue() : "not ranked")
                    .append(", ")
                    .append(plural(plans.size(), "plan"))
                    .append('.');
            Object best = bestScored(plans);
            if (best != null) {
                result.append(" Best: ")
                        .append(truncate(required(best, "plan")))
                        .append(", ")
                        .append(score(field(best, "score")))
                        .append(", ")
                        .append(price(field(best, "perThousand"), field(best, "per")))
                        .append('.');
            }
        }
        return result.toString();
    }

    private static Object bestScored(List<?> plans) {
        Object best = null;
        BigDecimal bestScore = null;
        for (Object plan : plans) {
            Object score = field(plan, "score");
            if (score instanceof BigDecimal && (bestScore == null || ((BigDecimal) score).compareTo(bestScore) > 0)) {
                best = plan;
                bestScore = (BigDecimal) score;
            }
        }
        return best;
    }

    private static String formatPlan(Object plan, boolean withApi) {
        StringBuilder line = new StringBuilder();
        Object rank = field(plan, "rank");
        boolean ranked = rank instanceof BigDecimal;
        line.append(!withApi && ranked ? ((BigDecimal) rank).intValue() + ". " : "- ")
                .append(truncate(required(plan, "provider")))
                .append(", ")
                .append(truncate(required(plan, "plan")));
        if (withApi) {
            line.append(" (").append(truncate(required(plan, "api"))).append(')');
        }
        line.append(": ");
        if (withApi && ranked) {
            line.append("rank ").append(((BigDecimal) rank).intValue()).append(", ");
        }
        line.append(score(field(plan, "score")))
                .append(", ")
                .append(price(field(plan, "perThousand"), field(plan, "per")));
        Object notFound = field(plan, "notFoundPerThousand");
        if (notFound instanceof BigDecimal) {
            line.append(", ").append(dollars((BigDecimal) notFound)).append(" per 1,000 not found");
        }
        String billing = text(plan, "billing");
        if (!billing.isEmpty()) {
            line.append(", ").append(billing);
        }
        Object latency = field(plan, "latencyMs");
        if (latency instanceof BigDecimal) {
            line.append(", median ").append(((BigDecimal) latency).longValue()).append(" ms");
        }
        line.append('.');
        String page = text(plan, "page");
        if (!page.isEmpty()) {
            line.append(' ').append(page);
        }
        return line.toString();
    }

    private static String score(Object score) {
        return score instanceof BigDecimal
                ? "score " + ((BigDecimal) score).setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
                        + "/100"
                : "not scored";
    }

    private static String price(Object perThousand, Object per) {
        if (!(perThousand instanceof BigDecimal)) {
            return "no listed price";
        }
        String unit = per instanceof String && !((String) per).isBlank() ? " " + per + "s" : "";
        return dollars((BigDecimal) perThousand) + " per 1,000" + unit;
    }

    private static String dollars(BigDecimal amount) {
        return "$" + amount.stripTrailingZeros().toPlainString();
    }

    private static String count(Object number, String word) {
        return number instanceof BigDecimal ? plural(((BigDecimal) number).longValue(), word) : "? " + word + "s";
    }

    private static String plural(long count, String word) {
        return String.format(Locale.ROOT, "%,d", count) + " " + word + (count == 1 ? "" : "s");
    }

    private static List<String> words(String query) {
        if (query == null || query.isBlank()) {
            return Collections.emptyList();
        }
        return Arrays.asList(fold(query).trim().split("[\\s-]+"));
    }

    private static boolean matchesAll(String text, List<String> words) {
        String folded = fold(text);
        return words.stream().allMatch(folded::contains);
    }

    private static String fold(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);
    }

    private static void appendIfPresent(StringBuilder result, String label, String value) {
        if (!value.isEmpty()) {
            result.append(' ').append(label).append(": ").append(value);
        }
    }

    private static Object field(Object node, String name) {
        return node instanceof Map ? ((Map<?, ?>) node).get(name) : null;
    }

    private static String required(Object node, String name) {
        Object value = field(node, name);
        if (!(value instanceof String) || ((String) value).isBlank()) {
            throw unexpected(name + " is missing");
        }
        return (String) value;
    }

    private static List<?> list(Object node, String name) {
        Object value = field(node, name);
        if (!(value instanceof List)) {
            throw unexpected(name + " is not a list");
        }
        return (List<?>) value;
    }

    private static String text(Object node, String name) {
        Object value = field(node, name);
        return value instanceof String && !((String) value).isBlank() ? (String) value : "";
    }

    private static DatacircleException unexpected(String what) {
        return new DatacircleException("The Datacircle API returned an answer of an unexpected shape: " + what
                + ". Retry later, or read https://api.datacircle.dev/v1/openapi.json.");
    }

    private static String truncate(String text) {
        if (text.length() <= MAX_TEXT_LENGTH) {
            return text;
        }
        return text.substring(0, MAX_TEXT_LENGTH - 3) + "...";
    }
}
