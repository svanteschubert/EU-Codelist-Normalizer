package org.standict.codelist.normalize;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Reads the downloader's registry as the contract between the two independent repositories. */
public final class NormalizationPipeline {
    private static final String GC_CATEGORY = "EN 16931 code list - GeneriCode";
    private static final String XLSX_CATEGORY = "EN 16931 code list - XLSX";
    private static final int MAX_XML_BYTES = 32 * 1024 * 1024;
    private final ObjectMapper json = new ObjectMapper();
    private final GenericodeNormalizer normalizer = new GenericodeNormalizer();
    private final SpreadsheetExtractor extractor = new SpreadsheetExtractor();
    private final ReleaseRevisions revisionAssignments;

    public NormalizationPipeline() { this(null); }

    NormalizationPipeline(ReleaseRevisions revisionAssignments) {
        this.revisionAssignments = revisionAssignments;
    }

    public record Summary(int archives, int files, int rows, int workbooks, int sheets) {}
    private record Source(JsonNode metadata, Path archive, LocalDate date, String version, String folder,
            boolean spreadsheet, int revision) {
        /** Grouping key of {@code release-revisions.json}: effective date and version as the registry states them. */
        String release() { return date + "_" + version; }

        String releaseDirectory() {
            return (version.matches("[0-9]") ? "0" + version : version) + "_" + date;
        }
        String revisionDirectory() { return releaseDirectory() + String.format(Locale.ROOT, "/r%02d", revision); }
        String directory(String stage) { return revisionDirectory() + "/" + stage + "/" + folder; }
    }

    public Summary run(Path downloader, Path output) throws IOException {
        Path inputRoot = downloader.toRealPath();
        Path outputRoot = resolveThroughExistingParent(output.toAbsolutePath().normalize());
        if (outputRoot.startsWith(inputRoot) || inputRoot.startsWith(outputRoot)) {
            throw new IOException("Output must be separate from the downloader repository");
        }
        List<Source> sources = readSources(inputRoot);
        GeneratedOutputs outputs = GeneratedOutputs.read(outputRoot, json);
        Files.createDirectories(outputRoot.getParent());
        Path staging = Files.createTempDirectory(outputRoot.getParent(), ".normalizing-");
        try {
            int files = 0;
            int rows = 0;
            int sheets = 0;
            ObjectNode index = json.createObjectNode();
            index.put("format_version", 5);
            index.put("normalization", "base36-code-order-v1");
            index.put("ph_genericode_version", "8.1.0");
            index.put("spreadsheet_extraction", "sheet-order-quoted-utf8-csv-v2");
            index.put("spreadsheet_normalization", "base36-code-column-order-v2");
            index.put("apache_poi_version", "5.5.1");
            index.put("sources_digest", sourcesDigest(sources));
            var archives = index.putArray("archives");
            var workbooks = index.putArray("workbooks");
            for (Source source : sources) {
                if (source.spreadsheet()) {
                    ObjectNode manifest = extractWorkbook(source, staging);
                    sheets += manifest.path("files").size();
                    workbooks.addObject().put("directory", source.directory("normalized"))
                            .put("extracted_directory", source.directory("extracted"))
                            .put("release_directory", source.releaseDirectory()).put("release_revision", source.revision())
                            .put("revision_directory", source.revisionDirectory())
                            .put("source_sha256", manifest.path("source_sha256").asText())
                            .put("sheets", manifest.path("files").size());
                    continue;
                }
                ObjectNode manifest = normalizeArchive(source, staging);
                files += manifest.path("files").size();
                for (JsonNode file : manifest.path("files")) rows += file.path("rows").asInt();
                archives.addObject().put("directory", source.directory("normalized"))
                        .put("extracted_directory", source.directory("extracted"))
                        .put("release_directory", source.releaseDirectory()).put("release_revision", source.revision())
                        .put("revision_directory", source.revisionDirectory())
                        .put("source_sha256", manifest.path("source_sha256").asText())
                        .put("files", manifest.path("files").size());
            }
            writeJson(staging.resolve("normalization.json"), index);
            // Publish only after all archives and workbooks have passed validation and processing.
            outputs.publish(staging);
            return new Summary(archives.size(), files, rows, workbooks.size(), sheets);
        } finally {
            try (var paths = Files.walk(staging)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private List<Source> readSources(Path inputRoot) throws IOException {
        Path registryFile = inputRoot.resolve("src/main/resources/downloaded-files.json").toRealPath();
        if (!registryFile.startsWith(inputRoot)) throw new IOException("Registry is outside downloader repository");
        JsonNode registry = json.readTree(registryFile.toFile());
        if (registry == null || !registry.isObject()) throw new IOException("Expected a JSON object in " + registryFile);
        var sources = new ArrayList<Source>();
        for (JsonNode metadata : registry) {
            String category = metadata.path("category").asText();
            boolean spreadsheet = XLSX_CATEGORY.equals(category);
            if ((!GC_CATEGORY.equals(category) && !spreadsheet) || !metadata.path("downloaded").asBoolean()) continue;
            String version = requiredText(metadata, "version");
            if (!version.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) throw new IOException("Invalid version: " + version);
            JsonNode dateNode = metadata.path("effective_date");
            LocalDate date;
            try {
                date = dateNode.isArray() && dateNode.size() == 3
                        ? LocalDate.of(dateNode.get(0).asInt(), dateNode.get(1).asInt(), dateNode.get(2).asInt())
                        : LocalDate.parse(dateNode.asText());
            } catch (RuntimeException e) {
                throw new IOException("Missing or invalid effective_date for version " + version, e);
            }
            String hash = requiredText(metadata, "actual_hash");
            if (!hash.matches("[0-9a-f]{64}")) throw new IOException("Invalid source SHA-256: " + hash);
            Path relative = Path.of(requiredText(metadata, "localPath").replace('\\', '/'));
            if (relative.isAbsolute()) throw new IOException("Expected a downloader-relative localPath: " + relative);
            Path archive = inputRoot.resolve(relative).toRealPath();
            if (!archive.startsWith(inputRoot) || !Files.isRegularFile(archive)) {
                throw new IOException("Source archive is outside downloader repository: " + relative);
            }
            String folder = spreadsheet ? "xlsx" : "gc";
            sources.add(new Source(metadata, archive, date, version, folder, spreadsheet, 1));
        }
        if (sources.isEmpty()) throw new IOException("No downloaded Genericode archives or EN16931 workbooks found in " + registryFile);
        ReleaseRevisions revisions = revisionAssignments == null ? ReleaseRevisions.bundled() : revisionAssignments;
        var counts = sources.stream().collect(Collectors.groupingBy(s -> s.release() + "/" + s.folder(), Collectors.counting()));
        var ambiguousReleases = new HashSet<String>();
        for (Source source : sources) {
            if (counts.get(source.release() + "/" + source.folder()) > 1) ambiguousReleases.add(source.release());
        }
        var folders = new HashSet<String>();
        for (int i = 0; i < sources.size(); i++) {
            Source source = sources.get(i);
            String hash = requiredText(source.metadata(), "actual_hash");
            Integer revision = revisions.revision(source.release(), source.folder(), hash);
            if (revision == null && ambiguousReleases.contains(source.release())) {
                throw new IOException("Missing release-revisions.json assignment for " + source.release() + "/" + source.folder()
                        + " source " + hash + "; assign every source in this release explicitly");
            }
            source = new Source(source.metadata(), source.archive(), source.date(), source.version(), source.folder(),
                    source.spreadsheet(), revision == null ? 1 : revision);
            sources.set(i, source);
            if (!folders.add(source.directory("normalized"))) {
                throw new IOException("Multiple sources map to output directory " + source.directory("normalized"));
            }
        }
        sources.sort(Comparator.comparing(Source::releaseDirectory).thenComparingInt(Source::revision).thenComparing(Source::folder));
        return sources;
    }

    private ObjectNode normalizeArchive(Source source, Path staging) throws IOException {
        ObjectNode manifest = sourceManifest(source);
        ObjectNode extractedManifest = manifest.deepCopy();
        extractedManifest.put("extraction", "original-archive-entry-v1");
        extractedManifest.put("normalized", false);
        var extractedFiles = extractedManifest.putArray("files");
        var files = manifest.putArray("files");
        Path destination = staging.resolve(source.directory("normalized"));
        Path extracted = staging.resolve(source.directory("extracted"));
        Files.createDirectories(destination);
        Files.createDirectories(extracted);
        try (var archive = new ZipFile(source.archive().toFile(), StandardCharsets.UTF_8)) {
            var entries = archive.stream().filter(e -> !e.isDirectory() && e.getName().endsWith(".gc"))
                    .sorted(Comparator.comparing(ZipEntry::getName)).toList();
            if (entries.isEmpty()) throw new IOException("No .gc files in " + source.archive());
            var names = new HashSet<String>();
            for (ZipEntry entry : entries) {
                Path entryPath = Path.of(entry.getName().replace('\\', '/'));
                if (entryPath.isAbsolute() || Arrays.asList(entry.getName().replace('\\', '/').split("/")).contains("..")) {
                    throw new IOException("Unsafe ZIP entry: " + entry.getName());
                }
                String name = entryPath.getFileName().toString();
                if (!name.matches("[A-Za-z0-9][A-Za-z0-9._-]*\\.gc") || !names.add(name)) {
                    throw new IOException("Invalid or duplicate Genericode filename: " + entry.getName());
                }
                byte[] input;
                try (var stream = archive.getInputStream(entry)) {
                    input = stream.readNBytes(MAX_XML_BYTES + 1);
                }
                if (input.length > MAX_XML_BYTES) throw new IOException("Genericode exceeds 32 MiB: " + entry.getName());
                GenericodeNormalizer.Result result;
                try {
                    result = normalizer.normalize(input);
                } catch (IOException e) {
                    throw new IOException(source.archive().getFileName() + "!" + entry.getName() + ": " + e.getMessage(), e);
                }
                Files.write(destination.resolve(name), result.xml());
                Files.write(extracted.resolve(name), input);
                files.addObject().put("filename", name).put("source_entry", entry.getName())
                        .put("rows", result.rows()).put("sha256", sha256(destination.resolve(name)));
                extractedFiles.addObject().put("filename", name).put("source_entry", entry.getName())
                        .put("rows", result.rows()).put("sha256", sha256(extracted.resolve(name)));
            }
        }
        writeJson(destination.resolve("source.json"), manifest);
        writeJson(extracted.resolve("source.json"), extractedManifest);
        System.out.printf("%s: %d code lists extracted and normalized%n", source.revisionDirectory(), files.size());
        return manifest;
    }

    private ObjectNode extractWorkbook(Source source, Path staging) throws IOException {
        ObjectNode manifest = sourceManifest(source);
        manifest.put("extraction", "sheet-order-quoted-utf8-csv-v2");
        manifest.put("normalized", false);
        ObjectNode normalizedManifest = manifest.deepCopy();
        normalizedManifest.put("normalized", true);
        normalizedManifest.put("normalization", "base36-code-column-order-v2");
        var files = manifest.putArray("files");
        var normalizedFiles = normalizedManifest.putArray("files");
        Path destination = staging.resolve(source.directory("extracted"));
        Path normalized = staging.resolve(source.directory("normalized"));
        for (var sheet : extractor.extract(source.archive(), destination, normalized)) {
            files.addObject().put("filename", sheet.filename()).put("source_sheet", sheet.sheet())
                    .put("rows", sheet.rows()).put("columns", sheet.columns())
                    .put("sha256", sha256(destination.resolve(sheet.filename())));
            normalizedFiles.addObject().put("filename", sheet.filename()).put("source_sheet", sheet.sheet())
                    .put("rows", sheet.rows()).put("columns", sheet.columns())
                    .put("sha256", sha256(normalized.resolve(sheet.filename())));
        }
        writeJson(destination.resolve("source.json"), manifest);
        writeJson(normalized.resolve("source.json"), normalizedManifest);
        System.out.printf("%s: %d sheets extracted and normalized%n", source.revisionDirectory(), files.size());
        return normalizedManifest;
    }

    private ObjectNode sourceManifest(Source source) throws IOException {
        String hash = sha256(source.archive());
        if (!hash.equals(requiredText(source.metadata(), "actual_hash"))) {
            throw new IOException("Source SHA-256 mismatch: " + source.archive());
        }
        ObjectNode manifest = json.createObjectNode();
        manifest.put("source_url", requiredText(source.metadata(), "url"));
        manifest.put("source_path", requiredText(source.metadata(), "localPath").replace('\\', '/'));
        manifest.put("source_sha256", hash);
        manifest.put("source_filename", source.metadata().path("filename").asText(source.archive().getFileName().toString()));
        manifest.put("version", requiredText(source.metadata(), "version"));
        manifest.put("release_revision", source.revision());
        manifest.put("release_directory", source.releaseDirectory());
        manifest.put("revision_directory", source.revisionDirectory());
        manifest.set("effective_date", source.metadata().get("effective_date"));
        if (source.metadata().hasNonNull("superseded_by")) {
            manifest.set("superseded_by", source.metadata().get("superseded_by"));
        }
        return manifest;
    }

    private void writeJson(Path path, JsonNode node) throws IOException {
        Files.writeString(path, json.writerWithDefaultPrettyPrinter().writeValueAsString(node) + "\n", StandardCharsets.UTF_8);
    }

    private static String requiredText(JsonNode node, String field) throws IOException {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.asText().isBlank()) throw new IOException("Missing text field: " + field);
        return value.asText();
    }

    /**
     * Identifies the downloader snapshot these outputs were normalized from: a digest over the registry facts every
     * consumed source depends on. Deliberately not the SHA-256 of {@code downloaded-files.json} itself, because that
     * changes when the registry is merely re-serialized in another key order or gains an unrelated entry, while the
     * normalized bytes stay the same. The lines are sorted, so the digest depends on the set of consumed sources only.
     */
    private static String sourcesDigest(List<Source> sources) {
        String lines = sources.stream()
                .map(source -> String.join("\t", source.metadata().path("url").asText(), source.version(),
                        source.date().toString(), source.metadata().path("actual_hash").asText(), source.folder(),
                        Integer.toString(source.revision())))
                .sorted()
                .collect(Collectors.joining("\n"));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(lines.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String sha256(Path path) throws IOException {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var input = new DigestInputStream(Files.newInputStream(path), digest)) {
                input.transferTo(java.io.OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Path resolveThroughExistingParent(Path path) throws IOException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return path.toRealPath();
        return resolveThroughExistingParent(path.getParent()).resolve(path.getFileName());
    }

}
