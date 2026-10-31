/*
 * Luckgram — keyword folders.
 * Licensed under GNU GPL v. 2 or later, like the rest of this Telegram-based project.
 */

package org.telegram.luckgram.keywordfolders;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/** Matching rules from specs/06-keyword-folders.md: case-insensitive, CJK substring, English whole word. */
public class KeywordMatcherTest {

    private static final List<String> INVOICE = Arrays.asList("发票", "invoice");

    @Test
    public void chineseMatchesAsSubstring() {
        assertTrue(KeywordMatcher.matches("请把电子发票发我", "发票"));
        assertTrue(KeywordMatcher.matches("发票", "发票"));
        assertTrue(KeywordMatcher.matches("增值税发票号码", "发票"));
    }

    @Test
    public void englishMatchesWholeWordsOnly() {
        assertTrue(KeywordMatcher.matches("Invoice #12 attached", "invoice"));
        assertTrue(KeywordMatcher.matches("see the invoice.", "invoice"));
        assertTrue(KeywordMatcher.matches("(invoice)", "invoice"));
        assertFalse(KeywordMatcher.matches("two invoices", "invoice"));
        assertFalse(KeywordMatcher.matches("reinvoice", "invoice"));
        assertFalse(KeywordMatcher.matches("invoice_id", "invoice"));
    }

    @Test
    public void englishNextToChineseIsAWordBoundary() {
        assertTrue(KeywordMatcher.matches("这是invoice号", "invoice"));
    }

    @Test
    public void caseInsensitive() {
        assertTrue(KeywordMatcher.matches("INVOICE", "invoice"));
        assertTrue(KeywordMatcher.matches("invoice", "INVOICE"));
    }

    @Test
    public void indexOfSkipsRejectedHits() {
        assertEquals(10, KeywordMatcher.indexOf("invoices, invoice", "invoice", 0));
        assertEquals(-1, KeywordMatcher.indexOf("invoice", "invoice", 1));
    }

    @Test
    public void firstMatchUsesFolderOrder() {
        assertEquals("发票", KeywordMatcher.firstMatch("invoice 和 发票", INVOICE));
        assertEquals("invoice", KeywordMatcher.firstMatch("Invoice only", INVOICE));
        assertNull(KeywordMatcher.firstMatch("nothing here", INVOICE));
        assertFalse(KeywordMatcher.matchesAny("", INVOICE));
        assertFalse(KeywordMatcher.matchesAny(null, INVOICE));
    }

    @Test
    public void emptyKeywordNeverMatches() {
        assertFalse(KeywordMatcher.matches("anything", ""));
        assertEquals(-1, KeywordMatcher.indexOf("anything", null, 0));
    }
}
