package org.standict.codelist.index;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link ChangeClaims}, over remarks as the published Index sheets write them. */
class ChangeClaimsTest {
    private static final Set<String> ICD = Set.of("0100", "0101", "0199", "0201", "0202", "0217", "0218", "0219",
            "0220", "0221", "0222", "0223", "0224", "0225", "0226", "0227", "0228", "0229", "0230", "0231");
    private static final Set<String> EAS = Set.of("0198", "0199", "0200", "9901", "AN", "AQ", "AS", "AU", "EM",
            "9902", "9904", "9905", "9917", "9921", "0130", "0203", "0204", "0217", "0221", "0225", "0230", "9955");
    private static final Set<String> COUNTRY = Set.of("AN", "CW", "SX", "BQ", "IS", "TR", "XI", "1A");

    @Test
    void readsVerbsBeforeTheirCodes() {
        var claims = ChangeClaims.parse("Yes", "Added 0198,0199,0200,9901,AN,AQ,AS,AU,EM removed 9902,9904,9905", EAS);

        assertEquals(Set.of("0198", "0199", "0200", "9901", "AN", "AQ", "AS", "AU", "EM"), claims.added());
        assertEquals(Set.of("9902", "9904", "9905"), claims.removed());
        assertEquals(ChangeClaims.Flag.YES, claims.flag());
    }

    /** Codes removed long ago are no longer in any list, yet they still show that EM is on the "Added" side. */
    @Test
    void keepsACodeBeforeAVerbWithTheClauseItContinues() {
        var claims = ChangeClaims.parse("Yes", "Added 0198,EM removed 9902,9904", Set.of("0198", "EM"));

        assertEquals(Set.of("0198", "EM"), claims.added());
        assertEquals(List.of("9902", "9904"), claims.unresolved());
    }

    @Test
    void readsVerbsAfterTheirCodes() {
        var claims = ChangeClaims.parse("Yes",
                "0201 and 0202 added, 0100 and 0101 structure corrected, 0199 name corrected.", ICD);

        assertEquals(Set.of("0201", "0202"), claims.added());
        assertEquals(Set.of("0100", "0101", "0199"), claims.reworded());
        assertTrue(claims.unresolved().isEmpty(), claims.unresolved().toString());
    }

    /** "is" is an English word here, not Iceland's IS. */
    @Test
    void readsASplitAsOneRemovedAndSeveralAdded() {
        var claims = ChangeClaims.parse("Yes", "\"AN\" is split up into CW, SX and BQ", COUNTRY);

        assertEquals(Set.of("AN"), claims.removed());
        assertEquals(Set.of("BQ", "CW", "SX"), claims.added());
    }

    /** The range's upper end dropped its leading zero, and so do the codes of the second remark. */
    @Test
    void expandsRangesAndRestoresLeadingZeros() {
        assertEquals(List.of("0221", "0222", "0223", "0224", "0225", "0226", "0227", "0228", "0229", "0230"),
                List.copyOf(ChangeClaims.parse("Yes", "Adding 0221 to 230", ICD).added()));

        var claims = ChangeClaims.parse("Yes", "Adding 217, 221, 225 and 230, deprecating 9955", EAS);
        assertEquals(Set.of("0217", "0221", "0225", "0230"), claims.added());
        assertEquals(Set.of("9955"), claims.deprecated());
    }

    /** "2017" was written for 0217; it is reported, not guessed. */
    @Test
    void keepsCodeLikeWordsTheListDoesNotHaveAsUnresolved() {
        var claims = ChangeClaims.parse("Yes", "Added 2017, 2018, 2019 and 2020", ICD);

        assertTrue(claims.added().isEmpty());
        assertEquals(List.of("2017", "2018", "2019", "2020"), claims.unresolved());
        assertEquals(List.of("VATEX-135-1"),
                ChangeClaims.parse("Yes", "Adding VATEX-135-1", Set.of("VATEX-EU-135-1")).unresolved());
    }

    @Test
    void readsCountsInsteadOfCodes() {
        var claims = ChangeClaims.parse("Yes", "Updated to Rec20r16+Rec21r11, adding 49 codes", Set.of("49", "XO1"));

        assertEquals(List.of(49), claims.counts());
        assertTrue(claims.added().isEmpty(), "the count is not the unit code 49");
        assertTrue(claims.unresolved().isEmpty(), claims.unresolved().toString());
    }

    @Test
    void readsRenamesWithoutTakingTheNewNameForACode() {
        var claims = ChangeClaims.parse("Yes", "TR name changed to Türkiye", COUNTRY);
        assertEquals(Set.of("TR"), claims.reworded());

        var named = ChangeClaims.parse("Yes", "Added Identifier scheme name for code 0199", ICD);
        assertEquals(Set.of("0199"), named.reworded(), "adding a name is not adding a code");
        assertTrue(named.added().isEmpty());
    }

    @Test
    void readsRemovalsAndAdditionsInOneSentence() {
        var claims = ChangeClaims.parse("Yes", "Removing Sierra Leone SLL, new code is SLE", Set.of("SLL", "SLE"));

        assertEquals(Set.of("SLL"), claims.removed());
        assertEquals(Set.of("SLE"), claims.added());
        assertTrue(claims.unresolved().isEmpty());
    }

    @Test
    void recognisesNewListsCaseChangesAndFlags() {
        assertTrue(ChangeClaims.parse("Yes", "New list", Set.of()).newList());
        assertFalse(ChangeClaims.parse("Fixed", "Part of list separated into new list FISCAL ID", Set.of()).newList());
        assertTrue(ChangeClaims.parse("Yes", "All codes changed from lower case to uppercase. No new ones.", Set.of())
                .caseChange());
        assertEquals(ChangeClaims.Flag.NO, ChangeClaims.parse("NO", "", Set.of()).flag());
        assertEquals(ChangeClaims.Flag.FIXED, ChangeClaims.parse("Fixed", "", Set.of()).flag());
        assertEquals(ChangeClaims.Flag.EMPTY, ChangeClaims.parse("", "", Set.of()).flag());
    }

    /** The 2019 remarks wrote VATEX codes in lower case; parentheses hold comments, not claims. */
    @Test
    void matchesVatexCaseInsensitivelyAndIgnoresParentheses() {
        var claims = ChangeClaims.parse("Yes", "Added vatex-eu-132, vatex-eu-143 (new)",
                Set.of("VATEX-EU-132", "VATEX-EU-143"));
        assertEquals(Set.of("VATEX-EU-132", "VATEX-EU-143"), claims.added());

        var updated = ChangeClaims.parse("Yes", "Added 0201. Updated 0202 (names added)", ICD);
        assertEquals(Set.of("0201"), updated.added());
        assertEquals(Set.of("0202"), updated.reworded());
    }
}
