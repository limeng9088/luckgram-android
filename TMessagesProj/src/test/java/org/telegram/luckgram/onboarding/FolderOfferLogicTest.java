package org.telegram.luckgram.onboarding;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FolderOfferLogicTest {

    @Test
    public void slugOnlyFromAddlistLinks() {
        assertEquals("AbC_d-12xyz", FolderOfferLogic.slug("https://t.me/addlist/AbC_d-12xyz"));
        assertEquals("abcd", FolderOfferLogic.slug(" https://t.me/addlist/abcd "));
        assertNull(FolderOfferLogic.slug(null));
        assertNull(FolderOfferLogic.slug(""));
        assertNull(FolderOfferLogic.slug("t.me/addlist/abcd"));
        assertNull(FolderOfferLogic.slug("https://t.me/luckgram"));
        assertNull(FolderOfferLogic.slug("https://t.me/addlist/ab"));
        assertNull(FolderOfferLogic.slug("https://t.me/addlist/abcd?x=1"));
        assertNull(FolderOfferLogic.slug("https://telegram.me/addlist/abcd"));
    }

    @Test
    public void checkedOnlyWhenOnWithLinkAndNotSeen() {
        String link = "https://t.me/addlist/abcd";
        assertTrue(FolderOfferLogic.shouldCheck(true, link, false, 42));
        assertFalse(FolderOfferLogic.shouldCheck(false, link, false, 42));
        assertFalse(FolderOfferLogic.shouldCheck(true, "", false, 42));
        assertFalse(FolderOfferLogic.shouldCheck(true, link, true, 42));
        assertFalse(FolderOfferLogic.shouldCheck(true, link, false, 0));
    }

    @Test
    public void offeredOnlyWhenNotJoinedAndNotEmpty() {
        assertTrue(FolderOfferLogic.shouldOffer(false, 3));
        assertFalse(FolderOfferLogic.shouldOffer(true, 3));
        assertFalse(FolderOfferLogic.shouldOffer(false, 0));
    }

    @Test
    public void seenKeyPerUser() {
        assertEquals("onboarding_folder_seen_42", FolderOfferLogic.seenKey(42));
    }
}
