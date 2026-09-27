# EU-Codelist-Normalizer

Normalize the EU's downloaded Genericode code lists into consistently ordered,
formatted Genericode XML, and extract and sort EN16931 spreadsheet sheets as CSV.
Releases are grouped by version and effective date under `src/test/resources/`.
Each release contains numbered revision subdirectories, each with `extracted/`
and `normalized/` directories. Comparisons and change reports are future work.

## Separation of responsibilities

[`EU-Codelist-Downloader`](../EU-Codelist-Downloader) acquires and archives the
official artefacts. This repository reads its registry, ZIP files and workbooks locally,
without changing or downloading anything in that sibling repository.

Normalization uses Philip Helger's
[`com.helger:ph-genericode:8.1.0`](https://github.com/phax/ph-genericode/tree/ph-genericode-8.1.0)
as a released Maven dependency. Local Java classes add the EU-specific source
selection, ordering, directory layout and provenance. No locally installed fork
or changes to the upstream library are required.

Spreadsheet extraction uses [`org.apache.poi:poi-ooxml:5.5.1`](https://poi.apache.org/).
Every sheet is exported in its original row and column order, omitting completely
empty rows. A separate copy
sorts code-list rows using the same base-36 ordering as the Genericode output.

The starting point is Svante Schubert's and Philip Helger's
`Genericode10EN16931CodeListMarshallerTest` in
[`svanteschubert/ph-genericode`](https://github.com/svanteschubert/ph-genericode),
including its `2024-05-15_13/EAS.gc` output.

## Run

Use this sibling layout:

```text
GitHub/
├── EU-Codelist-Downloader/
│   └── src/main/resources/
│       ├── downloaded-files.json
│       └── downloaded-files/
│           ├── EN 16931 code list - GeneriCode/*.zip
│           └── EN 16931 code list - XLSX/*.xlsx
└── EU-Codelist-Normalizer/
```

Maven 3.6+ and Java 17+ are required. The project pins jenv to Java 27, as used for
development. The shell script resolves `JAVA_HOME` through jenv when installed.

```bash
./run-normalize.sh
```

The script works from any working directory, builds and tests the application,
then reads `../EU-Codelist-Downloader`. After building, processing can also run
directly, without Maven or network access:

```bash
java -jar target/eu-codelist-normalizer-all.jar
java -jar target/eu-codelist-normalizer-all.jar --help
```

Optional paths (relative to the normalizer repository when using the script,
or the current working directory when invoking Java directly):

```bash
./run-normalize.sh --downloader ../EU-Codelist-Downloader --output src/test/resources
```

Maven needs network access on the first build to resolve its dependencies.
The normalizer itself performs no network downloads.

## Versioned output

```text
src/test/resources/
├── normalization.json
├── 01_2019-03-15/
│   └── r01/
│       ├── extracted/xlsx/...
│       └── normalized/xlsx/...
├── ...
├── 13_2024-05-15/
│   └── r01/
│       ├── extracted/
│       │   ├── gc/
│       │   │   ├── EAS.gc
│       │   │   ├── ...
│       │   │   └── source.json
│       │   └── xlsx/
│       │       ├── EAS.csv
│       │       ├── ...
│       │       └── source.json
│       └── normalized/
│           ├── gc/...
│           └── xlsx/...
├── ...
└── 17_2026-05-15/
    ├── r01/                      # Original GC archive and v17 workbook
    │   ├── extracted/{gc,xlsx}/...
    │   └── normalized/{gc,xlsx}/...
    └── r02/                      # Replacement GC archive and v17b workbook
        ├── extracted/{gc,xlsx}/...
        └── normalized/{gc,xlsx}/...
```

Directories use `<bundle-version>_<effective-date>/r<revision>/`, with numeric
version and revision numbers padded to at least two digits. Original version
values in source metadata and revision assignments remain unchanged.
The version is the **EN16931 bundle version** from the downloader's
registry, not the independent EAS or VATEX version. The date is the registry's
effective date, which can differ from the ZIP filename or internal XML version.
Original identification metadata inside the XML remains intact. Only downloaded
EN16931 Genericode ZIPs and EN16931 XLSX bundles are processed. Releases available
only as spreadsheets have an `xlsx/` directory within each stage. The separate EAS and VATEX
workbook categories have independent version numbers and are not included;
their sheets within the EN16931 bundles are extracted.

Each revision separates original-order `extracted/` files from sorted
`normalized/` files. Each stage contains Genericode files in `gc/` and spreadsheet
CSVs in the sibling `xlsx/`, with a source manifest in each format directory.
Extracted Genericode files are byte-for-byte copies of the original ZIP entries.
All downloaded records in these two categories are included, including
superseded records. Revisions are sibling subdirectories within their release; no hash-based
`revisions/` or `variants/` directories are generated.

[`src/main/resources/release-revisions.json`](src/main/resources/release-revisions.json)
explicitly maps source SHA-256 values to a revision for each effective date,
bundle version and format. It is bundled in the executable JAR. For example:

```json
{
  "effective_date": "2026-05-15",
  "version": "17",
  "revision": 2,
  "sources": { "gc": "<source-sha256>", "xlsx": "<source-sha256>" }
}
```

The file has `format_version: 1` and an `assignments` array containing these
entries. Revision numbers are local identifiers, not official attachment upload
numbers. The mappings pair release 17's original sources as `r01` and their
replacements as `r02`. Version 6's original and "updated" workbooks are `r01` and
`r02`, respectively; no official supersession relationship is invented for them.

Explicit assignments take precedence even when only one source is downloaded.
Unmapped releases with at most one source per format default to `r01`. If any
format has multiple sources, every source in that release must be assigned.
Missing or conflicting assignments fail before publication. Add explicit mappings
for new ambiguous releases and rebuild; upload numbers, timestamps and registry
order never determine the grouping. Missing formats are not copied from another
revision, and there is no duplicate `latest` directory.

Each `source.json` records the source URL, path relative to the downloader, source
SHA-256, version/date, archive entry or sheet names, output hashes and row counts.
Source manifests also retain original filenames, release and revision directories,
revision numbers and official `superseded_by` links when present. Spreadsheet manifests
record column counts, emitted row counts and whether they are normalized.
The root `normalization.json` (format version 5) identifies all generated archive and workbook
directories and their processing policies. Manifests contain no run timestamps
or absolute local paths.

Outputs are intentionally outside `target/`, survive `mvn clean`, and can be
versioned in Git. A rerun retains byte-identical files without rewriting them.
The normalizer validates all existing manifest-tracked files before generating
output in staging. After successful publication, it removes obsolete tracked
files and their empty directories, including previous flat revision and hash-based
layouts. Unrelated files and regression fixtures are preserved. Modified or
missing tracked files and untracked destination collisions fail before
publication; the error identifies the path that needs attention.

## Normalization policy

1. Select downloaded Genericode ZIPs from `downloaded-files.json` and verify their
   stored SHA-256 before reading them.
2. Read each `.gc` with the Genericode 1.0 marshaller from `ph-genericode`.
3. Require a nonempty, unique `Code` for every row.
4. Sort alphanumeric codes by their base-36 value, following the earlier example.
   Use arbitrary precision to avoid integer overflow; break numeric ties using
   the original code string. Codes containing other characters follow, ordered
   lexically. This gives a consistent total order for mixed code formats.
5. Write UTF-8, formatted Genericode XML using the `gc` namespace prefix.

Codes remain strings: leading zeros and case are preserved. Names, remarks,
annotations, identification, column definitions and other data are retained;
descriptions are not trimmed, rewritten or translated. This is application-level
normalization, not W3C XML canonicalization (C14N).

For example, `CD` (445 in base 10) sorts before `CBB` (15959) and `CEC` (16068).
This numeric ordering applies to both normalized Genericode and spreadsheet
code lists. The extracted files retain their original order. Length alone only
determines base-36 order when codes have no leading zeros.

All archives and workbooks are processed in a temporary directory before output
publication. Missing files, hash mismatches, invalid XML/workbooks,
missing/duplicate Genericode codes and unresolved output collisions fail the run
with a nonzero exit status. Invalid input does not publish partial results.
Output inside the downloader repository is rejected.

## Spreadsheet extraction policy

1. Verify each downloaded EN16931 XLSX workbook's SHA-256 and open it read-only
   with Apache POI.
2. Export every sheet, including index, notes, hidden and empty sheets, to
   `<sheet-name>.csv`. Keep the original sheet, row and column order, headings,
   duplicate values and cell whitespace in `extracted/xlsx/`, except for wholly
   empty rows as described below.
3. Write UTF-8 without a BOM, with commas between fields and double quotes around
   **every** field, including empty fields. Escape a double quote as `""` and
   preserve embedded commas and line breaks. CSV records use LF line endings.
4. Omit rows whose formatted cells are all empty or whitespace-only, including
   leading, internal and trailing empty rows and empty rows in documentation
   sheets. Retain partially empty rows, zero values and rows containing data
   even when the code is empty. Keep column positions and pad shorter rows with
   empty quoted fields so every row in a sheet has the same column count.
   A sheet with no retained rows produces an empty file. Embedded line breaks
   within populated cells are preserved; filtering operates on cells, not lines.
5. Use POI's `DataFormatter` with a fixed US locale and CSV mode for displayed
   values, including leading-zero number formats and dates. Formula cells use
   their saved cached results without recalculation; those results may be stale
   if the source workbook was not recalculated before saving. CSV retains cell
   values, not workbook formatting, formulas, comments or images.

## Spreadsheet normalization policy

`normalized/xlsx/` contains a separate CSV for every extracted sheet, using the
same quoting and encoding. Empty-row removal happens once before writing either
stage; sorting operates on the retained rows. Extraction and spreadsheet
normalization policy identifiers both use `v2`. Between the two stages, only row
order changes:

- Find the code column in the first three header rows, recognizing `Code`,
  `Code Values`, `Alpha-2 code`, `Alphabetic Code`, `AESC`, `AES` and `2005 Code`.
  This handles Country, Currency and Unit, whose codes are not in the first
  column, and sheets such as VAT ID and Time with multiple header rows.
- Keep all rows through the code header in place. Sort subsequent rows by the
  code column using the Genericode comparator. For cross-syntax mapping tables,
  the first code column determines the order and the entire row moves together.
- Preserve every cell value, including leading zeros, whitespace and duplicates.
  Identical codes retain their original relative order. Partially populated rows
  with a blank code stay in their positions within the retained rows.
- Keep `Index`, `Main`, empty sheets and other sheets without a recognized code
  header in the extracted order, since they have no code-list rows to sort.

## Development

```bash
mvn verify
```

- `GenericodeNormalizer`: the local row-ordering and serialization policy.
- `NormalizationPipeline`: registry input, archive verification, versioned output
  and source manifests.
- `SpreadsheetExtractor`: Apache POI sheet-to-CSV extraction.
- `SpreadsheetNormalizer`: header-aware sorting by the sheet's code column.
- `ReleaseRevisions`: explicit source-to-revision assignments.
- `GeneratedOutputs`: validation, publication and cleanup of manifest-tracked files.
- `NormalizerMain`: command-line entry point.

Tests cover preservation of values, ordering, repeatability, historical revisions,
source integrity and failure handling. Spreadsheet tests cover quoted UTF-8
values, embedded delimiters/quotes/newlines, blank cells and empty-row removal, hidden and
empty sheets, number/date formats, cached formulas, source preservation and
explicit revision grouping, safe migration, code-column selection and normalization of `CD`, `CBB`,
`CEC`. A regression fixture checks compatibility with the earlier
`2024-05-15_13/EAS.gc` output. The fixed examples `13-EAS-input.gc` and
`13-EAS-expected.gc`, and the normalization changes they demonstrate, are
explained in [13-EAS-README.md](src/test/resources/13-EAS-README.md).

## License

Apache License 2.0; see [LICENSE](LICENSE) and [NOTICE](NOTICE). The downloaded
code-list contents retain their upstream notices and provenance.
