/*
 * Copyright (C) 2014-2025 Philip Helger (www.helger.com)
 * philip[at]helger[dot]com
 * Copyright 2026 Svante Schubert
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Adapted from Genericode10EN16931CodeListMarshallerTest of ph-genericode
 * (https://github.com/svanteschubert/ph-genericode), the EN16931 marshalling
 * example by Svante Schubert and Philip Helger: the ordering of rows by code
 * and the Genericode output settings. Changed here: secure XML parsing, a
 * check for missing and duplicate codes, and a base-36 order without integer
 * overflow or ambiguous ties.
 */
package org.standict.codelist.normalize;

import com.helger.genericode.CGenericode;
import com.helger.genericode.Genericode10CodeListMarshaller;
import com.helger.genericode.Genericode10Helper;
import com.helger.xml.namespace.MapBasedNamespaceContext;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.util.HashSet;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

/** Local normalization policy composed with the unmodified ph-genericode library. */
public final class GenericodeNormalizer {
    public record Result(byte[] xml, int rows) {}

    public Result normalize(byte[] input) throws IOException {
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
            var document = builder.parse(new ByteArrayInputStream(input));
            var marshaller = new Genericode10CodeListMarshaller();
            var codeList = marshaller.read(document);
            if (codeList == null || codeList.getSimpleCodeList() == null) {
                throw new IOException("Expected a valid Genericode 1.0 SimpleCodeList");
            }
            var rows = codeList.getSimpleCodeList().getRow();
            var codes = new HashSet<String>();
            for (var row : rows) {
                String code = Genericode10Helper.getRowValue(row, "Code");
                if (code == null || code.isEmpty() || !codes.add(code)) {
                    throw new IOException("Missing, empty or duplicate Code: " + code);
                }
            }
            rows.sort((left, right) -> compareCodes(
                    Genericode10Helper.getRowValue(left, "Code"),
                    Genericode10Helper.getRowValue(right, "Code")));

            var namespaces = new MapBasedNamespaceContext();
            namespaces.addMapping("gc", CGenericode.GENERICODE_10_NAMESPACE_URI);
            namespaces.addDefaultNamespaceURI("");
            marshaller.setNamespaceContext(namespaces);
            marshaller.setFormattedOutput(true);
            var output = new ByteArrayOutputStream();
            if (marshaller.write(codeList, output).isFailure()) {
                throw new IOException("Cannot serialize normalized Genericode");
            }
            return new Result(output.toByteArray(), rows.size());
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Cannot normalize Genericode: " + e.getMessage(), e);
        }
    }

    /**
     * The order normalized code lists are written in, so that a report can list codes the way the files do. One
     * definition only: a second base-36 comparator elsewhere would drift from the bytes on disk.
     */
    public static final java.util.Comparator<String> CODE_ORDER = GenericodeNormalizer::compareCodes;

    /** Base-36 ordering from the original example, without integer overflow or ambiguous ties. */
    static int compareCodes(String left, String right) {
        boolean leftNumeric = left.matches("[0-9A-Za-z]+");
        boolean rightNumeric = right.matches("[0-9A-Za-z]+");
        if (leftNumeric && rightNumeric) {
            int numeric = new BigInteger(left, 36).compareTo(new BigInteger(right, 36));
            return numeric != 0 ? numeric : left.compareTo(right);
        }
        // One global order avoids the non-transitive pairwise fallback in the original example.
        if (leftNumeric != rightNumeric) return leftNumeric ? -1 : 1;
        return left.compareTo(right);
    }
}
