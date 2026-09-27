package org.standict.codelist.delivery;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Unit tests for {@link DeliveryLayout}. */
class DeliveryLayoutTest {
    @TempDir Path temp;
    private final ObjectMapper json = new ObjectMapper();
    private final ObjectNode registry = json.createObjectNode();

    @Test
    void groupsArtefactsByEffectiveDateAcrossEveryCategory() throws Exception {
        add("genericode", "EN 16931 code list - GeneriCode", "17", 2026, 5, 15, "digital-genericodes.zip");
        add("ubl", "validation-artefacts-UBL", "1.3.14", 2026, 5, 15, "ubl-artefacts.zip");
        add("eas", "EAS code list", "16", 2025, 11, 15, "EAS.xlsx");
        save();

        List<DeliveryLayout.Artefact> artefacts = new DeliveryLayout().read(input());

        assertEquals(List.of("2025-11-15/EAS.xlsx", "2026-05-15/digital-genericodes.zip",
                "2026-05-15/ubl-artefacts.zip"), artefacts.stream().map(DeliveryLayout.Artefact::path).toList());
        assertEquals("validation-artefacts-UBL", artefacts.get(2).category());
    }

    /**
     * A correction republished under the same filename is what {@code superseded_by} records. Both publications are
     * kept, ordered along the chain, so the correction stays visible instead of overwriting its predecessor.
     */
    @Test
    void keepsEveryRevisionAlongTheSupersessionChain() throws Exception {
        ObjectNode first = add("first", "EN 16931 code list - GeneriCode", "17", 2026, 5, 15, "digital-genericodes.zip");
        ObjectNode second = add("second", "EN 16931 code list - GeneriCode", "17", 2026, 5, 15, "digital-genericodes.zip");
        ObjectNode third = add("third", "EN 16931 code list - GeneriCode", "17", 2026, 5, 15, "digital-genericodes.zip");
        first.put("superseded_by", url("second"));
        second.put("superseded_by", url("third"));
        save();

        List<DeliveryLayout.Artefact> artefacts = new DeliveryLayout().read(input());

        assertEquals(List.of("2026-05-15/digital-genericodes.zip",
                "2026-05-15/digital-genericodes_revision02.zip", "2026-05-15/digital-genericodes_revision03.zip"),
                artefacts.stream().map(DeliveryLayout.Artefact::path).toList());
        assertEquals(List.of(url("first"), url("second"), url("third")),
                artefacts.stream().map(DeliveryLayout.Artefact::url).toList());
    }

    /** The delivery must depend on the registry's contents, never on the order its keys happen to be written in. */
    @Test
    void layoutIsIndependentOfRegistryKeyOrder() throws Exception {
        ObjectNode first = add("first", "EN 16931 code list - GeneriCode", "17", 2026, 5, 15, "genericodes.zip");
        add("other", "EAS code list", "16", 2025, 11, 15, "EAS.xlsx");
        ObjectNode second = add("second", "EN 16931 code list - GeneriCode", "17", 2026, 5, 15, "genericodes.zip");
        first.put("superseded_by", url("second"));
        save();
        var expected = new DeliveryLayout().read(input()).stream().map(DeliveryLayout.Artefact::path).toList();

        var reversed = json.createObjectNode();
        var names = new java.util.ArrayList<String>();
        registry.fieldNames().forEachRemaining(names::add);
        java.util.Collections.reverse(names);
        for (String name : names) {
            reversed.set(name, registry.get(name));
        }
        json.writeValue(input().resolve("src/main/resources/downloaded-files.json").toFile(), reversed);

        assertEquals(expected, new DeliveryLayout().read(input()).stream()
                .map(DeliveryLayout.Artefact::path).toList());
    }

    /** An artefact the registry gives no effective date for is kept aside rather than dropped. */
    @Test
    void keepsUndatedArtefacts() throws Exception {
        ObjectNode guidance = add("guidance", "guidance", "1", 2026, 5, 15, "guidance.pdf");
        guidance.remove("effective_date");
        save();

        assertEquals(List.of("undated/guidance.pdf"),
                new DeliveryLayout().read(input()).stream().map(DeliveryLayout.Artefact::path).toList());
    }

    /** Two artefacts sharing a filename in one delivery without a superseded_by link cannot be told apart. */
    @Test
    void refusesAnUnlinkedFilenameCollision() throws Exception {
        add("first", "EN 16931 code list - GeneriCode", "17", 2026, 5, 15, "genericodes.zip");
        add("second", "EN 16931 code list - GeneriCode", "17", 2026, 5, 15, "genericodes.zip");
        save();

        var failure = assertThrows(IOException.class, () -> new DeliveryLayout().read(input()));
        assertTrue(failure.getMessage().contains("superseded_by"), failure.getMessage());
    }

    @Test
    void refusesACycleOfCorrections() throws Exception {
        ObjectNode first = add("first", "EAS code list", "16", 2025, 11, 15, "a.xlsx");
        ObjectNode second = add("second", "EAS code list", "16", 2025, 11, 15, "b.xlsx");
        first.put("superseded_by", url("second"));
        second.put("superseded_by", url("first"));
        save();

        assertThrows(IOException.class, () -> new DeliveryLayout().read(input()));
    }

    /** The real registry must lay out without an unlinked collision; every artefact lands in exactly one delivery. */
    @Test
    void liveRegistryLaysOutWithoutCollisions() throws Exception {
        Path downloader = Path.of("../EU-Codelist-Downloader");
        Assumptions.assumeTrue(Files.isRegularFile(downloader.resolve("src/main/resources/downloaded-files.json")),
                "sibling EU-Codelist-Downloader not checked out");

        List<DeliveryLayout.Artefact> artefacts = new DeliveryLayout().read(downloader);

        assertFalse(artefacts.isEmpty());
        assertEquals(artefacts.size(), artefacts.stream().map(DeliveryLayout.Artefact::path).distinct().count());
        assertTrue(artefacts.stream().anyMatch(a -> a.revision() > 1),
                "the registry records corrections, so some delivery must hold more than one revision");
    }

    private ObjectNode add(String name, String category, String version, int year, int month, int day,
            String filename) throws IOException {
        Path file = input().resolve("src/main/resources/downloaded-files/" + name + "-" + filename);
        Files.createDirectories(file.getParent());
        Files.writeString(file, name);
        ObjectNode metadata = registry.putObject(url(name));
        metadata.put("url", url(name));
        metadata.put("category", category);
        metadata.put("downloaded", true);
        metadata.put("localPath", input().relativize(file).toString());
        metadata.put("actual_hash", "0".repeat(64));
        metadata.put("version", version);
        metadata.put("filename", filename);
        metadata.putArray("effective_date").add(year).add(month).add(day);
        return metadata;
    }

    private String url(String name) {
        return "https://example.test/" + name;
    }

    private Path input() throws IOException {
        Path root = temp.resolve("downloader");
        Files.createDirectories(root.resolve("src/main/resources"));
        return root;
    }

    private void save() throws IOException {
        json.writeValue(input().resolve("src/main/resources/downloaded-files.json").toFile(), registry);
    }
}
