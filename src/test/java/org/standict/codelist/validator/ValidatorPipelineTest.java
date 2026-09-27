package org.standict.codelist.validator;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end tests for {@link ValidatorPipeline}, against a Git repository with two tagged releases and a release tree
 * with two code-list releases.
 */
class ValidatorPipelineTest {
    @TempDir Path temp;
    private final ObjectMapper json = new ObjectMapper();

    private Path repository;
    private Path output;

    private final ValidatorCatalog catalog = ValidatorCatalog.of(List.of(
            new ValidatorCatalog.Release("validation-A", LocalDate.of(2024, 5, 15), "test"),
            new ValidatorCatalog.Release("validation-B", LocalDate.of(2024, 6, 1), "test")), Map.of(
            "UBL BR-CL-01", new ValidatorCatalog.Mapping("1001", ""),
            "CII BR-CL-01", new ValidatorCatalog.Mapping("1001", ""),
            "UBL BR-CL-06", new ValidatorCatalog.Mapping("Time", "2005 Code"),
            "CII BR-CL-06", new ValidatorCatalog.Mapping("Time", "2475 Code")));

    @BeforeEach
    void setUp() throws Exception {
        Assumptions.assumeTrue(gitAvailable(), "git is required to build the test repository");
        repository = Files.createDirectories(temp.resolve("eInvoicing-EN16931"));
        git("init", "-q");
        commitRelease("validation-A", "380 381", "3 35 432", "5 29 72");
        // B adds 389 to UBL only, and makes CII accept an unpublished time code.
        commitRelease("validation-B", "380 381 389", "3 35 432", "5 29 72 99");
        output = Files.createDirectories(temp.resolve("resources"));
        codeLists("01_2024-05-15", "380 381", "380 381");
        codeLists("02_2024-11-15", "380 381 389", "380 381 389");
    }

    @Test
    void comparesEveryDateOfEitherSideWithWhatWasInForceOnBoth() throws Exception {
        var result = new ValidatorPipeline(catalog).run(repository, output);

        var dates = result.report().dates();
        assertEquals(List.of(LocalDate.of(2024, 5, 15), LocalDate.of(2024, 6, 1), LocalDate.of(2024, 11, 15)),
                dates.stream().map(ValidatorComparison.DatePoint::effectiveDate).toList());
        assertEquals(ValidatorComparison.Trigger.BOTH, dates.get(0).trigger());
        assertEquals(ValidatorComparison.Trigger.VALIDATOR, dates.get(1).trigger());
        assertEquals(ValidatorComparison.Trigger.CODE_LISTS, dates.get(2).trigger());
        assertEquals("validation-B", dates.get(2).validator().tag());
        assertEquals("01_2024-05-15", dates.get(1).codeLists().directory());

        // On 2024-06-01 the UBL validator accepts 389, which the code lists in force only publish from November.
        var ubl = rule(result, "2024-06-01", Syntax.UBL, "BR-CL-01");
        assertEquals(List.of("389"), ubl.genericode().onlyInValidator());
        assertEquals(List.of("389"), ubl.spreadsheet().onlyInValidator());
        // From November it agrees, while CII rejects the newly published 389.
        assertTrue(rule(result, "2024-11-15", Syntax.UBL, "BR-CL-01").genericode().agrees());
        assertEquals(List.of("389"), rule(result, "2024-11-15", Syntax.CII, "BR-CL-01").genericode().onlyPublished());
    }

    /** The Time sheet has no Genericode counterpart, and CII is compared with the UNTDID 2475 column only. */
    /** With a GitHub origin, each differing code links to its line in the release's Schematron file, by tag. */
    @Test
    void linksImplementedCodesToTheirLineInTheTaggedRelease() throws Exception {
        git("remote", "add", "origin", "git@github.com:ConnectingEurope/eInvoicing-EN16931.git");

        var result = new ValidatorPipeline(catalog).run(repository, output);

        var rule = rule(result, "2024-06-01", Syntax.UBL, "BR-CL-01");
        String url = "https://github.com/ConnectingEurope/eInvoicing-EN16931/blob/validation-B/"
                + Syntax.UBL.repositoryPath();
        assertEquals(url + "#L4", rule.link("389"), "the list of BR-CL-01 is on line 4");
        String page = Files.readString(output.resolve("validator/index.html"));
        assertTrue(page.contains("href=\"" + url + "#L4\""), "implemented links to the line");
    }

    /** Without a GitHub origin the report carries no links rather than broken ones. */
    @Test
    void linksNothingWithoutAGitHubOrigin() throws Exception {
        var result = new ValidatorPipeline(catalog).run(repository, output);

        assertNull(rule(result, "2024-06-01", Syntax.UBL, "BR-CL-01").link("389"));
        assertFalse(Files.readString(output.resolve("validator/index.html")).contains("github.com/"));
    }

    @Test
    void comparesEachSyntaxWithItsOwnSpreadsheetColumn() throws Exception {
        var result = new ValidatorPipeline(catalog).run(repository, output);

        var ubl = rule(result, "2024-05-15", Syntax.UBL, "BR-CL-06");
        assertNull(ubl.genericode(), "a missing component is reported as missing, never as agreement");
        assertTrue(ubl.spreadsheet().agrees(), ubl.spreadsheet().toString());
        var cii = rule(result, "2024-06-01", Syntax.CII, "BR-CL-06");
        assertEquals(List.of("99"), cii.spreadsheet().onlyInValidator());
        assertEquals(List.of(), cii.spreadsheet().onlyPublished());
    }

    @Test
    void writesExtractedNormalizedAndStatistics() throws Exception {
        new ValidatorPipeline(catalog).run(repository, output);

        Path root = output.resolve("validator");
        assertEquals(Files.readString(repository.resolve(Syntax.UBL.repositoryPath())),
                Files.readString(root.resolve("extracted/2024-06-01_validation-B/ubl/EN16931-UBL-codes.sch")),
                "extracted files are the tagged bytes");
        assertEquals("\"Code\"\n\"380\"\n\"381\"\n\"389\"\n",
                Files.readString(root.resolve("normalized/2024-06-01_validation-B/ubl/BR-CL-01.csv")));
        var summary = Files.readAllLines(root.resolve("summary.csv"));
        assertEquals(1 + 3 * 2, summary.size(), "a header and one row per date and syntax");
        assertTrue(Files.readString(root.resolve("rules.csv")).contains("\"389\""));
        assertTrue(Files.readString(root.resolve("index.html")).contains("2024-11-15"));
        // The Index of 02 states "Added 389", which its sheet bears out; the Time sheet is not in its Index.
        var claims = Files.readAllLines(root.resolve("index-claims.csv"));
        assertEquals(3, claims.size(), "a header and the 1001 row of each release");
        assertTrue(claims.get(2).startsWith("\"02_2024-11-15/r01\",\"01_2024-05-15/r01\",\"1001\""), claims.get(2));
        assertTrue(claims.get(2).contains("\"OK\""), claims.get(2));
        assertTrue(Files.readString(root.resolve("index-releases.csv")).contains("sheets the Index does not list: Time"));
        assertTrue(Files.readString(root.resolve("business-terms.csv")).contains("\"1001\",\"BT-3\",\"BT-3\""));
        var index = json.readTree(root.resolve(ValidatorPipeline.INDEX).toFile());
        assertEquals(2, index.path("releases").size());
        assertEquals(40, index.path("releases").get(0).path("commit").asText().length());
    }

    /**
     * The shared folder must work on its own: every relative link of its page resolves to a file inside it, and the
     * manifest names each file with its hash.
     */
    @Test
    void publishesAFolderWhoseLinksAllResolve() throws Exception {
        Path folder = temp.resolve("docs/en16931-code-list-comparison");

        var result = new ValidatorPipeline(catalog).run(repository, output, folder);

        assertEquals(folder.resolve(ReportFolder.PAGE), result.page());
        String page = Files.readString(result.page());
        var links = new java.util.TreeSet<String>();
        var matcher = java.util.regex.Pattern.compile("href=\"([^\"#][^\"]*)\"").matcher(page);
        while (matcher.find()) {
            links.add(matcher.group(1));
        }
        assertTrue(links.containsAll(List.of("rules.csv", "summary.csv", "index-claims.csv", "manifest.json",
                "configuration/rule-catalog.csv", "configuration/business-terms-2017.csv")), links.toString());
        for (String link : links) {
            assertTrue(Files.isRegularFile(folder.resolve(link)), "broken link " + link);
        }
        var manifest = json.readTree(folder.resolve("manifest.json").toFile());
        assertEquals(ReportFolder.PAGE, manifest.path("page").asText());
        for (var file : manifest.path("files")) {
            byte[] contents = Files.readAllBytes(folder.resolve(file.path("path").asText()));
            assertEquals(java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(contents)), file.path("sha256").asText());
        }
        assertFalse(Files.exists(folder.resolve("validator-index.json")), "only the report, not the extraction");

        new ValidatorPipeline(catalog).run(repository, output, folder); // Replaces its own folder.
        assertTrue(Files.isRegularFile(folder.resolve(ReportFolder.PAGE)));
    }

    @Test
    void refusesToReplaceAFolderItDidNotPublish() throws Exception {
        Path folder = Files.createDirectories(temp.resolve("docs/shared"));
        Files.writeString(folder.resolve("notes.txt"), "hand-maintained");

        assertThrows(IOException.class, () -> new ValidatorPipeline(catalog).run(repository, output, folder));
        assertTrue(Files.exists(folder.resolve("notes.txt")));
    }

    /** Re-running replaces its own output with the same bytes, so the tree can be versioned in Git. */
    @Test
    void republishesItsOwnOutputRepeatably() throws Exception {
        new ValidatorPipeline(catalog).run(repository, output);
        var before = contents(output.resolve("validator"));

        new ValidatorPipeline(catalog).run(repository, output);

        assertEquals(before, contents(output.resolve("validator")));
    }

    @Test
    void refusesToReplaceADirectoryItDidNotWrite() throws Exception {
        Files.createDirectories(output.resolve("validator"));
        Files.writeString(output.resolve("validator/notes.txt"), "hand-maintained");

        var failure = assertThrows(IOException.class, () -> new ValidatorPipeline(catalog).run(repository, output));

        assertTrue(failure.getMessage().contains("Refusing"), failure.getMessage());
        assertTrue(Files.exists(output.resolve("validator/notes.txt")));
    }

    @Test
    void failsOnATagTheRepositoryDoesNotHave() {
        var unknown = ValidatorCatalog.of(List.of(
                new ValidatorCatalog.Release("validation-Z", LocalDate.of(2024, 5, 15), "test")), Map.of());

        assertThrows(IOException.class, () -> new ValidatorPipeline(unknown).run(repository, output));
        assertFalse(Files.exists(output.resolve("validator")), "nothing is published after a failure");
    }

    private static ValidatorComparison.RuleComparison rule(ValidatorPipeline.Result result, String date,
            Syntax syntax, String rule) {
        return result.report().rules().stream()
                .filter(candidate -> candidate.effectiveDate().toString().equals(date)
                        && candidate.syntax() == syntax && candidate.rule().equals(rule))
                .findFirst().orElseThrow(() -> new AssertionError(date + " " + syntax + " " + rule));
    }

    private void commitRelease(String tag, String ublTypes, String ublTimes, String ciiTimes) throws Exception {
        String ciiTypes = tag.equals("validation-B") ? "380 381" : ublTypes;
        write(repository.resolve(Syntax.UBL.repositoryPath()), schematron(ublTypes, ublTimes));
        write(repository.resolve(Syntax.CII.repositoryPath()), schematron(ciiTypes, ciiTimes));
        git("add", "-A");
        git("-c", "user.name=Test", "-c", "user.email=test@example.org", "commit", "-q", "-m", tag);
        git("tag", tag);
    }

    private static String schematron(String types, String times) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <pattern xmlns="http://purl.oclc.org/dsdl/schematron" id="Codesmodel">
                  <rule context="cbc:InvoiceTypeCode">
                    <assert id="BR-CL-01" test="contains(' %s ', concat(' ', normalize-space(.), ' '))">01</assert>
                  </rule>
                  <rule context="cbc:DescriptionCode">
                    <assert id="BR-CL-06" test="contains(' %s ', concat(' ', normalize-space(.), ' '))">06</assert>
                  </rule>
                </pattern>
                """.formatted(types, times);
    }

    private void codeLists(String release, String genericode, String spreadsheet) throws IOException {
        Path normalized = output.resolve(release).resolve("r01/normalized");
        var rows = new StringBuilder();
        for (String code : genericode.split(" ")) {
            rows.append("<Row><Value ColumnRef=\"Code\"><SimpleValue>").append(code)
                    .append("</SimpleValue></Value></Row>\n");
        }
        write(normalized.resolve("gc/1001.gc"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <gc:CodeList xmlns:gc="http://docs.oasis-open.org/codelist/ns/genericode/1.0/" xmlns="">
                  <Identification>
                    <ShortName>1001</ShortName>
                    <Version>1</Version>
                    <CanonicalUri>urn:test:1001</CanonicalUri>
                    <CanonicalVersionUri>urn:test:1001-1</CanonicalVersionUri>
                  </Identification>
                  <ColumnSet>
                    <Column Id="Code" Use="required"><ShortName>Unique code</ShortName><Data Type="string" /></Column>
                    <Key Id="CodeKey"><ShortName>Unique code</ShortName><ColumnRef Ref="Code" /></Key>
                  </ColumnSet>
                  <SimpleCodeList>
                %s  </SimpleCodeList>
                </gc:CodeList>
                """.formatted(rows));
        var csv = new StringBuilder("\"Code\",\"Name\"\n");
        for (String code : spreadsheet.split(" ")) {
            csv.append('"').append(code).append("\",\"A name\nwrapped over two lines\"\n");
        }
        write(normalized.resolve("xlsx/1001.csv"), csv.toString());
        write(output.resolve(release).resolve("r01/extracted/xlsx/Index.csv"),
                "\"Code lists\",\"Tab name\",\"Version/as published on\",\"Usage\",\"Changes\","
                        + "\"Remark on updates\",\"EN business terms where the code list is used.\"\n"
                        + "\"UNTDID 1001\",\"1001\",\"\",\"Subset\",\"Yes\",\"Added 389\",\"BT-3\"\n");
        write(normalized.resolve("xlsx/Time.csv"), """
                "UBL and UN/EDIFACT","","UN/CEFACT Cross Industry Invoice",""
                "2005 Code","Value","2475 Code","Value"
                "3","Invoice document issue date time","5","Date of invoice"
                "35","Delivery date/time, actual","29","Date of delivery"
                "432","Paid to date","72","Payment date"
                """);
    }

    private static void write(Path file, String contents) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, contents, StandardCharsets.UTF_8);
    }

    private static Map<String, String> contents(Path root) throws IOException {
        var files = new java.util.TreeMap<String, String>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                files.put(root.relativize(path).toString(), Files.readString(path));
            }
        }
        return files;
    }

    private void git(String... arguments) throws Exception {
        var command = new ArrayList<>(List.of("git", "-C", repository.toString()));
        command.addAll(List.of(arguments));
        var builder = new ProcessBuilder(command).redirectErrorStream(true);
        // Ignore the developer's own Git configuration: commit signing, hooks or templates would stall or alter the
        // test repository.
        builder.environment().put("GIT_CONFIG_GLOBAL", nullDevice());
        builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        builder.environment().put("GIT_TERMINAL_PROMPT", "0");
        Process process = builder.redirectInput(ProcessBuilder.Redirect.from(new java.io.File(nullDevice()))).start();
        String log = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), log);
    }

    private static String nullDevice() {
        return System.getProperty("os.name").startsWith("Windows") ? "NUL" : "/dev/null";
    }

    private static boolean gitAvailable() {
        try {
            return new ProcessBuilder("git", "--version").start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
