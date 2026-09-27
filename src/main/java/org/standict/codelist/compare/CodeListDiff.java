package org.standict.codelist.compare;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.standict.codelist.normalize.GenericodeNormalizer;

/**
 * Compares two readings of one code list and says what changed, code by code.
 *
 * <p>Three kinds of change matter to an implementer, and they are reported separately because they cost very different
 * amounts of work: a code that is gone can invalidate documents already in circulation, a code that is new can only be
 * unsupported, and a code whose wording changed keeps every document valid while changing what it means.
 */
public final class CodeListDiff {
    /** What happened to one code between two deliveries. */
    public enum Change { ADDED, REMOVED, CHANGED }

    /**
     * @param column the column that changed, for {@link Change#CHANGED}; empty for added and removed codes
     */
    public record Entry(Change change, String code, String column, String before, String after) {}

    public record Result(List<Entry> entries, int added, int removed, int changed, int unchanged) {
        public boolean isEmpty() {
            return entries.isEmpty();
        }
    }

    public Result compare(CodeListReader.CodeList before, CodeListReader.CodeList after) {
        var codes = new TreeSet<String>(GenericodeNormalizer.CODE_ORDER);
        codes.addAll(before.rows().keySet());
        codes.addAll(after.rows().keySet());
        var columns = new LinkedHashSet<String>();
        columns.addAll(before.columns());
        columns.addAll(after.columns());
        var entries = new ArrayList<Entry>();
        int added = 0;
        int removed = 0;
        int changed = 0;
        int unchanged = 0;
        for (String code : codes) {
            Map<String, String> left = before.rows().get(code);
            Map<String, String> right = after.rows().get(code);
            if (left == null) {
                entries.add(new Entry(Change.ADDED, code, "", "", right.getOrDefault("Name", "")));
                added++;
            } else if (right == null) {
                entries.add(new Entry(Change.REMOVED, code, "", left.getOrDefault("Name", ""), ""));
                removed++;
            } else {
                int before_ = entries.size();
                for (String column : columns) {
                    String leftValue = left.getOrDefault(column, "");
                    String rightValue = right.getOrDefault(column, "");
                    if (!leftValue.equals(rightValue)) {
                        entries.add(new Entry(Change.CHANGED, code, column, leftValue, rightValue));
                    }
                }
                if (entries.size() == before_) {
                    unchanged++;
                } else {
                    changed++;
                }
            }
        }
        return new Result(List.copyOf(entries), added, removed, changed, unchanged);
    }
}
