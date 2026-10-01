package org.standict.codelist.validator;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link Successions}. */
class SuccessionsTest {
    private static final java.util.function.Function<String, String> NO_NAMES = code -> null;

    /** STD was never in an EU release, so only the curated list knows that STN replaced it. */
    @Test
    void pairsACuratedRenameWithItsSource() throws Exception {
        var pairs = Successions.load().pairs("Currency", List.of("CUC", "STD"), List.of("STN", "UYW"), NO_NAMES);

        assertEquals(1, pairs.size(), pairs.toString());
        var pair = pairs.get(0);
        assertEquals(List.of("STD", "STN", "renamed", "2018"),
                List.of(pair.oldCode(), pair.newCode(), pair.kind(), pair.year()));
        assertTrue(pair.url().startsWith("https://en.wikipedia.org/wiki/"), pair.url());
        assertEquals("renamed in 2018", pair.when());
    }

    @Test
    void pairsEverySuccessorOfASplit() throws Exception {
        var pairs = Successions.load().pairs("Country", List.of("AN"), List.of("BQ", "CW", "RE", "SX"), NO_NAMES);

        assertEquals(List.of("BQ", "CW", "SX"), pairs.stream().map(Successions.Pair::newCode).toList());
        assertEquals("split in 2010", pairs.get(0).when());
    }

    @Test
    void pairsCodesSpelledAlikeAndCodesNamedAlike() {
        var successions = new Successions(Map.of());

        var spelled = successions.pairs("ICD", List.of("01'00", "01'01"), List.of("0100", "0101", "0201"), NO_NAMES);
        assertEquals(List.of("01'00 0100", "01'01 0101"), spelled.stream()
                .map(pair -> pair.oldCode() + " " + pair.newCode()).toList());
        assertEquals("spelling corrected", spelled.get(0).kind());

        var names = Map.of("X1", "Peso", "X2", "Peso", "Y1", "Name not known", "Y2", "Name not known", "Y3",
                "Name not known");
        var named = successions.pairs("Currency", List.of("X1", "Y1"), List.of("X2", "Y2", "Y3"), names::get);
        assertEquals(List.of("X1 X2"), named.stream().map(pair -> pair.oldCode() + " " + pair.newCode()).toList(),
                "a name several codes share pairs nothing");
    }

    @Test
    void leavesUnrelatedCodesAndListsAlone() throws Exception {
        var successions = Successions.load();

        assertEquals(List.of(), successions.pairs("Currency", List.of("CUC"), List.of("UYW"), NO_NAMES));
        assertEquals(List.of(), successions.pairs("Unit", List.of("STD"), List.of("STN"), NO_NAMES));
        assertEquals("EUR", successions.successorsOf("Currency", "HRK").get(0).newCode());
        assertEquals("replaced by EUR in 2023 (Croatia adopted the euro)",
                successions.successorsOf("Currency", "HRK").get(0).description());
    }
}
