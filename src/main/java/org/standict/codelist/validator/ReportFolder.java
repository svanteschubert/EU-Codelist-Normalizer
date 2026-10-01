package org.standict.codelist.validator;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.HexFormat;

/**
 * Publishes the comparison report as a folder that can be copied anywhere and still works: the page, under a name that
 * says what it is, next to every file it links to, and a {@code manifest.json} naming each file with its SHA-256.
 *
 * <pre>
 * docs/en16931-code-list-comparison/
 * ├── en16931-code-list-comparison.html
 * ├── about.html
 * ├── manifest.json
 * ├── summary.csv  rules.csv  index-claims.csv  business-terms.csv  index-releases.csv
 * └── configuration/validator-releases.csv  rule-catalog.csv  business-terms-2017.csv  code-successions.csv
 * </pre>
 *
 * <p>Links to the folder's own files are relative, links to where a finding is written point to GitHub, and the page
 * loads nothing from the network, so the folder needs no server configuration. The folder is replaced as a whole on
 * every run; one that exists without this generator's manifest of the same kind is refused rather than overwritten.
 */
public final class ReportFolder {
    /** The page's name in the published folder, recognisable once the folder has been copied elsewhere. */
    public static final String PAGE = "en16931-code-list-comparison.html";
    static final String MANIFEST = "manifest.json";
    static final String GENERATOR = "eu-codelist-normalizer --validator";
    static final int FORMAT_VERSION = 1;
    /** What a published folder holds; a report folder written before kinds were recorded has none. */
    static final String KIND = "report";

    private final ObjectMapper json = new ObjectMapper();

    /** Lists the page and every linked file present in {@code directory}, with sizes and SHA-256. */
    public void writeManifest(Path directory, String page) throws IOException {
        ObjectNode manifest = json.createObjectNode();
        manifest.put("format_version", FORMAT_VERSION);
        manifest.put("generator", GENERATOR);
        manifest.put("kind", KIND);
        manifest.put("page", page);
        var files = manifest.putArray("files");
        addFile(files.addObject(), directory, page, "the comparison report; open it in a browser");
        for (ValidatorReport.LinkedFile linked : ValidatorReport.LINKED_FILES) {
            if (Files.isRegularFile(directory.resolve(linked.path()))) {
                addFile(files.addObject(), directory, linked.path(), linked.description());
            }
        }
        Files.writeString(directory.resolve(MANIFEST),
                json.writerWithDefaultPrettyPrinter().writeValueAsString(manifest) + "\n", StandardCharsets.UTF_8);
    }

    /**
     * Copies the report of {@code report} (its {@code index.html} and linked files) to {@code destination}.
     *
     * @return the published page
     */
    public Path publish(Path report, Path destination) throws IOException {
        Path target = destination.toAbsolutePath().normalize();
        requireOwnedOrAbsent(target, KIND);
        Files.createDirectories(target.getParent());
        Path staging = Files.createTempDirectory(target.getParent(), "." + target.getFileName() + "-");
        try {
            Files.copy(report.resolve("index.html"), staging.resolve(PAGE));
            for (ValidatorReport.LinkedFile linked : ValidatorReport.LINKED_FILES) {
                Path source = report.resolve(linked.path());
                if (Files.isRegularFile(source)) {
                    Files.createDirectories(staging.resolve(linked.path()).getParent());
                    Files.copy(source, staging.resolve(linked.path()));
                }
                if (linked.path().endsWith(".html") && Files.isRegularFile(source)) {
                    // Pages link back to the report under the name it has here.
                    Path page = staging.resolve(linked.path());
                    Files.writeString(page, Files.readString(page, StandardCharsets.UTF_8)
                            .replace("href=\"index.html\"", "href=\"" + PAGE + "\""), StandardCharsets.UTF_8);
                }
            }
            writeManifest(staging, PAGE);
            replace(staging, target);
            return target.resolve(PAGE);
        } finally {
            deleteRecursively(staging);
        }
    }

    /**
     * Refuses a folder this generator cannot prove it published as {@code kind}, so a mistyped path deletes nothing:
     * neither a hand-made folder nor the other kind of published folder.
     */
    static void requireOwnedOrAbsent(Path root, String kind) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root)) {
            throw new IOException("Published folder is not a directory: " + root);
        }
        Path manifest = root.resolve(MANIFEST);
        var read = Files.isRegularFile(manifest) ? new ObjectMapper().readTree(manifest.toFile()) : null;
        if (read == null || !GENERATOR.equals(read.path("generator").asText())) {
            throw new IOException("Refusing to replace " + root + ": no " + MANIFEST + " from " + GENERATOR
                    + ", so this folder was not generated here");
        }
        if (!kind.equals(read.path("kind").asText(KIND))) {
            throw new IOException("Refusing to replace " + root + ": it holds the " + read.path("kind").asText()
                    + " folder, not the " + kind + " folder");
        }
    }

    /** Moves a staged folder into place, replacing what was there. */
    static void replace(Path staging, Path target) throws IOException {
        deleteRecursively(target);
        try {
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(staging, target);
        }
    }

    static void addFile(ObjectNode entry, Path directory, String path, String description) throws IOException {
        byte[] contents = Files.readAllBytes(directory.resolve(path));
        try {
            entry.put("path", path).put("bytes", contents.length)
                    .put("sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(contents)));
            if (!description.isEmpty()) {
                entry.put("description", description);
            }
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static void deleteRecursively(Path root) throws IOException {
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
