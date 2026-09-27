package org.standict.codelist.normalize;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

/** Explicit source identities, independent of attachment upload numbers and registry order. */
final class ReleaseRevisions {
    private record Key(String release, String format, String hash) {}
    private final Map<Key, Integer> assignments;

    private ReleaseRevisions(Map<Key, Integer> assignments) {
        this.assignments = Map.copyOf(assignments);
    }

    static ReleaseRevisions bundled() throws IOException {
        try (var stream = ReleaseRevisions.class.getResourceAsStream("/release-revisions.json")) {
            if (stream == null) throw new IOException("Missing bundled release-revisions.json");
            return read(new ObjectMapper().readTree(stream));
        }
    }

    static ReleaseRevisions read(JsonNode config) throws IOException {
        if (config == null || config.path("format_version").asInt() != 1 || !config.path("assignments").isArray()) {
            throw new IOException("Expected release-revisions.json format_version 1 with an assignments array");
        }
        var assignments = new HashMap<Key, Integer>();
        var destinations = new HashSet<String>();
        for (var entry : config.path("assignments")) {
            LocalDate date;
            try {
                date = LocalDate.parse(entry.path("effective_date").asText());
            } catch (RuntimeException e) {
                throw new IOException("Invalid revision effective_date", e);
            }
            String version = entry.path("version").asText();
            if (!version.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) throw new IOException("Invalid revision version: " + version);
            var revisionNode = entry.path("revision");
            if (!revisionNode.isIntegralNumber() || !revisionNode.canConvertToInt() || revisionNode.asInt() < 1) {
                throw new IOException("Revision must be a positive integer");
            }
            int revision = revisionNode.asInt();
            String release = date + "_" + version;
            var sources = entry.path("sources");
            if (!sources.isObject() || sources.isEmpty()) throw new IOException("Revision must assign at least one source");
            for (var field : sources.properties()) {
                String format = field.getKey();
                String hash = field.getValue().asText();
                if ((!format.equals("gc") && !format.equals("xlsx")) || !hash.matches("[0-9a-f]{64}")) {
                    throw new IOException("Invalid revision source format or SHA-256: " + format);
                }
                if (assignments.putIfAbsent(new Key(release, format, hash), revision) != null) {
                    throw new IOException("Source assigned more than once: " + release + "/" + format + "/" + hash);
                }
                if (!destinations.add(release + "/" + revision + "/" + format)) {
                    throw new IOException("Multiple sources assigned to the same revision: " + release + "/" + format);
                }
            }
        }
        return new ReleaseRevisions(assignments);
    }

    Integer revision(String release, String format, String hash) {
        return assignments.get(new Key(release, format, hash));
    }
}
