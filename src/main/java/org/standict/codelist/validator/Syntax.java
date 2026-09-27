package org.standict.codelist.validator;

import java.util.Locale;

/**
 * The two mandatory syntaxes of EN 16931, and where each release of the validator keeps the code lists it enforces.
 *
 * <p>EDIFACT is left out on purpose: its artefacts were last released with 1.0.0 and carry no rule identifiers.
 */
public enum Syntax {
    UBL("ubl/schematron/codelist/EN16931-UBL-codes.sch"),
    CII("cii/schematron/codelist/EN16931-CII-codes.sch");

    private final String repositoryPath;

    Syntax(String repositoryPath) {
        this.repositoryPath = repositoryPath;
    }

    /** The Schematron file within the validator repository, the same path in every release. */
    public String repositoryPath() {
        return repositoryPath;
    }

    public String fileName() {
        return repositoryPath.substring(repositoryPath.lastIndexOf('/') + 1);
    }

    public String directory() {
        return name().toLowerCase(Locale.ROOT);
    }
}
