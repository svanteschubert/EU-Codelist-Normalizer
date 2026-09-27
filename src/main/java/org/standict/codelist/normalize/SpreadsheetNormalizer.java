package org.standict.codelist.normalize;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Sorts code-list rows while preserving headers, cell values and rows without a code. */
public final class SpreadsheetNormalizer {
    private static final Set<String> CODE_HEADERS = Set.of(
            "code", "code values", "alpha-2 code", "alphabetic code", "aesc", "aes", "2005 code");

    public List<List<String>> normalize(String sheetName, List<List<String>> rows) {
        if (sheetName.equalsIgnoreCase("Index") || sheetName.equalsIgnoreCase("Main")) return rows;
        // EN16931 tables have up to three header rows, including cross-syntax mappings.
        for (int header = 0; header < Math.min(3, rows.size()); header++) {
            for (int column = 0; column < rows.get(header).size(); column++) {
                if (CODE_HEADERS.contains(rows.get(header).get(column).strip().toLowerCase(Locale.ROOT))) {
                    return sortRows(rows, header + 1, column);
                }
            }
        }
        // Unstructured notes and empty sheets have no code column to normalize.
        return rows;
    }

    private List<List<String>> sortRows(List<List<String>> rows, int firstDataRow, int codeColumn) {
        var sortable = new ArrayList<List<String>>();
        for (int i = firstDataRow; i < rows.size(); i++) {
            if (!rows.get(i).get(codeColumn).isBlank()) sortable.add(rows.get(i));
        }
        // List.sort is stable: duplicate codes retain their original relative order.
        sortable.sort((left, right) -> GenericodeNormalizer.compareCodes(left.get(codeColumn), right.get(codeColumn)));
        var result = new ArrayList<>(rows);
        int next = 0;
        for (int i = firstDataRow; i < rows.size(); i++) {
            if (!rows.get(i).get(codeColumn).isBlank()) result.set(i, sortable.get(next++));
        }
        return List.copyOf(result);
    }
}
