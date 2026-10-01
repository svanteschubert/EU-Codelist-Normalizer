package org.standict.codelist.validator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.standict.codelist.index.BusinessTerms;
import org.standict.codelist.index.IndexCheck;

/**
 * Extracts the code lists of every validator release, normalizes them, compares them with the published code lists
 * and writes the result to {@code <output>/validator/}:
 *
 * <pre>
 * validator/
 * ├── validator-index.json                              what was read, from which commit, and what it holds
 * ├── extracted/2026-05-15_validation-1.3.16/ubl/EN16931-UBL-codes.sch   byte for byte as tagged
 * ├── normalized/2026-05-15_validation-1.3.16/ubl/BR-CL-01.csv           one code per row, base-36 order
 * ├── summary.csv  rules.csv                                               the statistics
 * └── index.html
 * </pre>
 *
 * <p>The tree is generated in full on every run, so this pipeline owns it and replaces it wholesale; a
 * {@code validator-index.json} marks it as generated here, and a {@code validator/} directory without one is refused
 * rather than removed. The tree appears only after every release has been read and compared.
 */
public final class ValidatorPipeline {
    static final String DIRECTORY = "validator";
    static final String INDEX = "validator-index.json";
    private static final int FORMAT_VERSION = 1;

    private final ObjectMapper json = new ObjectMapper();
    private final SchematronCodeLists schematron = new SchematronCodeLists();
    private final ValidatorCatalog catalog;

    public ValidatorPipeline() throws IOException {
        this(ValidatorCatalog.load());
    }

    ValidatorPipeline(ValidatorCatalog catalog) {
        this.catalog = catalog;
    }

    /**
     * @param page the page of the shareable report folder, or {@code null} when none was requested
     * @param extracted the folder the extracted copies were published to, or {@code null} when none was requested
     */
    public record Result(int releases, int rules, ValidatorComparison.Report report, IndexCheck.Report index,
            Path directory, Path page, Path extracted) {}

    /**
     * Where the report and the extracted copies it links to are published, each {@code null} for not at all.
     *
     * @param downloader the downloader checkout, whose GitHub origin serves the copies and the original files; the
     *     report links to them only when {@code extractedFolder} lies inside it
     * @param reportFolder the report as a folder to share
     * @param extractedFolder the extracted text of every release, which the report's links point into
     */
    public record Publication(Path downloader, Path reportFolder, Path extractedFolder) {
        public static Publication none() {
            return new Publication(null, null, null);
        }
    }

    /**
     * @param validatorCheckout checkout of the eInvoicing-EN16931 repository, which is only ever read
     * @param outputRoot the normalizer's release tree, which holds the published code lists and receives
     *     {@code validator/}
     */
    public Result run(Path validatorCheckout, Path outputRoot) throws IOException {
        return run(validatorCheckout, outputRoot, Publication.none());
    }

    /**
     * @param reportFolder where to publish the report as a folder to share, or {@code null} for none
     */
    public Result run(Path validatorCheckout, Path outputRoot, Path reportFolder) throws IOException {
        return run(validatorCheckout, outputRoot, new Publication(null, reportFolder, null));
    }

    public Result run(Path validatorCheckout, Path outputRoot, Publication publication) throws IOException {
        var repository = new ValidatorRepository(validatorCheckout);
        Path output = outputRoot.toRealPath();
        if (output.startsWith(repository.root()) || repository.root().startsWith(output)) {
            throw new IOException("Output must be separate from the validator repository");
        }
        Path destination = output.resolve(DIRECTORY);
        requireOwnedOrAbsent(destination);
        // Refuse a folder that is not ours before anything is replaced.
        if (publication.extractedFolder() != null) {
            ReportFolder.requireOwnedOrAbsent(publication.extractedFolder().toAbsolutePath().normalize(),
                    ExtractedFolder.KIND);
        }
        if (publication.reportFolder() != null) {
            ReportFolder.requireOwnedOrAbsent(publication.reportFolder().toAbsolutePath().normalize(), ReportFolder.KIND);
        }
        CodeListReleases codeLists = CodeListReleases.read(output);

        Path staging = Files.createTempDirectory(output, ".validator-");
        try {
            var extracted = new LinkedHashMap<String, Map<Syntax, List<SchematronCodeLists.RuleCodes>>>();
            ObjectNode index = json.createObjectNode();
            index.put("format_version", FORMAT_VERSION);
            index.put("extraction", "git-show-tag-v1");
            index.put("normalization", "distinct-codes-base36-order-v1");
            index.put("syntaxes", "UBL, CII");
            ArrayNode releases = index.putArray("releases");
            int rules = 0;
            var commits = new LinkedHashMap<String, String>();
            for (ValidatorCatalog.Release release : catalog.releases()) {
                ObjectNode entry = releases.addObject();
                commits.put(release.tag(), repository.commit(release.tag()));
                entry.put("tag", release.tag()).put("commit", commits.get(release.tag()))
                        .put("effective_date", release.effectiveDate().toString())
                        .put("effective_date_source", release.source()).put("directory", release.directory());
                var bySyntax = new EnumMap<Syntax, List<SchematronCodeLists.RuleCodes>>(Syntax.class);
                for (Syntax syntax : Syntax.values()) {
                    byte[] source = repository.show(release.tag(), syntax.repositoryPath());
                    String where = release.tag() + ":" + syntax.repositoryPath();
                    List<SchematronCodeLists.RuleCodes> read = schematron.read(source, where);
                    bySyntax.put(syntax, read);
                    rules += read.size();
                    Path verbatim = staging.resolve("extracted").resolve(release.directory())
                            .resolve(syntax.directory()).resolve(syntax.fileName());
                    write(verbatim, source);
                    ObjectNode file = entry.withArray("syntaxes").addObject();
                    file.put("syntax", syntax.name()).put("source", syntax.repositoryPath())
                            .put("extracted", relative(staging, verbatim)).put("source_sha256", sha256(source));
                    ArrayNode ruleEntries = file.putArray("rules");
                    for (SchematronCodeLists.RuleCodes rule : read) {
                        Path normalized = staging.resolve("normalized").resolve(release.directory())
                                .resolve(syntax.directory()).resolve(rule.rule() + ".csv");
                        write(normalized, codesCsv(rule.codes()));
                        ruleEntries.addObject().put("rule", rule.rule())
                                .put("code_list", catalog.mapping(syntax, rule.rule())
                                        .map(ValidatorCatalog.Mapping::codeList).orElse(""))
                                .put("context", rule.context()).put("codes", rule.codes().size())
                                .put("normalized", relative(staging, normalized));
                    }
                }
                extracted.put(release.tag(), bySyntax);
            }
            var report = new ValidatorComparison(repository.webUrl().orElse(null), commits)
                    .compare(catalog, extracted, codeLists);
            var indexCheck = new IndexCheck().check(codeLists);
            var sources = Sources.of(output, publication.downloader(), publication.extractedFolder());
            new ValidatorReport(sources).write(report, indexCheck, staging);
            new ReportFolder().writeManifest(staging, "index.html");
            index.put("compared_dates", report.dates().size());
            index.put("index_revisions_checked", indexCheck.revisions().size());
            index.put("index_rows_checked", indexCheck.revisions().stream().mapToInt(r -> r.tabs().size()).sum());
            index.put("index_rows_mismatching", indexCheck.revisions().stream().flatMap(r -> r.tabs().stream())
                    .filter(tab -> tab.verdict() == IndexCheck.Verdict.MISMATCH).count());
            index.put("business_term_rows_differing_from_2017", indexCheck.revisions().stream()
                    .flatMap(r -> r.terms().stream()).filter(BusinessTerms.Check::differsFrom2017).count());
            Files.writeString(staging.resolve(INDEX),
                    json.writerWithDefaultPrettyPrinter().writeValueAsString(index) + "\n", StandardCharsets.UTF_8);
            // Publish only after every release has been read and compared, the copies before the report linking to them.
            replace(staging, destination);
            if (publication.extractedFolder() != null) {
                new ExtractedFolder().publish(output, publication.extractedFolder());
            }
            Path page = publication.reportFolder() == null ? null
                    : new ReportFolder().publish(destination, publication.reportFolder());
            return new Result(catalog.releases().size(), rules, report, indexCheck, destination, page,
                    publication.extractedFolder());
        } finally {
            deleteRecursively(staging);
        }
    }

    private static byte[] codesCsv(List<String> codes) {
        var text = new StringBuilder("\"Code\"\n");
        codes.forEach(code -> text.append('"').append(code.replace("\"", "\"\"")).append("\"\n"));
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Refuses a directory this pipeline cannot prove it generated, so a mistyped output path deletes nothing. */
    private void requireOwnedOrAbsent(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root)) {
            throw new IOException("Output is not a directory: " + root);
        }
        Path index = root.resolve(INDEX);
        if (!Files.isRegularFile(index)) {
            throw new IOException("Refusing to replace " + root + ": no " + INDEX
                    + ", so this directory was not generated here");
        }
        int version = json.readTree(index.toFile()).path("format_version").asInt();
        if (version != FORMAT_VERSION) {
            throw new IOException("Unexpected " + INDEX + " format_version " + version + " in " + root);
        }
    }

    private static String relative(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    private static void write(Path destination, byte[] contents) throws IOException {
        Files.createDirectories(destination.getParent());
        Files.write(destination, contents);
    }

    private static String sha256(byte[] contents) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(contents));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void replace(Path staged, Path destination) throws IOException {
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            deleteRecursively(destination);
        }
        try {
            Files.move(staged, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(staged, destination);
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
