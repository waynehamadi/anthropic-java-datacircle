package dev.datacircle.anthropic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Each tool on the live API, as the tool runner calls it. Run with DATACIRCLE_LIVE=1. */
@EnabledIfEnvironmentVariable(named = "DATACIRCLE_LIVE", matches = "1")
class DatacircleToolsLiveTest {

    @BeforeAll
    static void live() {
        DatacircleTools.share(null);
    }

    @Test
    void listsTheApis() {
        DatacircleTools.ListApis args = new DatacircleTools.ListApis();
        args.query = Optional.of("email finder");
        String text = args.get();
        assertTrue(text.contains("(email-finder): "), text);
        assertTrue(text.contains(" APIs. Pass a slug to datacircle_best_plans"), text);
    }

    @Test
    void givesTheBestPlans() {
        DatacircleTools.BestPlans args = new DatacircleTools.BestPlans();
        args.api = "email-finder";
        args.limit = Optional.of(3);
        String text = args.get();
        assertTrue(text.startsWith("Employee email (email-finder): the best 3 of "), text);
        assertTrue(text.contains("\n1. ") && text.contains("\n3. ") && text.contains("https://datacircle.dev/"), text);
    }

    @Test
    void searchesThePlans() {
        DatacircleTools.SearchPlans args = new DatacircleTools.SearchPlans();
        args.query = Optional.of("hunter");
        args.sort = Optional.of("price");
        args.limit = Optional.of(2);
        String text = args.get();
        assertTrue(text.startsWith("Found ") && text.contains("; showing 1 to 2."), text);
        assertTrue(text.contains("\n- Hunter, "), text);
    }

    @Test
    void givesAProvider() {
        DatacircleTools.Provider args = new DatacircleTools.Provider();
        args.provider = "Bright Data";
        String text = args.get();
        assertTrue(text.startsWith("Bright Data (https://brightdata.com) sells "), text);
    }

    @Test
    void saysWhenAnApiIsUnknown() {
        DatacircleTools.BestPlans args = new DatacircleTools.BestPlans();
        args.api = "no-such-thing";
        DatacircleException e = assertThrows(DatacircleException.class, args::get);
        assertEquals(404, e.statusCode());
        assertTrue(e.getMessage().endsWith("Call datacircle_list_apis for every API's slug."), e.getMessage());
    }
}
