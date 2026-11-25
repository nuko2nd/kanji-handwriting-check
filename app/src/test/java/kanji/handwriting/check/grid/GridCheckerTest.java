package kanji.handwriting.check.grid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;
import kanji.handwriting.check.checker.CheckerConfig;
import kanji.handwriting.check.checker.Verdict;
import kanji.handwriting.check.model.Point;
import kanji.handwriting.check.model.ReferenceStroke;
import kanji.handwriting.check.service.ReferenceRepository;
import org.junit.jupiter.api.Test;

class GridCheckerTest {
    private static final ReferenceRepository REPO = new ReferenceRepository();
    private static final GridChecker CHECKER = new GridChecker(REPO);
    private static final CheckerConfig CONFIG = CheckerConfig.defaults();

    // Layout like kanji_grid.png at a smaller size: square cells, a spacer band between rows.
    private static final int COLUMNS = 6, ROWS = 4, CELL = 100, SPACER = 22, MARGIN = 60;

    /** A blank sheet: grey ruled lines, as on the real sheet (dark enough to count as ink). */
    private static BufferedImage blankSheet() {
        int w = 2 * MARGIN + COLUMNS * CELL, h = 2 * MARGIN + ROWS * (CELL + SPACER) + SPACER;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.setColor(new Color(120, 120, 120));
        g.setStroke(new BasicStroke(3));
        int top = MARGIN, bottom = h - MARGIN;
        for (int c = 0; c <= COLUMNS; c++) {
            g.drawLine(MARGIN + c * CELL, top, MARGIN + c * CELL, bottom);
        }
        g.drawLine(MARGIN, top, w - MARGIN, top);
        for (int r = 0; r < ROWS; r++) {
            int y = top + SPACER + r * (CELL + SPACER);
            g.drawLine(MARGIN, y, w - MARGIN, y);
            g.drawLine(MARGIN, y + CELL, w - MARGIN, y + CELL);
        }
        g.drawLine(MARGIN, bottom, w - MARGIN, bottom);
        g.dispose();
        return img;
    }

    /** Writes a kanji's reference strokes into a cell, black, filling most of the cell. */
    private static void write(BufferedImage sheet, int index, String kanji) {
        int r = index / COLUMNS, c = index % COLUMNS;
        double x0 = MARGIN + c * CELL + 8, y0 = MARGIN + SPACER + r * (CELL + SPACER) + 8, k = (CELL - 16) / 109.0;
        Graphics2D g = sheet.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Color.BLACK);
        g.setStroke(new BasicStroke((float) (3.5 * k), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (ReferenceStroke s : REPO.get(kanji).orElseThrow().strokes()) {
            List<Point> pts = s.polyline();
            Path2D.Double p = new Path2D.Double();
            p.moveTo(x0 + pts.get(0).x() * k, y0 + pts.get(0).y() * k);
            pts.subList(1, pts.size()).forEach(q -> p.lineTo(x0 + q.x() * k, y0 + q.y() * k));
            g.draw(p);
        }
        g.dispose();
    }

    @Test
    void findsCellsInReadingOrderAndSkipsSpacerBands() {
        List<KanjiGrid.Cell> cells = KanjiGrid.cells(blankSheet());
        assertEquals(COLUMNS * ROWS, cells.size());
        for (int i = 0; i < cells.size(); i++) {
            assertEquals(i, cells.get(i).index());
            assertEquals(i / COLUMNS, cells.get(i).row());
            assertEquals(i % COLUMNS, cells.get(i).column());
        }
        // Left to right within a row, then the next row lower down.
        assertTrue(cells.get(1).bounds().x > cells.get(0).bounds().x);
        assertTrue(cells.get(COLUMNS).bounds().y > cells.get(0).bounds().y + CELL);
        // Each cell lies inside its lines, so a blank cell has no ink.
        BufferedImage cell = KanjiGrid.image(blankSheet(), cells.get(7));
        assertEquals(cell.getWidth(), cell.getHeight());
        for (int y = 0; y < cell.getHeight(); y++) {
            for (int x = 0; x < cell.getWidth(); x++) {
                assertEquals(0xffffff, cell.getRGB(x, y) & 0xffffff, "grid line left in the cell at " + x + "," + y);
            }
        }
    }

    @Test
    void handwritingTouchingLinesDoesNotAddLines() {
        BufferedImage sheet = blankSheet();
        Graphics2D g = sheet.createGraphics();
        g.setColor(Color.BLACK);
        g.setStroke(new BasicStroke(4));
        g.drawLine(MARGIN + 20, MARGIN + SPACER + 50, MARGIN + 2 * CELL + 20, MARGIN + SPACER + 50); // across a line
        g.dispose();
        assertEquals(COLUMNS * ROWS, KanjiGrid.cells(sheet).size());
    }

    @Test
    void checksOnlyTheKanjiInTheirCells() {
        BufferedImage sheet = blankSheet();
        String text = "語は未来、OK";
        write(sheet, 0, "語");
        write(sheet, 2, "未");
        write(sheet, 3, "来");
        List<GridChecker.CellResult> results = CHECKER.check(sheet, text, CONFIG);
        assertEquals(List.of("語", "未", "来"), results.stream().map(GridChecker.CellResult::kanji).toList());
        assertEquals(List.of(0, 2, 3), results.stream().map(GridChecker.CellResult::position).toList());
        for (GridChecker.CellResult r : results) {
            assertEquals(r.position(), r.cell().index());
            assertEquals(Verdict.PASS, r.result().verdict(), () -> r.kanji() + ": " + r.result().feedback());
        }
    }

    @Test
    void reportsProgressPerKanji() {
        BufferedImage sheet = blankSheet();
        write(sheet, 0, "語");
        write(sheet, 2, "未");
        List<String> updates = new java.util.ArrayList<>();
        CHECKER.check(sheet, "語の未", CONFIG, (done, total) -> updates.add(done + "/" + total));
        assertEquals(List.of("0/2", "1/2", "2/2"), updates);
    }

    @Test
    void wrongKanjiInACellIsNotPassed() {
        BufferedImage sheet = blankSheet();
        write(sheet, 0, "語");
        write(sheet, 1, "犬"); // 大 expected
        List<GridChecker.CellResult> results = CHECKER.check(sheet, "語大", CONFIG);
        assertEquals(Verdict.PASS, results.get(0).result().verdict());
        assertFalse(results.get(1).result().pass());
    }

    @Test
    void emptyCellFails() {
        List<GridChecker.CellResult> results = CHECKER.check(blankSheet(), "語", CONFIG);
        assertEquals(Verdict.FAIL, results.get(0).result().verdict());
    }

    @Test
    void textWithoutKanjiChecksNothing() {
        assertTrue(CHECKER.check(blankSheet(), "ひらがな、カタカナ。", CONFIG).isEmpty());
    }

    @Test
    void textLongerThanTheSheetIsRejected() {
        String text = "語".repeat(COLUMNS * ROWS + 1);
        assertThrows(IllegalArgumentException.class, () -> CHECKER.check(blankSheet(), text, CONFIG));
    }

    @Test
    void blankImageHasNoGrid() {
        BufferedImage img = new BufferedImage(300, 300, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 300, 300);
        g.dispose();
        assertThrows(IllegalArgumentException.class, () -> KanjiGrid.cells(img));
    }

    @Test
    void kanjiAreHanIdeographs() {
        for (String k : List.of("一", "漢", "𠮟", "豈")) {
            assertTrue(GridChecker.isKanji(k.codePointAt(0)), k);
        }
        for (String k : List.of("々", "〆", "〇", "あ", "ア", "、", "A", " ")) {
            assertFalse(GridChecker.isKanji(k.codePointAt(0)), k);
        }
    }

    /** The bundled practice sheet: 20 × 20 cells. */
    @Test
    void realSheetHas400Cells() throws Exception {
        Path sheet = Path.of("..", "kanji_grid.png");
        assumeTrue(Files.exists(sheet), "kanji_grid.png not found from " + Path.of("").toAbsolutePath());
        List<KanjiGrid.Cell> cells = KanjiGrid.cells(ImageIO.read(sheet.toFile()));
        assertEquals(400, cells.size());
        assertEquals(19, cells.get(399).row());
        assertEquals(19, cells.get(399).column());
        cells.forEach(c -> assertEquals(c.bounds().width, c.bounds().height, 3, () -> "cell " + c));
        // The thin gap rows between rows of cells (about 22 px) are not cells: consecutive rows
        // are separated by the gap plus two lines, and no cell reaches into a gap.
        int pitchX = cells.get(1).bounds().x - cells.get(0).bounds().x;
        for (int r = 1; r < 20; r++) {
            var above = cells.get((r - 1) * 20).bounds();
            var below = cells.get(r * 20).bounds();
            int between = below.y - (above.y + above.height);
            int sameRowBetween = pitchX - above.width;
            assertTrue(between > sameRowBetween + 15, () -> "row gap " + between + " vs column gap " + sameRowBetween);
        }
    }

    @Test
    void gapRowsAreNotCells() {
        // On the synthetic sheet every gap row is SPACER tall; the cells must skip all of them.
        List<KanjiGrid.Cell> cells = KanjiGrid.cells(blankSheet());
        for (KanjiGrid.Cell c : cells) {
            int rowTop = MARGIN + SPACER + c.row() * (CELL + SPACER);
            assertTrue(c.bounds().y > rowTop && c.bounds().y + c.bounds().height < rowTop + CELL, () -> "cell " + c);
        }
    }

    /** A 3 × 3 grid of cells, in reading order. */
    private static List<KanjiGrid.Cell> grid3() {
        List<KanjiGrid.Cell> cells = new java.util.ArrayList<>();
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                cells.add(new KanjiGrid.Cell(cells.size(), r, c, new java.awt.Rectangle(c * 10, r * 10, 10, 10)));
            }
        }
        return cells;
    }

    /** Where each written character of the text lands, as "row,column". */
    private static List<String> where(String text) {
        return GridChecker.layout(text, grid3()).stream().map(p -> p.cell().row() + "," + p.cell().column()).toList();
    }

    @Test
    void lineBreakStartsTheNextRow() {
        assertEquals(List.of("0,0", "0,1", "1,0", "1,1"), where("一二\n三四"));
    }

    @Test
    void lineBreakAfterAFullRowAddsNoEmptyRow() {
        assertEquals(List.of("0,0", "0,1", "0,2", "1,0"), where("一二三\n四"));
    }

    @Test
    void blankLineLeavesAnEmptyRow() {
        assertEquals(List.of("0,0", "2,0"), where("一\n\n二"));
        assertEquals(List.of("1,0"), where("\n一"));
    }

    @Test
    void windowsLineBreaksWork() {
        assertEquals(List.of("0,0", "1,0"), where("一\r\n二"));
    }

    @Test
    void positionsCountWrittenCharactersOnly() {
        List<GridChecker.Placement> placed = GridChecker.layout("一\n二", grid3());
        assertEquals(List.of(0, 1), placed.stream().map(GridChecker.Placement::position).toList());
        assertEquals(2, GridChecker.writtenCharacters("一\n二"));
    }

    @Test
    void textThatDoesNotFitIsRejectedOrCut() {
        String text = "一\n二\n三\n四";
        assertThrows(IllegalArgumentException.class, () -> GridChecker.layout(text, grid3()));
        assertEquals(3, GridChecker.layout(text, grid3(), true).size());
    }
}
