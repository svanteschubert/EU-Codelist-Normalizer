package org.standict.codelist.delivery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Groups every downloaded artefact of the downloader's registry by the effective date it applies from.
 *
 * <p>The registry stores artefacts by download URL, which says nothing about which delivery they belong to. What a
 * reader of the EN 16931 code lists wants to see is a delivery: everything that came into force on one date, side by
 * side — the Genericode package, the spreadsheets, the EAS and VATEX lists, the UBL and CII validation artefacts.
 *
 * <p><strong>Every revision is kept.</strong> The European Commission republishes an artefact under the same filename
 * when it corrects it, and the registry records that as {@code superseded_by} pointing at the replacement. Dropping the
 * superseded entry would hide the correction, which is one of the few things a reader most wants to see, so a corrected
 * artefact keeps its own file, distinguished by a {@code _revisionNN} suffix in front of the extension. The chain of
 * {@code superseded_by} links gives the order: the first publication keeps the Commission's own filename, its
 * replacement becomes {@code …_revision02}, the next {@code …_revision03}. A file therefore never has to be renamed
 * when a correction appears later, and an artefact that was never corrected is named exactly as it was published.
 *
 * <p>The layout is a pure function of the registry's contents, never of its key order: entries are assigned revisions
 * along the supersession chain and then sorted by date, filename and revision.
 */
public final class DeliveryLayout {
    /** Holds artefacts the registry gives no effective date for, so that they are never silently dropped. */
    static final String UNDATED = "undated";

    private final ObjectMapper json = new ObjectMapper();

    /** One downloaded artefact, placed in the delivery of its effective date. */
    public record Artefact(String url, LocalDate effectiveDate, String category, String version, String filename,
            String sha256, Path source, int revision) {

        /** {@code 2026-05-15}, or {@code undated} when the registry states no effective date. */
        public String directory() {
            return effectiveDate == null ? UNDATED : effectiveDate.toString();
        }

        /**
         * The published filename for the first publication, and {@code name_revisionNN.ext} for every correction after
         * it, so that all revisions of one artefact sit side by side in their delivery.
         */
        public String revisionedFilename() {
            if (revision <= 1) {
                return filename;
            }
            String suffix = String.format(Locale.ROOT, "_revision%02d", revision);
            int extension = filename.lastIndexOf('.');
            return extension <= 0 ? filename + suffix
                    : filename.substring(0, extension) + suffix + filename.substring(extension);
        }

        public String path() {
            return directory() + "/" + revisionedFilename();
        }
    }

    /**
     * Reads every downloaded artefact of the registry, in delivery order.
     *
     * @param downloaderRoot checkout of the downloader, whose registry and files are only ever read
     */
    public List<Artefact> read(Path downloaderRoot) throws IOException {
        Path inputRoot = downloaderRoot.toRealPath();
        Path registryFile = inputRoot.resolve("src/main/resources/downloaded-files.json").toRealPath();
        if (!registryFile.startsWith(inputRoot)) {
            throw new IOException("Registry is outside downloader repository");
        }
        JsonNode registry = json.readTree(registryFile.toFile());
        if (registry == null || !registry.isObject()) {
            throw new IOException("Expected a JSON object in " + registryFile);
        }
        Map<String, Integer> revisions = revisionsAlongSupersessionChains(registry);
        var artefacts = new ArrayList<Artefact>();
        var entries = registry.fields();
        while (entries.hasNext()) {
            var entry = entries.next();
            JsonNode metadata = entry.getValue();
            if (!metadata.path("downloaded").asBoolean()) {
                continue;
            }
            Path relative = Path.of(requiredText(metadata, "localPath").replace('\\', '/'));
            if (relative.isAbsolute()) {
                throw new IOException("Expected a downloader-relative localPath: " + relative);
            }
            Path source = inputRoot.resolve(relative).toRealPath();
            if (!source.startsWith(inputRoot) || !Files.isRegularFile(source)) {
                throw new IOException("Artefact is outside the downloader repository: " + relative);
            }
            String filename = requiredText(metadata, "filename");
            if (filename.contains("/") || filename.contains("\\") || filename.equals(".") || filename.equals("..")) {
                throw new IOException("Unsafe artefact filename: " + filename);
            }
            String hash = requiredText(metadata, "actual_hash");
            if (!hash.matches("[0-9a-f]{64}")) {
                throw new IOException("Invalid artefact SHA-256: " + hash);
            }
            artefacts.add(new Artefact(entry.getKey(), effectiveDate(metadata, entry.getKey()),
                    metadata.path("category").asText(""), metadata.path("version").asText(""), filename, hash, source,
                    revisions.getOrDefault(entry.getKey(), 1)));
        }
        artefacts.sort(Comparator.comparing((Artefact a) -> a.effectiveDate() == null ? LocalDate.MAX : a.effectiveDate())
                .thenComparing(Artefact::filename).thenComparingInt(Artefact::revision));
        var seen = new HashMap<String, String>();
        for (Artefact artefact : artefacts) {
            String clash = seen.putIfAbsent(artefact.path(), artefact.url());
            if (clash != null) {
                throw new IOException("Two artefacts map to " + artefact.path() + ": " + clash + " and "
                        + artefact.url() + "; the registry must link them with superseded_by");
            }
        }
        return List.copyOf(artefacts);
    }

    /**
     * Numbers each artefact along its chain of {@code superseded_by} links, so the first publication is {@code r01} and
     * every correction takes the next number. Only URLs that take part in a chain are returned; everything else is
     * {@code r01} by default.
     */
    private Map<String, Integer> revisionsAlongSupersessionChains(JsonNode registry) throws IOException {
        var replacementOf = new LinkedHashMap<String, String>();
        var superseded = new HashMap<String, String>();
        var entries = registry.fields();
        while (entries.hasNext()) {
            var entry = entries.next();
            String replacement = entry.getValue().path("superseded_by").asText("");
            if (replacement.isEmpty()) {
                continue;
            }
            if (!registry.has(replacement)) {
                throw new IOException("superseded_by points at an unknown entry: " + replacement);
            }
            replacementOf.put(entry.getKey(), replacement);
            String other = superseded.putIfAbsent(replacement, entry.getKey());
            if (other != null) {
                throw new IOException("Two entries claim to be superseded by " + replacement);
            }
        }
        var revisions = new HashMap<String, Integer>();
        for (String start : replacementOf.keySet()) {
            if (superseded.containsKey(start)) {
                continue; // Not the head of its chain; reached from an earlier publication instead.
            }
            int revision = 1;
            for (String url = start; url != null; url = replacementOf.get(url)) {
                if (revisions.put(url, revision++) != null) {
                    throw new IOException("Cycle in superseded_by at " + url);
                }
            }
        }
        // A chain that loops back on itself has no first publication, so the walk above never enters it.
        for (String url : replacementOf.keySet()) {
            if (!revisions.containsKey(url)) {
                throw new IOException("Cycle in superseded_by at " + url);
            }
        }
        return revisions;
    }

    private LocalDate effectiveDate(JsonNode metadata, String url) throws IOException {
        JsonNode date = metadata.path("effective_date");
        if (date.isMissingNode() || date.isNull()) {
            return null;
        }
        try {
            return date.isArray() && date.size() >= 3
                    ? LocalDate.of(date.get(0).asInt(), date.get(1).asInt(), date.get(2).asInt())
                    : LocalDate.parse(date.asText());
        } catch (RuntimeException e) {
            throw new IOException("Invalid effective_date for " + url, e);
        }
    }

    private static String requiredText(JsonNode metadata, String field) throws IOException {
        JsonNode value = metadata.path(field);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new IOException("Missing text field: " + field);
        }
        return value.asText();
    }
}
