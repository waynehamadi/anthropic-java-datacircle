# Datacircle tools for the Anthropic Java SDK

Find the best GTM APIs. We benchmark all the GTM APIs and we put the results on our website.

Our website: https://datacircle.dev

This library gives the [Anthropic Java SDK](https://github.com/anthropics/anthropic-sdk-java) four tools that call Datacircle's open API, https://api.datacircle.dev/v1. You add them to the tool runner or to your own loop, beside your app's own tools. You need no key and no account.

| Tool | What the tool gives | Call |
| --- | --- | --- |
| `datacircle_list_apis` | Every GTM API on Datacircle, with the API's slug, number of plans, number of providers and best plan | `GET /apis` |
| `datacircle_best_plans` | One API's plans, sorted by score out of 100, best first. Each plan comes with a rank and a price per 1,000 calls | `GET /apis/{slug}/best` |
| `datacircle_search_plans` | Plans that match a provider or plan name, on all APIs or on one API. You can sort them by score, provider or price | `GET /plans` |
| `datacircle_provider` | The APIs a provider sells, and the provider's rank on each API | `GET /providers/{slug}` |

## Install

The library is on [JitPack](https://jitpack.io/#waynehamadi/anthropic-java-datacircle). With Maven:

```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>

<dependency>
    <groupId>com.github.waynehamadi</groupId>
    <artifactId>anthropic-java-datacircle</artifactId>
    <version>0.1.0</version>
</dependency>
```

With Gradle:

```kotlin
repositories {
    maven("https://jitpack.io")
}

dependencies {
    implementation("com.github.waynehamadi:anthropic-java-datacircle:0.1.0")
}
```

The library uses the anthropic-java your app already has, on Java 11 or later. It adds no other dependency. The tool runner needs anthropic-java 2.11.0 or later. `runnableTools()` needs 2.64.0 or later.

## Use the tools in the tool runner

Each tool is an argument class for `addTool(Class)`: `DatacircleTools.ListApis`, `BestPlans`, `SearchPlans` and `Provider`. The tool runner runs them when the model calls them.

```java
import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.helpers.BetaToolRunner;
import com.anthropic.models.beta.messages.BetaMessage;
import com.anthropic.models.beta.messages.MessageCreateParams;
import dev.datacircle.anthropic.DatacircleTools;

AnthropicClient client = AnthropicOkHttpClient.fromEnv();

BetaToolRunner runner = client.beta().messages().toolRunner(DatacircleTools.addTo(MessageCreateParams.builder()
                .model("claude-sonnet-5-5")
                .maxTokens(2048)
                .addUserMessage("Who are the 3 best providers for the email-finder API?"))
        .build());
for (BetaMessage message : runner) {
    message.content().forEach(block -> block.text().ifPresent(text -> System.out.println(text.text())));
}
```

`DatacircleTools.addTo(...)` adds the four classes and gives the builder back, so your own `addTool(...)` calls go after it. You can also add one: `.addTool(DatacircleTools.BestPlans.class)`.

The classes call https://api.datacircle.dev/v1 with a 30 second timeout. For another base URL or timeout, give the runner the tools of an instance instead (anthropic-java 2.64.0 or later):

```java
MessageCreateParams.Builder params = MessageCreateParams.builder()
        .model("claude-sonnet-5-5")
        .maxTokens(2048)
        .addUserMessage("How does Bright Data rank on each API it sells?");
new DatacircleTools("https://api.datacircle.dev/v1", Duration.ofSeconds(10)).runnableTools().forEach(params::addTool);
```

## Use the tools in your own loop

`DatacircleTools.addTo(...)` also takes the params of `client.messages()`. `result(toolUse)` answers a tool call with a tool result. It gives an empty `Optional` when the tool is not one of the four, so your own tools answer the rest.

```java
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.ToolResultBlockParam;

DatacircleTools datacircle = new DatacircleTools();
MessageCreateParams.Builder params = DatacircleTools.addTo(MessageCreateParams.builder()
        .model("claude-sonnet-5-5")
        .maxTokens(2048)
        .addUserMessage("Who are the 3 best providers for the email-finder API?"));

Message message = client.messages().create(params.build());
while (message.stopReason().equals(Optional.of(StopReason.TOOL_USE))) {
    params.addMessage(message);
    List<ContentBlockParam> results = new ArrayList<>();
    for (ContentBlock block : message.content()) {
        block.toolUse().ifPresent(use -> results.add(ContentBlockParam.ofToolResult(
                datacircle.result(use).orElseGet(() -> ToolResultBlockParam.builder()
                        .toolUseId(use.id())
                        .content("No tool is named " + use.name())
                        .isError(true)
                        .build()))));
    }
    params.addUserMessageOfContentBlockParams(results);
    message = client.messages().create(params.build());
}
message.content().forEach(block -> block.text().ifPresent(text -> System.out.println(text.text())));
```

On anthropic-java 2.x, the last builder call is `addUserMessageOfBlockParams(results)`. With `client.beta().messages()`, `DatacircleTools.addTo(...)` takes the beta params and `datacircle.result(...)` answers each `BetaToolUseBlock` the same way. `DatacircleTools.definitions()` gives the four tools' definitions as a list.

## What the tools answer

Each tool answers in short text, one API or plan a line, with each plan's page on datacircle.dev. For example, `datacircle_best_plans` with `{"api": "email-finder", "limit": 3}` answers:

```text
Employee email (email-finder): the best 3 of 5,464 plans. Sheet: https://datacircle.dev/email-finder-api-pricing
1. Emelia, 100,000 credits per month: score 76.6/100, $4.99 per 1,000 calls, $0 per 1,000 not found, monthly. https://datacircle.dev/find-email-apis/emelia/19-monthly
2. ...
```

When a call fails, the tool throws a `DatacircleException` whose message says what to do, for example when a slug does not exist. The tool runner sends that message to the model as an error result, and `result(...)` does the same, so the conversation goes on.

The API allows 60 calls a minute from one address. Its spec is at https://api.datacircle.dev/v1/openapi.json.

## Tests

```bash
./mvnw test                      # offline, on local servers
DATACIRCLE_LIVE=1 ./mvnw test    # also calls each tool on the live API
./mvnw test -Danthropic.version=3.14.0
```

## License

MIT
