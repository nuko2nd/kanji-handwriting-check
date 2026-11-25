package kanji.handwriting.check.checker.render;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import kanji.handwriting.check.checker.Messages;
import kanji.handwriting.check.checker.Verdict;
import kanji.handwriting.check.checker.image.InkPreprocessor;
import kanji.handwriting.check.checker.reference.ReferenceShape;
import kanji.handwriting.check.checker.score.ComponentScore;
import kanji.handwriting.check.checker.score.ScoreReport;
import kanji.handwriting.check.model.Point;

/**
 * Draws the feedback image, in reference space scaled up:
 * <ul>
 *   <li>reference strokes in light grey;
 *   <li>the drawing's skeleton, tinted per assigned component from red (score 0) to green (1);
 *   <li>reference segments with no ink nearby as red dashes ("missing");
 *   <li>stray ink in orange ("extra");
 *   <li>a legend with the verdict and each component's score.
 * </ul>
 */
public final class OverlayRenderer {
    /** Output pixels per reference unit. */
    public static final int PIXELS_PER_UNIT = 6;

    private static final Color REFERENCE = new Color(0xD6, 0xD6, 0xD6);
    private static final Color MISSING = new Color(0xD3, 0x2F, 0x2F);
    private static final Color EXTRA = new Color(0xF5, 0x7C, 0x00);
    private static final Color GRID = new Color(0xEE, 0xEE, 0xEE);
    private static final int LINE_HEIGHT = 22;

    private OverlayRenderer() {}

    /**
     * Renders the overlay.
     *
     * @param shape the reference
     * @param report the scorer's output
     * @param verdict the verdict
     * @param kanjiScore the kanji-level score
     * @param feedback alignment messages to show under the legend
     * @param messages language for the legend
     * @return a new RGB image
     */
    public static BufferedImage render(ReferenceShape shape, ScoreReport report, Verdict verdict, double kanjiScore,
                                       List<String> feedback, Messages messages) {
        int size = (int) Math.round(InkPreprocessor.REFERENCE_SIZE * PIXELS_PER_UNIT);
        int legendLines = 2 + report.components().size() + feedback.size();
        BufferedImage img = new BufferedImage(size, size + legendLines * LINE_HEIGHT + 12, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, img.getWidth(), img.getHeight());

        // Cell outline and centre guides, like a practice grid.
        g.setColor(GRID);
        g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[] {6f, 6f}, 0f));
        g.drawLine(size / 2, 0, size / 2, size);
        g.drawLine(0, size / 2, size, size / 2);
        g.setStroke(new BasicStroke(2f));
        g.drawRect(1, 1, size - 2, size - 2);

        // Reference strokes.
        g.setColor(REFERENCE);
        g.setStroke(new BasicStroke(3f * PIXELS_PER_UNIT, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (ReferenceShape.Component c : shape.components()) {
            for (ReferenceShape.Stroke s : c.strokes()) {
                g.draw(path(s.path()));
            }
        }

        // Drawing skeleton, tinted by component score; stray points orange.
        double r = 0.9 * PIXELS_PER_UNIT;
        for (int i = 0; i < report.alignedSkeleton().size(); i++) {
            Point p = report.alignedSkeleton().get(i);
            g.setColor(report.skeletonStray()[i] ? EXTRA : scoreColor(report.components().get(report.skeletonComponent()[i]).score()));
            g.fill(new Ellipse2D.Double(p.x() * PIXELS_PER_UNIT - r, p.y() * PIXELS_PER_UNIT - r, 2 * r, 2 * r));
        }
        g.setColor(EXTRA);
        for (Point p : report.strayInk()) {
            g.fillRect((int) (p.x() * PIXELS_PER_UNIT), (int) (p.y() * PIXELS_PER_UNIT), 2, 2);
        }

        // Where each stroke was snapped to (snap scoring), as a thin line tinted by its score.
        ReferenceShape scored = report.scoredShape();
        if (scored != shape) {
            g.setStroke(new BasicStroke(0.35f * PIXELS_PER_UNIT, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            for (int c = 0; c < scored.components().size(); c++) {
                List<ReferenceShape.Stroke> strokes = scored.components().get(c).strokes();
                for (int s = 0; s < strokes.size(); s++) {
                    g.setColor(scoreColor(report.components().get(c).strokes().get(s).score()).darker());
                    g.draw(path(strokes.get(s).path()));
                }
            }
        }

        // Missing segments (of the snapped strokes, if snapping) as red dashes.
        g.setColor(MISSING);
        g.setStroke(new BasicStroke(0.8f * PIXELS_PER_UNIT, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f,
                new float[] {2f * PIXELS_PER_UNIT, 1.2f * PIXELS_PER_UNIT}, 0f));
        for (int c = 0; c < scored.components().size(); c++) {
            List<ReferenceShape.Stroke> strokes = scored.components().get(c).strokes();
            for (int s = 0; s < strokes.size(); s++) {
                for (List<Point> run : uncoveredRuns(strokes.get(s).samples(), report.strokeCovered()[c][s])) {
                    g.draw(path(run));
                }
            }
        }

        // Legend.
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 16));
        int y = size + LINE_HEIGHT;
        g.setColor(verdict == Verdict.PASS ? new Color(0x2E, 0x7D, 0x32) : verdict == Verdict.UNSURE ? new Color(0xB2, 0x6A, 0x00) : MISSING);
        g.drawString(String.format("%s  %s  %s %.2f", shape.kanji(), messages.verdict(verdict), messages.score(), kanjiScore),
                10, y);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
        y += LINE_HEIGHT;
        g.setColor(Color.DARK_GRAY);
        g.drawString(messages.legend(), 10, y);
        List<ComponentScore> sorted = new ArrayList<>(report.components());
        sorted.sort(Comparator.comparingDouble(ComponentScore::score));
        for (ComponentScore cs : sorted) {
            y += LINE_HEIGHT;
            g.setColor(scoreColor(cs.score()));
            g.fillRect(10, y - 12, 14, 14);
            g.setColor(Color.BLACK);
            g.drawString(messages.legendLine(messages.label(cs.label()), cs.score(), cs.coverage(), cs.precision(),
                    cs.extraMarks()), 32, y);
        }
        g.setColor(MISSING);
        for (String f : feedback) {
            y += LINE_HEIGHT;
            g.drawString(f, 10, y);
        }
        g.dispose();
        return img;
    }

    /**
     * Straight blend from red (score 0) to green (1). It deliberately avoids passing through
     * yellow/orange, so a middling score is never mistaken for orange "extra" ink.
     */
    static Color scoreColor(double score) {
        double s = Math.max(0, Math.min(1, score));
        Color bad = new Color(0xD3, 0x2F, 0x2F), good = new Color(0x2E, 0x9D, 0x32);
        return new Color(
                (int) Math.round(bad.getRed() + s * (good.getRed() - bad.getRed())),
                (int) Math.round(bad.getGreen() + s * (good.getGreen() - bad.getGreen())),
                (int) Math.round(bad.getBlue() + s * (good.getBlue() - bad.getBlue())));
    }

    private static Path2D path(List<Point> pts) {
        Path2D.Double p = new Path2D.Double();
        p.moveTo(pts.get(0).x() * PIXELS_PER_UNIT, pts.get(0).y() * PIXELS_PER_UNIT);
        for (int i = 1; i < pts.size(); i++) {
            p.lineTo(pts.get(i).x() * PIXELS_PER_UNIT, pts.get(i).y() * PIXELS_PER_UNIT);
        }
        if (pts.size() == 1) {
            p.lineTo(pts.get(0).x() * PIXELS_PER_UNIT + 0.01, pts.get(0).y() * PIXELS_PER_UNIT);
        }
        return p;
    }

    private static List<List<Point>> uncoveredRuns(List<Point> samples, boolean[] covered) {
        List<List<Point>> runs = new ArrayList<>();
        List<Point> run = null;
        for (int i = 0; i < samples.size(); i++) {
            if (!covered[i]) {
                if (run == null) {
                    run = new ArrayList<>();
                    runs.add(run);
                }
                run.add(samples.get(i));
            } else {
                run = null;
            }
        }
        return runs;
    }
}
