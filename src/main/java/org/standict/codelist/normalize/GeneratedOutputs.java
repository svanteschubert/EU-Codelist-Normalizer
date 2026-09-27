package org.standict.codelist.normalize;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

/** Validates previous manifests before publishing and removes only obsolete, tracked outputs. */
final class GeneratedOutputs {
    private final Path root;
    private final Map<Path, String> previous;

    private GeneratedOutputs(Path root, Map<Path, String> previous) {
        this.root = root;
        this.previous = previous;
    }

    static GeneratedOutputs read(Path root, ObjectMapper json) throws IOException {
        var result = new GeneratedOutputs(root, new HashMap<>());
        Path indexFile = root.resolve("normalization.json");
        result.checkPath(indexFile);
        if (!Files.exists(indexFile)) return result;
        var index = json.readTree(indexFile.toFile());
        if (index == null || index.path("format_version").asInt() < 3 || index.path("format_version").asInt() > 5) {
            throw new IOException("Expected existing output manifest format_version 3, 4 or 5: " + indexFile);
        }
        for (String category : new String[] {"archives", "workbooks"}) {
            String format = category.equals("archives") ? "gc" : "xlsx";
            String extension = format.equals("gc") ? ".gc" : ".csv";
            if (!index.path(category).isArray()) throw new IOException("Missing " + category + " in " + indexFile);
            for (var entry : index.path(category)) {
                for (String stage : new String[] {"normalized", "extracted"}) {
                    String directory = entry.path(stage.equals("normalized") ? "directory" : "extracted_directory").asText();
                    String releasePattern = "([0-9]{4}-[0-9]{2}-[0-9]{2}_[A-Za-z0-9][A-Za-z0-9._-]*"
                            + "|[A-Za-z0-9][A-Za-z0-9._-]*_[0-9]{4}-[0-9]{2}-[0-9]{2})";
                    if (!directory.matches(releasePattern + "/(r[0-9]{2,}/)?"
                            + stage + "/" + format + "(/(revisions|variants)/[0-9a-f]{64})?")) {
                        throw new IOException("Unsafe generated directory in existing manifest: " + directory);
                    }
                    Path destination = root.resolve(directory);
                    Path manifestFile = destination.resolve("source.json");
                    result.checkPath(manifestFile);
                    var manifest = json.readTree(manifestFile.toFile());
                    if (manifest == null || !manifest.path("files").isArray()
                            || !manifest.path("source_sha256").asText().equals(entry.path("source_sha256").asText())) {
                        throw new IOException("Invalid existing source manifest: " + manifestFile);
                    }
                    result.track(manifestFile, NormalizationPipeline.sha256(manifestFile));
                    for (var file : manifest.path("files")) {
                        String filename = file.path("filename").asText();
                        if (!filename.endsWith(extension) || filename.contains("/") || filename.contains("\\")
                                || filename.indexOf('\0') >= 0) {
                            throw new IOException("Unsafe generated filename in existing manifest: " + filename);
                        }
                        result.track(destination.resolve(filename), file.path("sha256").asText());
                    }
                }
            }
        }
        return result;
    }

    private void track(Path path, String hash) throws IOException {
        checkPath(path);
        if (!hash.matches("[0-9a-f]{64}") || !Files.isRegularFile(path) || !NormalizationPipeline.sha256(path).equals(hash)) {
            throw new IOException("Existing output SHA-256 mismatch: " + path);
        }
        if (previous.putIfAbsent(path, hash) != null) throw new IOException("Duplicate existing output: " + path);
    }

    void publish(Path staging) throws IOException {
        java.util.List<Path> files;
        try (var paths = Files.walk(staging)) {
            // Publish the index only after every data file and source manifest is in place.
            files = paths.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing((Path p) -> p.equals(staging.resolve("normalization.json")))
                            .thenComparing(Comparator.naturalOrder())).toList();
        }
        var destinations = new HashSet<Path>();
        for (Path file : files) {
            Path destination = root.resolve(staging.relativize(file));
            checkPath(destination);
            if (Files.exists(destination) && (!Files.isRegularFile(destination)
                    || (!previous.containsKey(destination) && !destination.equals(root.resolve("normalization.json"))
                    && Files.mismatch(file, destination) != -1))) {
                throw new IOException("Untracked output collision: " + destination);
            }
            destinations.add(destination);
        }
        for (Path file : files) {
            Path destination = root.resolve(staging.relativize(file));
            Files.createDirectories(destination.getParent());
            if (Files.isRegularFile(destination) && Files.mismatch(file, destination) == -1) continue;
            try {
                Files.move(file, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(file, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        var directories = new HashSet<Path>();
        // Recheck obsolete files before deleting any of them, in case a file changed during processing.
        var obsolete = previous.keySet().stream().filter(p -> !destinations.contains(p)).sorted().toList();
        for (Path path : obsolete) {
            checkPath(path);
            if (!NormalizationPipeline.sha256(path).equals(previous.get(path))) {
                throw new IOException("Obsolete output changed during processing; preserving it: " + path);
            }
        }
        for (Path path : obsolete) {
            Files.delete(path);
            for (Path parent = path.getParent(); !parent.equals(root); parent = parent.getParent()) directories.add(parent);
        }
        for (Path directory : directories.stream().sorted(Comparator.comparingInt(Path::getNameCount).reversed()).toList()) {
            try {
                Files.delete(directory);
            } catch (DirectoryNotEmptyException e) {
                // Unrelated files, or current generated files, keep their containing directories.
            }
        }
    }

    private void checkPath(Path destination) throws IOException {
        if (!destination.normalize().startsWith(root)) throw new IOException("Output escapes destination: " + destination);
        for (Path path = destination; path != null && path.startsWith(root); path = path.getParent()) {
            if (Files.isSymbolicLink(path)) throw new IOException("Output contains a symbolic link: " + path);
            if (!path.equals(destination) && Files.exists(path, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(path)) {
                throw new IOException("Output parent is not a directory: " + path);
            }
        }
    }
}
