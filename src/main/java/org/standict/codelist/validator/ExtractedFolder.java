package org.standict.codelist.validator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Publishes the extracted text of every code-list release where the report's links can point at a line of it: the
 * Genericode files byte for byte as the archives hold them, and every sheet of the workbooks as CSV, each with its
 * {@code source.json}.
 *
 * <pre>
 * EU-Codelist-Downloader/docs/extracted/
 * ├── manifest.json
 * └── 17_2026-05-15/r02/gc/Currency.gc  …/xlsx/Index.csv  …/xlsx/source.json
 * </pre>
 *
 * <p>The originals are binary, so a browser cannot show a line of them; these copies can, and on GitHub with line
 * numbers. The folder is replaced as a whole on every run, and one without this generator's manifest of the same kind
 * is refused rather than overwritten.
 */
public final class ExtractedFolder {
    static final String KIND = "extracted";

    private final ObjectMapper json = new ObjectMapper();

    /**
     * Copies {@code <release>/rNN/extracted/{gc,xlsx}/} of every release below {@code releases} to
     * {@code <destination>/<release>/rNN/{gc,xlsx}/}.
     *
     * @return the number of files published, the manifest not counted
     */
    public int publish(Path releases, Path destination) throws IOException {
        Path target = destination.toAbsolutePath().normalize();
        Path root = releases.toRealPath();
        if (target.startsWith(root) || root.startsWith(target)) {
            throw new IOException("The extracted copies must be published outside the release tree: " + target);
        }
        ReportFolder.requireOwnedOrAbsent(target, KIND);
        Files.createDirectories(target.getParent());
        Path staging = Files.createTempDirectory(target.getParent(), "." + target.getFileName() + "-");
        try {
            var paths = new ArrayList<String>();
            for (CodeListReleases.Release release : CodeListReleases.read(root).releases()) {
                for (CodeListReleases.Revision revision : release.revisions()) {
                    for (String format : List.of("gc", "xlsx")) {
                        Path source = revision.stage("extracted", format);
                        if (source == null) {
                            continue;
                        }
                        String prefix = revision.name() + "/" + format + "/";
                        try (var files = Files.list(source)) {
                            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                                String path = prefix + file.getFileName();
                                Files.createDirectories(staging.resolve(path).getParent());
                                Files.copy(file, staging.resolve(path));
                                paths.add(path);
                            }
                        }
                    }
                }
            }
            ObjectNode manifest = json.createObjectNode();
            manifest.put("format_version", ReportFolder.FORMAT_VERSION);
            manifest.put("generator", ReportFolder.GENERATOR);
            manifest.put("kind", KIND);
            manifest.put("description", "The extracted text of every EN16931 code-list release the comparison read: "
                    + "the Genericode files as the archives hold them, the workbook sheets as CSV.");
            var files = manifest.putArray("files");
            for (String path : paths) {
                ReportFolder.addFile(files.addObject(), staging, path, path.endsWith("source.json")
                        ? "where the files of this directory were extracted from" : "");
            }
            Files.writeString(staging.resolve(ReportFolder.MANIFEST),
                    json.writerWithDefaultPrettyPrinter().writeValueAsString(manifest) + "\n", StandardCharsets.UTF_8);
            ReportFolder.replace(staging, target);
            return paths.size();
        } finally {
            ReportFolder.deleteRecursively(staging);
        }
    }
}
