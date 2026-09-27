package org.standict.codelist.normalize;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Pins the shape of {@code downloaded-files.json}, the only contract between the two repositories.
 *
 * <p>{@code EU-Codelist-Downloader} owns that file; this repository reads it. Nothing in either build makes the
 * dependency visible, so renaming a field there — or changing {@code effective_date} from a three-number array to a
 * string, or {@code localPath} to an absolute path — would otherwise surface here as a confusing runtime failure long
 * afterwards. The captured sample keeps that contract testable offline; {@link #liveRegistryStillSatisfiesTheContract()}
 * checks the sibling working tree when it happens to be present.
 *
 * <p>Note the mixed naming in the upstream file ({@code localPath} beside {@code actual_hash} and
 * {@code effective_date}): the field names are pinned exactly as they are, not as they ought to be.
 */
class RegistryContractTest {
    /** Categories {@link NormalizationPipeline} consumes; everything else in the registry is ignored by this repository. */
    private static final List<String> CONSUMED =
            List.of("EN 16931 code list - GeneriCode", "EN 16931 code list - XLSX");

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void capturedSampleSatisfiesTheContract() throws Exception {
        JsonNode registry = json.readTree(
                Path.of("src/test/resources/contract/downloaded-files-sample.json").toFile());
        assertEquals(CONSUMED.size(), verifyConsumedEntries(registry),
                "the sample must cover every consumed category");
    }

    @Test
    void liveRegistryStillSatisfiesTheContract() throws Exception {
        Path registryFile = Path.of("../EU-Codelist-Downloader/src/main/resources/downloaded-files.json");
        Assumptions.assumeTrue(Files.isRegularFile(registryFile),
                "sibling EU-Codelist-Downloader not checked out");
        assertTrue(verifyConsumedEntries(json.readTree(registryFile.toFile())) > 0,
                "no downloaded Genericode or XLSX entries: the categories may have been renamed upstream");
    }

    /** Verifies every consumed entry and returns how many categories were covered. */
    private int verifyConsumedEntries(JsonNode registry) {
        assertTrue(registry.isObject(), "registry must be a JSON object keyed by source URL");
        var categories = new java.util.HashSet<String>();
        var entries = registry.fields();
        while (entries.hasNext()) {
            var entry = entries.next();
            JsonNode metadata = entry.getValue();
            String category = metadata.path("category").asText();
            if (!CONSUMED.contains(category) || !metadata.path("downloaded").asBoolean()) {
                continue;
            }
            categories.add(category);
            String where = category + " " + entry.getKey();
            assertTrue(metadata.path("downloaded").isBoolean(), "downloaded must be a boolean: " + where);
            assertEquals(entry.getKey(), metadata.path("url").asText(), "url must repeat the registry key: " + where);
            assertTrue(text(metadata, "version").matches("[A-Za-z0-9][A-Za-z0-9._-]*"), "version: " + where);
            assertTrue(text(metadata, "actual_hash").matches("[0-9a-f]{64}"), "actual_hash: " + where);
            assertFalse(Path.of(text(metadata, "localPath").replace('\\', '/')).isAbsolute(),
                    "localPath must stay relative to the downloader repository: " + where);
            assertFalse(text(metadata, "filename").isBlank(), "filename: " + where);
            assertEffectiveDate(metadata.path("effective_date"), where);
            if (metadata.hasNonNull("superseded_by")) {
                assertFalse(metadata.path("superseded_by").asText().isBlank(), "superseded_by: " + where);
            }
        }
        return categories.size();
    }

    /** Either a three-number array as Jackson writes {@code LocalDate}, or an ISO-8601 string. */
    private void assertEffectiveDate(JsonNode date, String where) {
        if (date.isArray()) {
            assertEquals(3, date.size(), "effective_date array must be [year, month, day]: " + where);
            for (JsonNode part : date) {
                assertTrue(part.isInt(), "effective_date parts must be numbers: " + where);
            }
            return;
        }
        assertTrue(date.isTextual() && date.asText().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"),
                "effective_date must be [year, month, day] or an ISO date: " + where);
    }

    private String text(JsonNode metadata, String field) {
        JsonNode value = metadata.path(field);
        assertTrue(value.isTextual(), "missing text field " + field);
        return value.asText();
    }
}
