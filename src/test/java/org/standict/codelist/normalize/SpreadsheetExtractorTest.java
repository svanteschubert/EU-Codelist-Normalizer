package org.standict.codelist.normalize;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Locale;
import org.apache.poi.ss.usermodel.FormulaError;
import org.apache.poi.ss.usermodel.SheetVisibility;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SpreadsheetExtractorTest {
    @TempDir Path temp;

    @Test
    void omitsEmptyRowsWhilePreservingUnicodeWhitespaceAndColumnGapsAndHiddenAndEmptySheets() throws Exception {
        Path source = temp.resolve("source.xlsx");
        try (var workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("Code list");
            var row = sheet.createRow(1);
            row.createCell(0).setCellValue("0009");
            row.createCell(2).setCellValue("  Grüß, \"世界\"\r\nnext line  ");
            sheet.createRow(3).createCell(1).setCellValue("0002");
            workbook.createSheet("Hidden notes").createRow(0).createCell(0).setCellValue("Keep me");
            workbook.setSheetVisibility(1, SheetVisibility.VERY_HIDDEN);
            workbook.createSheet("Empty");
            try (var out = Files.newOutputStream(source)) { workbook.write(out); }
        }
        var before = Files.readAllBytes(source);
        Path output = temp.resolve("csv");
        var results = new SpreadsheetExtractor().extract(source, output);
        assertEquals(3, results.size());
        assertEquals(new SpreadsheetExtractor.SheetResult("Code list", "Code list.csv", 2, 3, java.util.List.of(1, 3)),
                results.get(0));
        assertEquals(2, results.get(0).workbookRow(0), "the first CSV record is workbook row 2");
        assertEquals(4, results.get(0).workbookRow(1), "the second skips the blank row 3");
        assertEquals("\"0009\",\"\",\"  Grüß, \"\"世界\"\"\r\nnext line  \"\n"
                + "\"\",\"0002\",\"\"\n", Files.readString(output.resolve("Code list.csv")));
        assertEquals("\"Keep me\"\n", Files.readString(output.resolve("Hidden notes.csv")));
        assertEquals(0, Files.size(output.resolve("Empty.csv")));
        assertArrayEquals(before, Files.readAllBytes(source));
    }

    @Test
    void filtersBlankRowsFromBothStagesIncludingNotesButRetainsPartialRowsZerosAndMultilineCells() throws Exception {
        Path source = temp.resolve("source.xlsx");
        try (var workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("Codes");
            sheet.createRow(0).createCell(0).setCellValue("\t \n");
            var header = sheet.createRow(1);
            header.createCell(0).setCellValue("Code");
            header.createCell(1).setCellValue("Name");
            var cbb = sheet.createRow(2);
            cbb.createCell(0).setCellValue("CBB");
            cbb.createCell(1).setCellValue("  original\n\nparagraph  ");
            sheet.createRow(3).createCell(1).setCellValue("\u00a0\u2003");
            sheet.createRow(4).createCell(1).setCellValue("Keep this note with an empty code");
            sheet.createRow(5).createCell(0).setCellValue("CD");
            sheet.createRow(6).createCell(0).setCellValue(0);
            sheet.createRow(7).createCell(1).setCellValue(0);
            sheet.createRow(8).createCell(0).setCellValue("");
            sheet.createRow(9);
            var notes = workbook.createSheet("Index");
            notes.createRow(0);
            notes.createRow(1).createCell(0).setCellValue("  Notes  ");
            notes.createRow(2).createCell(0).setCellValue(" ");
            workbook.createSheet("All blank").createRow(2).createCell(0).setCellValue("\t ");
            try (var out = Files.newOutputStream(source)) { workbook.write(out); }
        }
        Path extracted = temp.resolve("extracted");
        Path normalized = temp.resolve("normalized");
        var sheets = new SpreadsheetExtractor().extract(source, extracted, normalized);
        assertEquals(6, sheets.get(0).rows());
        assertEquals(1, sheets.get(1).rows());
        assertEquals(0, sheets.get(2).rows());
        assertEquals("\"Code\",\"Name\"\n\"CBB\",\"  original\n\nparagraph  \"\n"
                + "\"\",\"Keep this note with an empty code\"\n\"CD\",\"\"\n\"0\",\"\"\n\"\",\"0\"\n",
                Files.readString(extracted.resolve("Codes.csv")));
        assertEquals("\"Code\",\"Name\"\n\"0\",\"\"\n\"\",\"Keep this note with an empty code\"\n"
                + "\"CD\",\"\"\n\"CBB\",\"  original\n\nparagraph  \"\n\"\",\"0\"\n",
                Files.readString(normalized.resolve("Codes.csv")));
        for (Path stage : new Path[] {extracted, normalized}) {
            assertEquals("\"  Notes  \"\n", Files.readString(stage.resolve("Index.csv")));
            assertEquals(0, Files.size(stage.resolve("All blank.csv")));
        }
    }

    @Test
    void extractsDisplayedNumbersDatesBooleansErrorsAndCachedFormulaValuesIndependentlyOfHostLocale() throws Exception {
        Path source = temp.resolve("source.xlsx");
        try (var workbook = new XSSFWorkbook()) {
            var row = workbook.createSheet("Values").createRow(0);
            var codeStyle = workbook.createCellStyle();
            codeStyle.setDataFormat(workbook.createDataFormat().getFormat("0000"));
            var code = row.createCell(0);
            code.setCellValue(9);
            code.setCellStyle(codeStyle);
            row.createCell(1).setCellValue(12.5);
            row.createCell(2).setCellValue(true);
            row.createCell(3).setCellErrorValue(FormulaError.NA.getCode());
            var dateStyle = workbook.createCellStyle();
            dateStyle.setDataFormat(workbook.createDataFormat().getFormat("yyyy-mm-dd"));
            var date = row.createCell(4);
            date.setCellValue(LocalDateTime.of(2026, 5, 15, 0, 0));
            date.setCellStyle(dateStyle);
            row.createCell(5).setCellFormula("A1+1");
            row.createCell(6).setCellFormula("\"cached text\"");
            workbook.getCreationHelper().createFormulaEvaluator().evaluateAll();
            // Change the precedent without recalculating: extraction must use the saved result, 10.
            code.setCellValue(8);
            try (var out = Files.newOutputStream(source)) { workbook.write(out); }
        }
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            new SpreadsheetExtractor().extract(source, temp.resolve("csv"));
        } finally {
            Locale.setDefault(original);
        }
        assertEquals("\"0008\",\"12.5\",\"TRUE\",\"#N/A\",\"2026-05-15\",\"10\",\"cached text\"\n",
                Files.readString(temp.resolve("csv/Values.csv")));
    }
}
