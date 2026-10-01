package org.standict.codelist.validator;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * A local Git checkout that is only ever read: its GitHub address, its default branch, and the output of read-only
 * {@code git} commands. Nothing is checked out, fetched or written.
 */
public final class GitCheckout {
    private static final Pattern GITHUB =
            Pattern.compile("^(?:https://github\\.com/|git@github\\.com:)([^/]+/[^/]+?)(?:\\.git)?/?$");

    private final Path root;

    public GitCheckout(Path root) throws IOException {
        this.root = root.toRealPath();
        if (!Files.exists(this.root.resolve(".git"))) {
            throw new IOException("Not a Git checkout: " + root);
        }
    }

    public Path root() {
        return root;
    }

    /**
     * The repository's web address on GitHub, from its {@code origin} remote; empty when there is no such remote. Both
     * {@code https://github.com/o/r(.git)} and {@code git@github.com:o/r.git} are understood.
     */
    public Optional<String> webUrl() {
        try {
            var matcher = GITHUB.matcher(text("remote", "get-url", "origin"));
            return matcher.matches() ? Optional.of("https://github.com/" + matcher.group(1)) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty(); // No origin: a report simply carries no links.
        }
    }

    /**
     * The branch GitHub shows by default, as {@code origin/HEAD} names it, else the branch checked out; empty on a
     * detached checkout without either.
     */
    public Optional<String> defaultBranch() {
        try {
            return Optional.of(text("symbolic-ref", "--short", "refs/remotes/origin/HEAD").replaceFirst("^origin/", ""));
        } catch (IOException e) {
            try {
                String branch = text("rev-parse", "--abbrev-ref", "HEAD");
                return branch.equals("HEAD") ? Optional.empty() : Optional.of(branch);
            } catch (IOException ignored) {
                return Optional.empty();
            }
        }
    }

    /** The output of a read-only {@code git} command, stripped. */
    String text(String... arguments) throws IOException {
        return new String(git(arguments), StandardCharsets.UTF_8).strip();
    }

    byte[] git(String... arguments) throws IOException {
        var command = new ArrayList<String>(List.of("git", "-C", root.toString()));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectInput(ProcessBuilder.Redirect.from(nullDevice())).start();
        // Drain both streams concurrently, so a large file on stdout cannot block git on a full stderr pipe.
        CompletableFuture<byte[]> errors = CompletableFuture.supplyAsync(() -> readQuietly(process.getErrorStream()));
        byte[] output;
        try (InputStream stream = process.getInputStream()) {
            output = stream.readAllBytes();
        }
        try {
            int exit = process.waitFor();
            if (exit != 0) {
                throw new IOException("git " + String.join(" ", arguments) + " failed in " + root + ": "
                        + new String(errors.join(), StandardCharsets.UTF_8).strip());
            }
        } catch (InterruptedException e) {
            process.destroy();
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while running git", e);
        }
        return output;
    }

    private static byte[] readQuietly(InputStream stream) {
        var buffer = new ByteArrayOutputStream();
        try (stream) {
            stream.transferTo(buffer);
        } catch (IOException ignored) {
            // The exit status reports the failure; the message is only a courtesy.
        }
        return buffer.toByteArray();
    }

    private static java.io.File nullDevice() {
        return new java.io.File(System.getProperty("os.name").startsWith("Windows") ? "NUL" : "/dev/null");
    }
}
