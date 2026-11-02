package org.telegram.luckgram.update;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class VersionLineTest {

    @Test
    public void ciBuildUsesRunNumberAndShortCommit() {
        assertEquals("Luckgram 13.0.3 (189) · 19e5f52",
                VersionLine.format("13.0.3", 189, 71839, "19e5f52a0b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e"));
    }

    @Test
    public void localBuildFallsBackToVersionCode() {
        assertEquals("Luckgram 13.0.3 (7183) · abcdef1", VersionLine.format("13.0.3", 0, 71839, "abcdef1"));
    }

    @Test
    public void missingCommitIsUnknown() {
        assertEquals("Luckgram 13.0.3 (5) · unknown", VersionLine.format("13.0.3", 5, 0, ""));
        assertEquals("unknown", VersionLine.shortCommit(null));
        assertEquals("unknown", VersionLine.shortCommit("unknown"));
        assertEquals("unknown", VersionLine.shortCommit("abc"));
        assertEquals("abcdef1", VersionLine.shortCommit(" ABCDEF1234 "));
    }

    @Test
    public void missingVersionName() {
        assertEquals("Luckgram unknown (1) · unknown", VersionLine.format(null, 1, 0, null));
    }
}
