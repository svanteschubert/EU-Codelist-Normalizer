package org.standict.codelist.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.standict.codelist.normalize.GenericodeNormalizer;
import org.standict.codelist.normalize.SpreadsheetExtractor;

/**
 * Writes the contents of each delivery's artefacts into {@code downloaded/} and, where a canonical form exists, their
 * counterparts into {@code normalized/}.
 *
 * <p>The contents are written, not the archives themselves. A ZIP or a workbook tells a reader nothing in a diff, and
 * comparing deliveries is the whole point of the tree: what changed between two publications of a code list is visible
 * in the {@code .gc} files and the sheets, not in the bytes of their container. Keeping only the contents also avoids
 * storing the same archives twice, since the downloader already holds them.
 *
 * <p>Each artefact gets a directory of its own, named after the artefact, so that two revisions of one code list and
 * the unrelated files of a validation artefact can never overwrite each other:
 *
 * <pre>
 * downloaded/2026-05-15/digital-genericodes-2026-05-15/EAS.gc
 * downloaded/2026-05-15/digital-genericodes-2026-05-15_revision02/EAS.gc
 * downloaded/2026-05-15/EN16931 code lists values v17 - used from 2026-05-15/Index.csv
 * normalized/2026-05-15/digital-genericodes-2026-05-15/EAS.gc          (base-36 code order)
 * </pre>
 *
 * <p>Artefacts with no canonical form — a PDF of technical guidance, or an archive holding no code lists — are recorded
 * in the delivery manifest and contribute no normalized files, so that nothing is silently missing from the report.
 */
public final class DeliveryExtractor {
    private static final int MAX_ENTRY_BYTES = 32 * 1024 * 1024;

    private final ObjectMapper json = new ObjectMapper();
    private final GenericodeNormalizer normalizer = new GenericodeNormalizer();
    private final SpreadsheetExtractor spreadsheets = new SpreadsheetExtractor();

    public record Summary(int deliveries, int artefacts, int files, int normalized) {}

    /**
     * @param artefacts the deliveries to write, as {@link DeliveryLayout} grouped them
     * @param downloaded root of the verbatim tree
     * @param normalized root of the canonical tree
     */
    public Summary extract(List<DeliveryLayout.Artefact> artefacts, Path downloaded, Path normalized)
            throws IOException {
        var deliveries = new HashSet<String>();
        int files = 0;
        int normalizedFiles = 0;
        var manifests = new java.util.LinkedHashMap<String, ObjectNode>();
        for (DeliveryLayout.Artefact artefact : artefacts) {
            deliveries.add(artefact.directory());
            String folder = withoutExtension(artefact.revisionedFilename());
            Path verbatim = downloaded.resolve(artefact.directory()).resolve(folder);
            Path canonical = normalized.resolve(artefact.directory()).resolve(folder);
            ObjectNode entry = manifests
                    .computeIfAbsent(artefact.directory(), key -> json.createObjectNode().put("effective_date", key))
                    .withArray("artefacts").addObject();
            entry.put("url", artefact.url()).put("category", artefact.category())
                    .put("filename", artefact.filename()).put("revision", artefact.revision())
                    .put("source_sha256", artefact.sha256()).put("directory", folder);
            List<String> written = switch (kind(artefact.filename())) {
                case ZIP -> extractArchive(artefact, verbatim, canonical, entry);
                case WORKBOOK -> extractWorkbook(artefact, verbatim, canonical, entry);
                case OPAQUE -> {
                    entry.put("extraction", "none");
                    yield List.of();
                }
            };
            files += written.size();
            normalizedFiles += entry.path("normalized_files").asInt();
            entry.put("files", written.size());
        }
        for (var delivery : manifests.entrySet()) {
            Path manifest = downloaded.resolve(delivery.getKey()).resolve("delivery.json");
            Files.createDirectories(manifest.getParent());
            Files.writeString(manifest,
                    json.writerWithDefaultPrettyPrinter().writeValueAsString(delivery.getValue()) + "\n",
                    StandardCharsets.UTF_8);
        }
        return new Summary(deliveries.size(), artefacts.size(), files, normalizedFiles);
    }

    private enum Kind { ZIP, WORKBOOK, OPAQUE }

    private static Kind kind(String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".zip")) {
            return Kind.ZIP;
        }
        return lower.endsWith(".xlsx") || lower.endsWith(".xlsm") ? Kind.WORKBOOK : Kind.OPAQUE;
    }

    /** Writes every entry verbatim, and a canonical copy of each {@code .gc} code list. */
    private List<String> extractArchive(DeliveryLayout.Artefact artefact, Path verbatim, Path canonical,
            ObjectNode entry) throws IOException {
        var written = new ArrayList<String>();
        int normalized = 0;
        entry.put("extraction", "archive-entries-v1");
        try (var archive = new ZipFile(artefact.source().toFile(), StandardCharsets.UTF_8)) {
            var entries = archive.stream().filter(candidate -> !candidate.isDirectory())
                    .sorted(Comparator.comparing(ZipEntry::getName)).toList();
            for (ZipEntry zipEntry : entries) {
                String name = safeEntryName(zipEntry.getName(), artefact);
                byte[] contents;
                try (var stream = archive.getInputStream(zipEntry)) {
                    contents = stream.readNBytes(MAX_ENTRY_BYTES + 1);
                }
                if (contents.length > MAX_ENTRY_BYTES) {
                    throw new IOException("Archive entry exceeds 32 MiB: " + artefact.filename() + "!" + name);
                }
                write(verbatim.resolve(name), contents);
                written.add(name);
                if (name.toLowerCase(Locale.ROOT).endsWith(".gc")) {
                    try {
                        write(canonical.resolve(name), normalizer.normalize(contents).xml());
                        normalized++;
                    } catch (IOException e) {
                        throw new IOException(artefact.filename() + "!" + name + ": " + e.getMessage(), e);
                    }
                }
            }
        }
        entry.put("normalized_files", normalized);
        return written;
    }

    /** Writes each sheet as CSV and, in the same pass, its sorted counterpart. */
    private List<String> extractWorkbook(DeliveryLayout.Artefact artefact, Path verbatim, Path canonical,
            ObjectNode entry) throws IOException {
        entry.put("extraction", "sheet-order-quoted-utf8-csv-v2");
        var sheets = spreadsheets.extract(artefact.source(), verbatim, canonical);
        entry.put("normalized_files", sheets.size());
        return sheets.stream().map(SpreadsheetExtractor.SheetResult::filename).toList();
    }

    /** Rejects absolute paths, {@code ..} segments and NUL bytes before anything is written. */
    private static String safeEntryName(String name, DeliveryLayout.Artefact artefact) throws IOException {
        String normalized = name.replace('\\', '/');
        if (normalized.startsWith("/") || Path.of(normalized).isAbsolute() || normalized.indexOf('\0') >= 0
                || Arrays.asList(normalized.split("/")).contains("..")) {
            throw new IOException("Unsafe entry in " + artefact.filename() + ": " + name);
        }
        return normalized;
    }

    private static void write(Path destination, byte[] contents) throws IOException {
        Files.createDirectories(destination.getParent());
        Files.write(destination, contents);
    }

    private static String withoutExtension(String filename) {
        int extension = filename.lastIndexOf('.');
        return extension <= 0 ? filename : filename.substring(0, extension);
    }
}
