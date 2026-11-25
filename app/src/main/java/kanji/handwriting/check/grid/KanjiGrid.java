package kanji.handwriting.check.grid;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Finds the writing cells of a kanji practice sheet: a ruled grid of square cells, as in
 * {@code kanji_grid.png} (20 columns × 20 rows of cells, with a thin spacer band between rows).
 *
 * <p><b>Detection.</b> Grid lines are found from the image itself, so a sheet exported at another
 * resolution still works (it must not be rotated or warped, as a photo would be):
 * <ol>
 *   <li>A pixel is <em>marked</em> if it is not near-white (grid lines, ink, anything).
 *   <li>A row of pixels is a horizontal line if its longest unbroken run of marked pixels is at
 *       least {@link #MIN_LINE_FRACTION} of the image width. A handwritten stroke spans at most a
 *       cell or two, far short of that, and ink touching a line only lengthens the run. Adjacent
 *       line pixels (an anti-aliased line is 2–4 px wide) are merged into one line.
 *   <li>The bands between horizontal lines that are at least half as tall as the tallest are the
 *       rows of cells; the thin spacer bands between them are left out.
 *   <li>Vertical lines stop at the spacer bands, so they are not one long run. A column of pixels
 *       is a vertical line if it is at least {@link #MIN_FILL} marked inside at least
 *       {@link #MIN_ROWS} of the rows of cells: handwriting never lines up like that.
 *   <li>The gaps between neighbouring vertical lines are the columns. Rows whose height is not
 *       close to the column width (not square cells) are dropped.
 * </ol>
 *
 * <p><b>Cells</b> are returned in reading order: left to right, then top to bottom. Each cell's
 * image is the inside of its lines, shrunk by {@link #INSET_FRACTION} so no grid line remains
 * (the lines are dark enough to count as ink), padded to a square on white.
 */
public final class KanjiGrid {
    /** A horizontal line must run unbroken for at least this fraction of the image width. */
    static final double MIN_LINE_FRACTION = 0.3;
    /** A vertical line fills at least this fraction of a row of cells... */
    static final double MIN_FILL = 0.9;
    /** ...in at least this fraction of the rows. */
    static final double MIN_ROWS = 0.8;
    /** Pixels at least this bright (0–255 grey) are background. */
    static final int WHITE = 240;
    /** Each side of a cell is cropped by this fraction of the cell size, at least 3 px. */
    static final double INSET_FRACTION = 0.04;
    /** A gap between horizontal lines is a row of cells if its height is within this ratio of the column width. */
    static final double SQUARE_TOLERANCE = 0.25;

    private KanjiGrid() {}

    /**
     * One writing cell.
     *
     * @param index 0-based position in reading order
     * @param row 0-based row of cells (spacer bands are not counted)
     * @param column 0-based column, from the left
     * @param bounds the area inside the cell's lines, in sheet pixels
     */
    public record Cell(int index, int row, int column, Rectangle bounds) {}

    /**
     * Finds every cell of a sheet.
     *
     * @param sheet the sheet image
     * @return the cells in reading order
     * @throws IllegalArgumentException if no grid of square cells is found
     */
    public static List<Cell> cells(BufferedImage sheet) {
        int w = sheet.getWidth(), h = sheet.getHeight();
        boolean[][] marked = new boolean[h][w];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = sheet.getRGB(x, y);
                int a = p >>> 24 & 0xff;
                double grey = 0.299 * (p >> 16 & 0xff) + 0.587 * (p >> 8 & 0xff) + 0.114 * (p & 0xff);
                grey = 255 - (255 - grey) * a / 255.0; // transparent counts as white
                marked[y][x] = grey < WHITE;
            }
        }
        List<int[]> horizontals = merge(horizontalLines(marked));
        if (horizontals.size() < 2) {
            throw new IllegalArgumentException("No grid found: " + horizontals.size() + " horizontal lines");
        }
        List<int[]> bands = gaps(horizontals);
        int tallest = bands.stream().mapToInt(b -> b[1] - b[0]).max().orElse(0);
        List<int[]> tallBands = bands.stream().filter(b -> b[1] - b[0] >= tallest / 2.0).toList();
        List<int[]> verticals = merge(verticalLines(marked, tallBands));
        if (verticals.size() < 2) {
            throw new IllegalArgumentException("No grid found: " + verticals.size() + " vertical lines");
        }

        List<int[]> columns = gaps(verticals);
        double cellWidth = columns.stream().mapToInt(c -> c[1] - c[0]).sorted().toArray()[columns.size() / 2];
        List<int[]> rows = new ArrayList<>();
        for (int[] g : bands) {
            if (Math.abs(g[1] - g[0] - cellWidth) <= SQUARE_TOLERANCE * cellWidth) {
                rows.add(g);
            }
        }
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("No square cells found between the grid lines");
        }

        List<Cell> cells = new ArrayList<>();
        for (int r = 0; r < rows.size(); r++) {
            for (int c = 0; c < columns.size(); c++) {
                int[] col = columns.get(c), row = rows.get(r);
                int inset = (int) Math.max(3, Math.round(INSET_FRACTION * cellWidth));
                Rectangle bounds = new Rectangle(col[0] + inset, row[0] + inset,
                        col[1] - col[0] - 2 * inset, row[1] - row[0] - 2 * inset);
                cells.add(new Cell(cells.size(), r, c, bounds));
            }
        }
        return List.copyOf(cells);
    }

    /** Cells are enlarged to this many pixels across (the size of the checker's test drawings). */
    static final int CELL_SIZE = 436;
    /** A cell's ink is darkened to black only if its darkest ink is at least this dark (0–255). */
    static final int MAX_INK_GREY = 200;

    /**
     * The image of one cell, ready for the checker: its area, padded to a square on white, with
     * its contrast stretched so the darkest ink is black (a grey pen otherwise sits right at the
     * ink threshold, and its strokes break up), then enlarged smoothly to {@link #CELL_SIZE}
     * pixels (a sheet cell is only about 90 px, so a thin pen line is 1–2 px; enlarged, it is a
     * solid line with as many points as a full-size drawing).
     *
     * @param sheet the sheet image
     * @param cell a cell found by {@link #cells}
     * @return a new square image, {@link #CELL_SIZE} pixels across
     */
    public static BufferedImage image(BufferedImage sheet, Cell cell) {
        Rectangle b = cell.bounds();
        int side = Math.max(b.width, b.height);
        BufferedImage crop = new BufferedImage(side, side, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = crop.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, side, side);
        g.drawImage(sheet.getSubimage(b.x, b.y, b.width, b.height), (side - b.width) / 2, (side - b.height) / 2, null);
        g.dispose();
        stretchContrast(crop);

        BufferedImage out = new BufferedImage(CELL_SIZE, CELL_SIZE, BufferedImage.TYPE_INT_RGB);
        g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(crop, 0, 0, CELL_SIZE, CELL_SIZE, null);
        g.dispose();
        return out;
    }

    /**
     * Maps grey levels so the darkest ink (the darkest 2% of non-white pixels) becomes black and
     * white stays white. Cells with no clear ink (nothing darker than {@link #MAX_INK_GREY}) are
     * left alone, so faint specks are not turned into marks.
     */
    private static void stretchContrast(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        int[] grey = new int[w * h];
        int[] histogram = new int[256];
        int nonWhite = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = img.getRGB(x, y);
                int v = (int) Math.round(0.299 * (p >> 16 & 0xff) + 0.587 * (p >> 8 & 0xff) + 0.114 * (p & 0xff));
                grey[y * w + x] = v;
                if (v < WHITE) {
                    histogram[v]++;
                    nonWhite++;
                }
            }
        }
        if (nonWhite == 0) {
            return;
        }
        int dark = 0;
        for (int seen = 0; dark < 255 && (seen += histogram[dark]) < Math.max(1, nonWhite / 50); dark++) {
            // find the 2nd percentile of non-white grey levels
        }
        if (dark > MAX_INK_GREY || dark == 0) {
            return; // no clear ink, or already black
        }
        for (int i = 0; i < grey.length; i++) {
            int v = (int) Math.round(255.0 * Math.max(0, grey[i] - dark) / (255 - dark));
            img.setRGB(i % w, i / w, (v << 16) | (v << 8) | v);
        }
    }

    /** Pixel rows holding a long unbroken horizontal line. */
    private static List<Integer> horizontalLines(boolean[][] marked) {
        int h = marked.length, w = marked[0].length;
        int minRun = (int) (MIN_LINE_FRACTION * w);
        List<Integer> out = new ArrayList<>();
        for (int y = 0; y < h; y++) {
            int run = 0, best = 0;
            for (int x = 0; x < w && best < minRun; x++) {
                run = marked[y][x] ? run + 1 : 0;
                best = Math.max(best, run);
            }
            if (best >= minRun) {
                out.add(y);
            }
        }
        return out;
    }

    /** Pixel columns that fill most of most of the given bands. */
    private static List<Integer> verticalLines(boolean[][] marked, List<int[]> bands) {
        int w = marked[0].length;
        List<Integer> out = new ArrayList<>();
        if (bands.isEmpty()) {
            return out;
        }
        for (int x = 0; x < w; x++) {
            int filled = 0;
            for (int[] b : bands) {
                int count = 0;
                for (int y = b[0]; y < b[1]; y++) {
                    if (marked[y][x]) {
                        count++;
                    }
                }
                if (count >= MIN_FILL * (b[1] - b[0])) {
                    filled++;
                }
            }
            if (filled >= MIN_ROWS * bands.size()) {
                out.add(x);
            }
        }
        return out;
    }

    /** Merges adjacent positions into lines, as {first, last} pixel. */
    private static List<int[]> merge(List<Integer> positions) {
        List<int[]> out = new ArrayList<>();
        for (int p : positions) {
            if (!out.isEmpty() && p - out.get(out.size() - 1)[1] <= 1) {
                out.get(out.size() - 1)[1] = p;
            } else {
                out.add(new int[] {p, p});
            }
        }
        return out;
    }

    /** The open intervals between consecutive lines, as {first, end} (end exclusive). */
    private static List<int[]> gaps(List<int[]> lines) {
        List<int[]> out = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            out.add(new int[] {lines.get(i - 1)[1] + 1, lines.get(i)[0]});
        }
        return out;
    }
}
