package org.telegram.luckgram.compactinput;

/**
 * Luckgram specs#102: the 「+」 panel colors, one table for both apps. Keep the values identical to iOS
 * ({@code LuckgramPlusPanelColors} in LuckgramUI). ARGB hex.
 *
 * <pre>
 * | role                | light      | dark       |
 * |---------------------|------------|------------|
 * | icon                | FF000000   | FFFFFFFF   |
 * | icon square         | 14000000   | 29FFFFFF   |
 * | title               | 66000000   | 7AFFFFFF   |
 * | page dot (current)  | FF000000   | FFFFFFFF   |
 * | page dot (other)    | 40000000   | 40FFFFFF   |
 * | dimmed cell opacity | 0.4        | 0.4        |
 * </pre>
 */
public final class LuckgramPlusPanelColors {

    public static final float DIMMED_ALPHA = 0.4f;

    private LuckgramPlusPanelColors() {
    }

    public static int icon(boolean dark) {
        return dark ? 0xFFFFFFFF : 0xFF000000;
    }

    public static int square(boolean dark) {
        return dark ? 0x29FFFFFF : 0x14000000;
    }

    public static int title(boolean dark) {
        return dark ? 0x7AFFFFFF : 0x66000000;
    }

    public static int dotCurrent(boolean dark) {
        return dark ? 0xFFFFFFFF : 0xFF000000;
    }

    public static int dotOther(boolean dark) {
        return dark ? 0x40FFFFFF : 0x40000000;
    }
}
