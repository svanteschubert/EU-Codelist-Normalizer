package org.standict.codelist.compare;

import com.helger.genericode.Genericode10CodeListMarshaller;
import com.helger.genericode.Genericode10Helper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Reads a Genericode code list into the plain code-to-values form the comparison works on.
 *
 * <p>The order of the columns and of the rows is the file's own, so a caller may rely on it.
 *
 * <p>Every column is kept, not only the code and its name: a delivery that merely rewords a description changes what
 * implementers read, and a report that only counted codes would call that delivery identical.
 */
public final class CodeListReader {
    private static final String CODE_COLUMN = "Code";

    /** One code list: its identification and its rows, in the order the file lists them. */
    public record CodeList(String shortName, String version, List<String> columns, Map<String, Map<String, String>> rows) {
        public int size() {
            return rows.size();
        }
    }

    public CodeList read(Path genericode) throws IOException {
        return read(Files.readAllBytes(genericode), genericode.toString());
    }

    public CodeList read(byte[] contents, String where) throws IOException {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler() {
                @Override public void error(SAXParseException e) throws SAXParseException { throw e; }
                @Override public void fatalError(SAXParseException e) throws SAXParseException { throw e; }
            });
            var codeList = new Genericode10CodeListMarshaller().read(builder.parse(new ByteArrayInputStream(contents)));
            if (codeList == null || codeList.getSimpleCodeList() == null || codeList.getColumnSet() == null) {
                throw new IOException("Expected a valid Genericode 1.0 SimpleCodeList: " + where);
            }
            var columns = new ArrayList<String>();
            for (var column : codeList.getColumnSet().getColumnChoice()) {
                if (column instanceof com.helger.genericode.v10.Column declared) {
                    columns.add(declared.getId());
                }
            }
            if (!columns.contains(CODE_COLUMN)) {
                throw new IOException("No " + CODE_COLUMN + " column: " + where);
            }
            var rows = new LinkedHashMap<String, Map<String, String>>();
            for (var row : codeList.getSimpleCodeList().getRow()) {
                String code = Genericode10Helper.getRowValue(row, CODE_COLUMN);
                if (code == null || code.isEmpty()) {
                    throw new IOException("Missing " + CODE_COLUMN + " value: " + where);
                }
                var values = new LinkedHashMap<String, String>();
                for (String column : columns) {
                    String value = Genericode10Helper.getRowValue(row, column);
                    if (value != null) {
                        values.put(column, value);
                    }
                }
                if (rows.put(code, java.util.Collections.unmodifiableMap(values)) != null) {
                    throw new IOException("Duplicate " + CODE_COLUMN + " " + code + ": " + where);
                }
            }
            var identification = codeList.getIdentification();
            return new CodeList(
                    identification == null ? "" : String.valueOf(identification.getShortName().getValue()),
                    identification == null || identification.getVersion() == null ? "" : identification.getVersion(),
                    List.copyOf(columns), java.util.Collections.unmodifiableMap(rows));
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Cannot read Genericode " + where + ": " + e.getMessage(), e);
        }
    }
}
