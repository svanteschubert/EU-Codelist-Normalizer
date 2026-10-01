package org.standict.codelist.normalize;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.WorkbookFactory;

/** Extracts every sheet as CSV and can also write a separate, sorted copy. */
public final class SpreadsheetExtractor {
    /**
     * @param omittedRows the 1-based workbook rows left out as blank, so that a CSV record can be traced back to the row
     *     of the workbook it came from
     */
    public record SheetResult(String sheet, String filename, int rows, int columns, List<Integer> omittedRows) {
        /** The 1-based workbook row of the CSV record at {@code index} (0-based). */
        public int workbookRow(int index) {
            return workbookRow(omittedRows, index);
        }

        /** The 1-based workbook row of the CSV record at {@code index}, given the rows the extraction omitted. */
        public static int workbookRow(List<Integer> omittedRows, int index) {
            int row = index + 1;
            for (int omitted : omittedRows) {
                if (omitted <= row) {
                    row++;
                }
            }
            return row;
        }
    }

    public List<SheetResult> extract(Path source, Path destination) throws IOException {
        return extract(source, destination, null);
    }

    public List<SheetResult> extract(Path source, Path destination, Path normalized) throws IOException {
        var results = new ArrayList<SheetResult>();
        var formatter = new DataFormatter(Locale.US, true);
        formatter.setUseCachedValuesForFormulaCells(true);
        Files.createDirectories(destination);
        if (normalized != null) Files.createDirectories(normalized);
        // Read-only mode is essential: closing a workbook must never update the source archive.
        try (var workbook = WorkbookFactory.create(source.toFile(), null, true)) {
            var names = new HashSet<String>();
            for (var sheet : workbook) {
                String name = sheet.getSheetName() + ".csv";
                if (name.contains("/") || name.contains("\\") || name.indexOf('\0') >= 0
                        || !names.add(name.toLowerCase(Locale.ROOT))) {
                    throw new IOException("Unsafe or duplicate CSV filename: " + name);
                }
                int rows = sheet.getPhysicalNumberOfRows() == 0 ? 0 : sheet.getLastRowNum() + 1;
                int columns = 0;
                for (Row row : sheet) columns = Math.max(columns, row.getLastCellNum());
                if (rows > 0) columns = Math.max(1, columns);
                var values = new ArrayList<List<String>>();
                var omitted = new ArrayList<Integer>();
                for (int rowIndex = 0; rowIndex < rows; rowIndex++) {
                    Row row = sheet.getRow(rowIndex);
                    var cells = new ArrayList<String>();
                    for (int column = 0; column < columns; column++) {
                        var cell = row == null ? null : row.getCell(column, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                        cells.add(formatter.formatCellValue(cell));
                    }
                    if (cells.stream().anyMatch(value -> !isBlank(value))) values.add(List.copyOf(cells));
                    else omitted.add(rowIndex + 1);
                }
                writeCsv(destination.resolve(name), values);
                if (normalized != null) {
                    writeCsv(normalized.resolve(name), new SpreadsheetNormalizer().normalize(sheet.getSheetName(), values));
                }
                results.add(new SheetResult(sheet.getSheetName(), name, values.size(), columns, List.copyOf(omitted)));
            }
        } catch (RuntimeException e) {
            throw new IOException("Cannot extract workbook " + source + ": " + e.getMessage(), e);
        }
        return List.copyOf(results);
    }

    private static boolean isBlank(String value) {
        return value.codePoints().allMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c));
    }

    private static void writeCsv(Path destination, List<List<String>> rows) throws IOException {
        try (var writer = Files.newBufferedWriter(destination, StandardCharsets.UTF_8)) {
            for (var row : rows) {
                for (int column = 0; column < row.size(); column++) {
                    if (column > 0) writer.write(',');
                    writer.write('"');
                    writer.write(row.get(column).replace("\"", "\"\""));
                    writer.write('"');
                }
                writer.write('\n');
            }
        }
    }
}
