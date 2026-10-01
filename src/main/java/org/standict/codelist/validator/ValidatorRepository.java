package org.standict.codelist.validator;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Reads files of tagged releases out of a checkout of the validator repository, without touching its working tree.
 *
 * <p>Every file is read with {@code git show <tag>:<path>} from the object database, so the checkout may sit on any
 * branch, carry local changes or untracked files, and still yields exactly what each release published. The repository
 * is only ever read: nothing is checked out, fetched or written.
 */
public final class ValidatorRepository {
    private final GitCheckout checkout;

    public ValidatorRepository(Path root) throws IOException {
        try {
            this.checkout = new GitCheckout(root);
        } catch (IOException e) {
            throw new IOException("Not a Git checkout of the validator: " + root, e);
        }
    }

    public Path root() {
        return checkout.root();
    }

    /**
     * The repository's web address on GitHub, from its {@code origin} remote, for linking to a line of a released
     * file; empty when there is no such remote.
     */
    public java.util.Optional<String> webUrl() {
        return checkout.webUrl();
    }

    /** The commit a tag names, so a report can state, and link to, exactly the revision it read. */
    public String commit(String tag) throws IOException {
        return checkout.text("rev-parse", "--verify", "--quiet", "refs/tags/" + tag + "^{commit}");
    }

    /** The contents of one file as the tagged release published it. */
    public byte[] show(String tag, String path) throws IOException {
        return checkout.git("show", "refs/tags/" + tag + ":" + path);
    }
}
