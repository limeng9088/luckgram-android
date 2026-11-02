package org.telegram.luckgram.onboarding;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

public class WelcomeLogicTest {

    @Test
    public void welcomeOnlyWithoutAccounts() {
        assertTrue(WelcomeLogic.shouldShowWelcome(0));
        assertFalse(WelcomeLogic.shouldShowWelcome(1));
        assertFalse(WelcomeLogic.shouldShowWelcome(2));
    }

    @Test
    public void privacyAskedUntilAcceptedAndStepsOnce() {
        assertTrue(WelcomeLogic.shouldAskPrivacy(false));
        assertFalse(WelcomeLogic.shouldAskPrivacy(true));
        assertTrue(WelcomeLogic.shouldShowSteps(false));
        assertFalse(WelcomeLogic.shouldShowSteps(true));
    }

    @Test
    public void policyUpdateAsksLoggedInUsersOnce() {
        assertFalse(WelcomeLogic.shouldPromptUpdate(true, 0, 0));
        assertTrue(WelcomeLogic.shouldPromptUpdate(true, 2, 1));
        assertTrue(WelcomeLogic.shouldPromptUpdate(true, 1, 0));
        assertFalse(WelcomeLogic.shouldPromptUpdate(true, 2, 2));
        assertFalse(WelcomeLogic.shouldPromptUpdate(false, 2, 1));
        assertTrue(new WelcomeLogic.Texts(true).privacyUpdatedTitle().contains("已更新"));
    }

    @Test
    public void privacyUrlByLanguageUnlessConfigured() {
        assertEquals("https://luckgram.cc/privacy-policy/", WelcomeLogic.privacyUrl(null, true));
        assertEquals("https://luckgram.cc/en/privacy-policy/", WelcomeLogic.privacyUrl("", false));
        assertEquals("https://luckgram.cc/en/privacy-policy/", WelcomeLogic.privacyUrl("http://luckgram.cc/x", false));
        assertEquals("https://luckgram.cc/legal/privacy", WelcomeLogic.privacyUrl(" https://luckgram.cc/legal/privacy ", true));
    }

    @Test
    public void proStepAlwaysLastAndSubscribeOnlyWithBilling() {
        assertEquals(Arrays.asList(WelcomeLogic.Step.AI, WelcomeLogic.Step.AGENTS, WelcomeLogic.Step.SUMMARY, WelcomeLogic.Step.DISCOVER, WelcomeLogic.Step.PRO),
            WelcomeLogic.steps());
        assertTrue(WelcomeLogic.canSubscribe(WelcomeLogic.Step.PRO, true));
        assertFalse(WelcomeLogic.canSubscribe(WelcomeLogic.Step.PRO, false));
        assertFalse(WelcomeLogic.canSubscribe(WelcomeLogic.Step.AI, true));
        assertEquals("即将推出", new WelcomeLogic.Texts(true).comingSoon());
        assertEquals("Coming soon", new WelcomeLogic.Texts(false).comingSoon());
    }

    @Test
    public void progressCountsShownSteps() {
        assertEquals("1/4", WelcomeLogic.progress(0, 4));
        assertEquals("5/5", WelcomeLogic.progress(4, 5));
        assertEquals(0.25f, WelcomeLogic.progressFraction(0, 4), 0.0001f);
        assertEquals(1f, WelcomeLogic.progressFraction(3, 4), 0.0001f);
    }

    @Test
    public void stepsWriteTheirFeatureSwitches() {
        assertArrayEquals(new String[]{"translate_out", "quick_replies", "ai_reply", "ai_writing"}, WelcomeLogic.toggles(WelcomeLogic.Step.AI));
        assertArrayEquals(new String[]{"ai_agents"}, WelcomeLogic.toggles(WelcomeLogic.Step.AGENTS));
        assertEquals(0, WelcomeLogic.toggles(WelcomeLogic.Step.SUMMARY).length);
        assertEquals(0, WelcomeLogic.toggles(WelcomeLogic.Step.DISCOVER).length);
        assertTrue(WelcomeLogic.hasSecondary(WelcomeLogic.Step.AI));
        assertTrue(WelcomeLogic.hasSecondary(WelcomeLogic.Step.PRO));
        assertFalse(WelcomeLogic.hasSecondary(WelcomeLogic.Step.SUMMARY));
    }

    @Test
    public void textsInBothLanguages() {
        WelcomeLogic.Texts zh = new WelcomeLogic.Texts(true);
        WelcomeLogic.Texts en = new WelcomeLogic.Texts(false);
        assertEquals("开始使用", zh.getStarted());
        assertEquals("Get Started", en.getStarted());
        assertEquals("Welcome to", en.welcomeTo());
        assertEquals("Luckgram Privacy Policy", en.privacyTitle());
        assertEquals(3, en.privacyText().split("\n\n").length);
        assertEquals(3, zh.privacyText().split("\n\n").length);
        assertTrue(zh.privacyText().contains("非官方"));
        assertEquals("暂不开启", zh.secondary(WelcomeLogic.Step.AI));
        assertNull(en.secondary(WelcomeLogic.Step.SUMMARY));
        assertNull(en.question(WelcomeLogic.Step.DISCOVER));
        assertEquals("Subscribe · 250 ⭐ / 30 days", en.subscribe(250, 30));
        assertEquals("订阅", zh.subscribe(0, 30));
        for (WelcomeLogic.Step s : WelcomeLogic.Step.values()) {
            assertFalse(zh.title(s).isEmpty());
            assertFalse(en.body(s).isEmpty());
        }
        // Only benefits Luckgram Pro really has (S7): AI quota, device sharing, Stars.
        assertEquals(3, en.proFeatures().length);
    }
}
