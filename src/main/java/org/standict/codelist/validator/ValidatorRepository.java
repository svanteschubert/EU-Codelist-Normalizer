package org.standict.codelist.validator;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Reads files of tagged releases out of a checkout of the validator repository, without touching its working tree.
 *
 * <p>Every file is read with {@code git show <tag>:<path>} from the object database, so the checkout may sit on any
 * branch, carry local changes or untracked files, and still yields exactly what each release published. The repository
 * is only ever read: nothing is checked out, fetched or written.
 */
public final class ValidatorRepository {
    private final Path root;

    public ValidatorRepository(Path root) throws IOException {
        this.root = root.toRealPath();
        if (!Files.exists(this.root.resolve(".git"))) {
            throw new IOException("Not a Git checkout of the validator: " + root);
        }
    }

    public Path root() {
        return root;
    }

    /** The commit a tag names, so a report can state exactly which revision it read. */
    public String commit(String tag) throws IOException {
        return new String(git("rev-parse", "--verify", "--quiet", "refs/tags/" + tag + "^{commit}"),
                StandardCharsets.UTF_8).strip();
    }

    /** The contents of one file as the tagged release published it. */
    public byte[] show(String tag, String path) throws IOException {
        return git("show", "refs/tags/" + tag + ":" + path);
    }

    private byte[] git(String... arguments) throws IOException {
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
