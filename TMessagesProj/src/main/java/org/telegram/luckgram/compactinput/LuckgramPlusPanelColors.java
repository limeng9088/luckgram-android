package org.telegram.luckgram.compactinput;

/**
 * Luckgram specs#102: the 「+」 panel colors, one table for both apps. Keep the values identical to iOS
 * ({@code LuckgramPlusPanelColors} in LuckgramUI). ARGB hex.
 *
 * <pre>
 * | role                | light      | dark       |
 * |---------------------|------------|------------|
 * | icon                | FFFFFFFF   | FFFFFFFF   |
 * | title               | A6000000   | B3FFFFFF   |
 * | page dot (current)  | FF000000   | FFFFFFFF   |
 * | page dot (other)    | 40000000   | 40FFFFFF   |
 * | dimmed cell opacity | 0.4        | 0.4        |
 * </pre>
 *
 * specs#147: every entry has its own icon square, a top-to-bottom gradient (same in light and dark):
 *
 * <pre>
 * | entry         | top      | bottom   |
 * |---------------|----------|----------|
 * | gallery       | FF5EB5F7 | FF2A82E4 |
 * | camera        | FFFF7A85 | FFE8415A |
 * | file          | FF8C9EFF | FF5667E8 |
 * | location      | FF5FD97A | FF25A84A |
 * | contact       | FFFFB55C | FFF5881F |
 * | poll          | FFFFD45C | FFF2A900 |
 * | quick replies | FF4FD6C8 | FF16A89B |
 * | voice input   | FFC18CFF | FF8E4FE8 |
 * | AI writing    | FFF58AE0 | FFB04CF0 |
 * | translate     | FF5CCBF5 | FF1E9BD6 |
 * </pre>
 */
public final class LuckgramPlusPanelColors {

    public static final float DIMMED_ALPHA = 0.4f;

    private LuckgramPlusPanelColors() {
    }

    /** Entry order: gallery, camera, file, location, contact, poll, quick replies, voice input, AI writing, translate. */
    private static final int[][] SQUARES = {
            {0xFF5EB5F7, 0xFF2A82E4},
            {0xFFFF7A85, 0xFFE8415A},
            {0xFF8C9EFF, 0xFF5667E8},
            {0xFF5FD97A, 0xFF25A84A},
            {0xFFFFB55C, 0xFFF5881F},
            {0xFFFFD45C, 0xFFF2A900},
            {0xFF4FD6C8, 0xFF16A89B},
            {0xFFC18CFF, 0xFF8E4FE8},
            {0xFFF58AE0, 0xFFB04CF0},
            {0xFF5CCBF5, 0xFF1E9BD6},
    };

    public static int icon(boolean dark) {
        return 0xFFFFFFFF;
    }

    /** Top and bottom colors of the icon square; {@code entry} is the 0-based position in the table above. */
    public static int[] square(int entry) {
        final int[] pair = SQUARES[entry >= 0 && entry < SQUARES.length ? entry : 0];
        return new int[]{pair[0], pair[1]};
    }

    public static int title(boolean dark) {
        return dark ? 0xB3FFFFFF : 0xA6000000;
    }

    public static int dotCurrent(boolean dark) {
        return dark ? 0xFFFFFFFF : 0xFF000000;
    }

    public static int dotOther(boolean dark) {
        return dark ? 0x40FFFFFF : 0x40000000;
    }
}
