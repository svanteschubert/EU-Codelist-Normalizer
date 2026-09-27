package org.standict.codelist.normalize;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class ReleaseRevisionsTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void rejectsAHashAssignedTwiceAndMultipleSourcesForOneFormatInARevision() {
        var duplicateSource = config();
        add(duplicateSource, 1, "a");
        add(duplicateSource, 2, "a");
        assertThrows(IOException.class, () -> ReleaseRevisions.read(duplicateSource));
        var duplicateDestination = config();
        add(duplicateDestination, 1, "a");
        add(duplicateDestination, 1, "b");
        assertThrows(IOException.class, () -> ReleaseRevisions.read(duplicateDestination));
    }

    @Test
    void rejectsInvalidRevisionNumbersDatesVersionsAndSourceIdentities() {
        var valid = config();
        var entry = add(valid, 1, "a");
        for (int revision : new int[] {0, -1}) {
            entry.put("revision", revision);
            assertThrows(IOException.class, () -> ReleaseRevisions.read(valid));
        }
        entry.put("revision", 1.5);
        assertThrows(IOException.class, () -> ReleaseRevisions.read(valid));
        entry.put("revision", 1);
        entry.put("effective_date", "2026-02-30");
        assertThrows(IOException.class, () -> ReleaseRevisions.read(valid));
        entry.put("effective_date", "2026-05-15").put("version", "../17");
        assertThrows(IOException.class, () -> ReleaseRevisions.read(valid));
        entry.put("version", "17");
        entry.putObject("sources").put("xlsx", "not a hash");
        assertThrows(IOException.class, () -> ReleaseRevisions.read(valid));
        entry.putObject("sources").put("unknown", "a".repeat(64));
        assertThrows(IOException.class, () -> ReleaseRevisions.read(valid));
    }

    @Test
    void separatesAssignmentsByReleaseAndFormat() throws Exception {
        var config = config();
        var entry = add(config, 2, "a");
        ((ObjectNode) entry.get("sources")).put("gc", "b".repeat(64));
        var revisions = ReleaseRevisions.read(config);
        assertEquals(2, revisions.revision("2026-05-15_17", "xlsx", "a".repeat(64)));
        assertEquals(2, revisions.revision("2026-05-15_17", "gc", "b".repeat(64)));
        assertNull(revisions.revision("2026-05-15_17", "gc", "a".repeat(64)));
        assertNull(revisions.revision("2026-05-15_18", "xlsx", "a".repeat(64)));
    }

    private ObjectNode config() {
        var config = json.createObjectNode().put("format_version", 1);
        config.putArray("assignments");
        return config;
    }

    private ObjectNode add(ObjectNode config, int revision, String hashDigit) {
        var entry = config.withArray("assignments").addObject().put("effective_date", "2026-05-15")
                .put("version", "17").put("revision", revision);
        entry.putObject("sources").put("xlsx", hashDigit.repeat(64));
        return entry;
    }
}
