package kanji.handwriting.check.grid;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.imageio.ImageIO;
import kanji.handwriting.check.checker.CheckResult;
import kanji.handwriting.check.checker.CheckerConfig;
import kanji.handwriting.check.checker.KanjiChecker;
import kanji.handwriting.check.service.ReferenceRepository;

/**
 * Checks a filled-in kanji practice sheet (see {@link KanjiGrid}) against the text that was meant
 * to be written on it.
 *
 * <p>The text is laid out one character per cell, in reading order (left to right, then top to
 * bottom), as on Japanese manuscript paper: character {@code i} of the text belongs in cell
 * {@code i}, so the first {@code n} cells are used for a text of {@code n} characters. Only the
 * kanji are checked; every other character (kana, punctuation, Latin letters, spaces) still takes
 * its cell but is skipped.
 */
public final class GridChecker {
    private final KanjiChecker checker;
    private final ReferenceRepository repository;

    /**
     * Creates a sheet checker.
     *
     * @param repository source of KanjiVG data
     */
    public GridChecker(ReferenceRepository repository) {
        this.repository = repository;
        this.checker = new KanjiChecker(repository);
    }

    /**
     * The result for one kanji of the text.
     *
     * @param position 0-based index of the character among the written characters of the text
     *     (counting code points, not counting line breaks)
     * @param kanji the kanji that belongs in the cell
     * @param cell the cell it was written in
     * @param result the checker's result for that cell
     */
    public record CellResult(int position, String kanji, KanjiGrid.Cell cell, CheckResult result) {}

    /**
     * Checks a sheet stored as an image file.
     *
     * @param sheet the filled-in sheet (PNG or another ImageIO format)
     * @param text the characters that were meant to be written, one per cell
     * @param config checker settings
     * @return one result per kanji in the text, in text order
     * @throws IOException if the image cannot be read
     * @throws IllegalArgumentException as for {@link #check(BufferedImage, String, CheckerConfig)}
     */
    public List<CellResult> check(Path sheet, String text, CheckerConfig config) throws IOException {
        BufferedImage img = ImageIO.read(sheet.toFile());
        if (img == null) {
            throw new IOException("Not a readable image: " + sheet);
        }
        return check(img, text, config);
    }

    /**
     * Checks a sheet.
     *
     * @param sheet the filled-in sheet
     * @param text the characters that were meant to be written, one per cell
     * @param config checker settings
     * @return one result per kanji in the text, in text order
     * @throws IllegalArgumentException if no grid is found, the text has more characters than the
     *     sheet has cells, or a kanji has no KanjiVG data (checked before any cell is checked)
     */
    public List<CellResult> check(BufferedImage sheet, String text, CheckerConfig config) {
        return check(sheet, text, config, (done, total) -> { });
    }

    /** Receives progress while a sheet is checked. */
    @FunctionalInterface
    public interface Progress {
        /**
         * Called before the first kanji is checked and after each one.
         *
         * @param done kanji checked so far
         * @param total kanji to check
         */
        void update(int done, int total);
    }

    /**
     * Checks a sheet, reporting progress.
     *
     * @param sheet the filled-in sheet
     * @param text the characters that were meant to be written, one per cell
     * @param config checker settings
     * @param progress told how many kanji are done, from the calling thread
     * @return one result per kanji in the text, in text order
     * @throws IllegalArgumentException as for {@link #check(BufferedImage, String, CheckerConfig)}
     */
    public List<CellResult> check(BufferedImage sheet, String text, CheckerConfig config, Progress progress) {
        List<Placement> placements = layout(text, KanjiGrid.cells(sheet));
        List<String> missing = new ArrayList<>();
        for (Placement p : placements) {
            String ch = Character.toString(p.codePoint());
            if (isKanji(p.codePoint()) && repository.get(ch).isEmpty() && !missing.contains(ch)) {
                missing.add(ch);
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("No KanjiVG data for " + String.join(", ", missing));
        }

        List<Placement> kanji = placements.stream().filter(p -> isKanji(p.codePoint())).toList();
        int total = kanji.size();
        progress.update(0, total);
        if (total == 0) {
            return List.of();
        }
        // Cells are independent: check them on all cores, reporting each as it finishes.
        int threads = Math.min(total, Math.max(1, Runtime.getRuntime().availableProcessors()));
        ExecutorService pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "grid-checker");
            t.setDaemon(true);
            return t;
        });
        try {
            CompletionService<CellResult> done = new ExecutorCompletionService<>(pool);
            for (Placement p : kanji) {
                String ch = Character.toString(p.codePoint());
                BufferedImage image = KanjiGrid.image(sheet, p.cell());
                done.submit(() -> new CellResult(p.position(), ch, p.cell(), checker.check(image, ch, config)));
            }
            CellResult[] results = new CellResult[placements.size()];
            for (int n = 1; n <= total; n++) {
                CellResult r = done.take().get();
                results[r.position()] = r;
                progress.update(n, total);
            }
            List<CellResult> ordered = new ArrayList<>();
            for (CellResult r : results) {
                if (r != null) {
                    ordered.add(r);
                }
            }
            return List.copyOf(ordered);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while checking the sheet", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException(e.getCause());
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Where one character of the text is written.
     *
     * @param position 0-based index among the text's written characters (line breaks not counted)
     * @param codePoint the character
     * @param cell its cell
     */
    public record Placement(int position, int codePoint, KanjiGrid.Cell cell) {}

    /**
     * Places the text on the sheet's cells, left to right and top to bottom, as on 原稿用紙: each
     * character takes one cell, and a line break ends the paragraph, so the next character starts a
     * new row. A paragraph that fills its last row exactly is not followed by an extra empty row;
     * a blank line leaves one. (An indent is a full-width space, which takes a cell like any other
     * character.) Both CRLF and LF line breaks are accepted.
     *
     * @param text the text, possibly with line breaks
     * @param cells the sheet's cells, in reading order
     * @return one placement per written character, in text order
     * @throws IllegalArgumentException if the text needs more cells than the sheet has
     */
    public static List<Placement> layout(String text, List<KanjiGrid.Cell> cells) {
        return layout(text, cells, false);
    }

    /**
     * Places the text on the sheet's cells as {@link #layout(String, List)} does.
     *
     * @param text the text, possibly with line breaks
     * @param cells the sheet's cells, in reading order
     * @param allowOverflow if true, characters that do not fit are left out instead of failing
     * @return one placement per written character that fits, in text order
     * @throws IllegalArgumentException if the text needs more cells than the sheet has and
     *     {@code allowOverflow} is false
     */
    public static List<Placement> layout(String text, List<KanjiGrid.Cell> cells, boolean allowOverflow) {
        List<Placement> out = new ArrayList<>();
        int[] chars = text.replace("\r\n", "\n").replace('\r', '\n').codePoints().toArray();
        int next = 0; // index of the next free cell
        boolean rowJustFilled = false; // the last character filled the end of its row
        for (int cp : chars) {
            if (cp == '\n') {
                if (rowJustFilled) {
                    rowJustFilled = false; // the paragraph ended with its row: already on a new row
                    continue;
                }
                // Part way along a row, skip the rest of it; at the start of a row (a blank line,
                // or a break at the very start), leave that whole row empty.
                boolean midRow = next > 0 && next < cells.size() && cells.get(next).row() == cells.get(next - 1).row();
                int row = midRow ? cells.get(next - 1).row() : next < cells.size() ? cells.get(next).row() : -1;
                while (next < cells.size() && cells.get(next).row() == row) {
                    next++;
                }
                continue;
            }
            if (next >= cells.size()) {
                if (allowOverflow) {
                    break;
                }
                throw new IllegalArgumentException("The text does not fit on the sheet (" + cells.size() + " cells)");
            }
            KanjiGrid.Cell cell = cells.get(next++);
            out.add(new Placement(out.size(), cp, cell));
            rowJustFilled = next >= cells.size() || cells.get(next).row() != cell.row();
        }
        return out;
    }

    /**
     * The number of written characters in a text: every character except line breaks.
     *
     * @param text the text
     * @return its written characters
     */
    public static int writtenCharacters(String text) {
        return (int) text.codePoints().filter(cp -> cp != '\n' && cp != '\r').count();
    }

    /**
     * Whether a character is a kanji: a Han-script ideograph (CJK Unified Ideographs, their
     * extensions in the supplementary planes, and compatibility ideographs). The iteration mark
     * 々, the abbreviation 〆 and the zero 〇 are skipped like kana (KanjiVG has no 〇).
     *
     * @param codePoint a Unicode code point
     * @return true for kanji
     */
    public static boolean isKanji(int codePoint) {
        return codePoint != '〇' && Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN
                && Character.isIdeographic(codePoint);
    }
}
