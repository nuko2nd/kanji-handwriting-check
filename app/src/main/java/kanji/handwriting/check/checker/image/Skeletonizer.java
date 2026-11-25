package kanji.handwriting.check.checker.image;

/**
 * Zhang–Suen thinning: repeatedly peels boundary pixels off a binary image until every stroke is
 * one pixel wide, keeping connectivity. This removes pen width from the comparison.
 */
public final class Skeletonizer {
    private Skeletonizer() {}

    /**
     * Thins a binary image.
     *
     * @param ink the mask, indexed {@code [y][x]}; not modified
     * @return a new mask of the same size holding the skeleton
     */
    public static boolean[][] skeletonize(boolean[][] ink) {
        int h = ink.length, w = h == 0 ? 0 : ink[0].length;
        // One-pixel border of background so neighbour lookups never go out of range.
        boolean[][] img = new boolean[h + 2][w + 2];
        for (int y = 0; y < h; y++) {
            System.arraycopy(ink[y], 0, img[y + 1], 1, w);
        }
        java.util.List<int[]> remove = new java.util.ArrayList<>();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int pass = 0; pass < 2; pass++) {
                remove.clear();
                for (int y = 1; y <= h; y++) {
                    for (int x = 1; x <= w; x++) {
                        if (img[y][x] && shouldRemove(img, x, y, pass)) {
                            remove.add(new int[] {x, y});
                        }
                    }
                }
                for (int[] p : remove) {
                    img[p[1]][p[0]] = false;
                }
                changed |= !remove.isEmpty();
            }
        }
        boolean[][] out = new boolean[h][w];
        for (int y = 0; y < h; y++) {
            System.arraycopy(img[y + 1], 1, out[y], 0, w);
        }
        return out;
    }

    private static boolean shouldRemove(boolean[][] img, int x, int y, int pass) {
        // Neighbours P2..P9 clockwise from north.
        boolean p2 = img[y - 1][x], p3 = img[y - 1][x + 1], p4 = img[y][x + 1], p5 = img[y + 1][x + 1];
        boolean p6 = img[y + 1][x], p7 = img[y + 1][x - 1], p8 = img[y][x - 1], p9 = img[y - 1][x - 1];
        boolean[] n = {p2, p3, p4, p5, p6, p7, p8, p9};
        int count = 0, transitions = 0;
        for (int i = 0; i < 8; i++) {
            if (n[i]) {
                count++;
            }
            if (!n[i] && n[(i + 1) % 8]) {
                transitions++;
            }
        }
        if (count < 2 || count > 6 || transitions != 1) {
            return false;
        }
        return pass == 0
                ? !(p2 && p4 && p6) && !(p4 && p6 && p8)
                : !(p2 && p4 && p8) && !(p2 && p6 && p8);
    }
}
