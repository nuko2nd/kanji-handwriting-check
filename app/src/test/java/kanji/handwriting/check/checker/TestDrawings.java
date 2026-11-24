package kanji.handwriting.check.checker;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import kanji.handwriting.check.checker.geometry.Polylines;
import kanji.handwriting.check.model.Point;
import kanji.handwriting.check.model.ReferenceStroke;
import kanji.handwriting.check.service.ReferenceRepository;

/**
 * Builds synthetic drawings from KanjiVG: the reference strokes rendered with a pen, optionally
 * with one deliberate error. Strokes are polylines in reference space (109 × 109).
 */
final class TestDrawings {
    static final int CANVAS = 436; // 4 pixels per reference unit
    static final double PEN = 3.5; // pen width in reference units (about 14 px)

    private static final ReferenceRepository REPO = new ReferenceRepository();

    private TestDrawings() {}

    /** The reference strokes of a kanji, in stroke order. */
    static List<List<Point>> strokes(String kanji) {
        return REPO.get(kanji).orElseThrow().strokes().stream().map(ReferenceStroke::polyline).toList();
    }

    /** Renders strokes (and optional dots) black on white. */
    static BufferedImage render(List<List<Point>> strokes, double penWidth, List<Point> dots, double dotDiameter) {
        double k = CANVAS / 109.0;
        BufferedImage img = new BufferedImage(CANVAS, CANVAS, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, CANVAS, CANVAS);
        g.setColor(Color.BLACK);
        g.setStroke(new BasicStroke((float) (penWidth * k), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (List<Point> s : strokes) {
            Path2D.Double p = new Path2D.Double();
            p.moveTo(s.get(0).x() * k, s.get(0).y() * k);
            for (Point q : s.subList(1, s.size())) {
                p.lineTo(q.x() * k, q.y() * k);
            }
            g.draw(p);
        }
        for (Point d : dots) {
            double r = dotDiameter * k / 2;
            g.fill(new Ellipse2D.Double(d.x() * k - r, d.y() * k - r, 2 * r, 2 * r));
        }
        g.dispose();
        return img;
    }

    static BufferedImage render(List<List<Point>> strokes) {
        return render(strokes, PEN, List.of(), 0);
    }

    // ---- Modifications (stroke numbers are 1-based, as in KanjiVG) ----

    static List<List<Point>> drop(List<List<Point>> strokes, int stroke) {
        List<List<Point>> out = new ArrayList<>(strokes);
        out.remove(stroke - 1);
        return out;
    }

    static List<List<Point>> shorten(List<List<Point>> strokes, int stroke, double keep) {
        List<List<Point>> out = new ArrayList<>(strokes);
        out.set(stroke - 1, Polylines.truncate(strokes.get(stroke - 1), keep));
        return out;
    }

    /** Continues the stroke straight past its end by {@code fraction} of its length. */
    static List<List<Point>> extend(List<List<Point>> strokes, int stroke, double fraction) {
        List<Point> s = strokes.get(stroke - 1);
        Point end = s.get(s.size() - 1);
        Point before = s.get(Math.max(0, s.size() - 4));
        double dx = end.x() - before.x(), dy = end.y() - before.y(), len = Math.hypot(dx, dy);
        double extra = Polylines.length(s) * fraction;
        List<Point> longer = new ArrayList<>(s);
        longer.add(new Point(end.x() + dx / len * extra, end.y() + dy / len * extra));
        List<List<Point>> out = new ArrayList<>(strokes);
        out.set(stroke - 1, longer);
        return out;
    }

    /** Moves the given strokes (1-based numbers) by (dx, dy). */
    static List<List<Point>> shift(List<List<Point>> strokes, Set<Integer> which, double dx, double dy) {
        List<List<Point>> out = new ArrayList<>();
        for (int i = 0; i < strokes.size(); i++) {
            out.add(which.contains(i + 1)
                    ? strokes.get(i).stream().map(p -> new Point(p.x() + dx, p.y() + dy)).toList()
                    : strokes.get(i));
        }
        return out;
    }

    /** Scales everything about the cell centre. */
    static List<List<Point>> scale(List<List<Point>> strokes, double factor) {
        double c = 54.5;
        return strokes.stream().map(s -> s.stream()
                .map(p -> new Point(c + (p.x() - c) * factor, c + (p.y() - c) * factor)).toList()).toList();
    }

    /** Wobbles every point by up to {@code amount} units, smoothly along each stroke. */
    static List<List<Point>> jitter(List<List<Point>> strokes, double amount, long seed) {
        Random rnd = new Random(seed);
        List<List<Point>> out = new ArrayList<>();
        for (List<Point> s : strokes) {
            List<Point> dense = Polylines.resample(s, 2.0);
            double ox = 0, oy = 0;
            List<Point> wobbly = new ArrayList<>();
            for (Point p : dense) {
                ox = Math.max(-amount, Math.min(amount, ox + (rnd.nextDouble() - 0.5) * amount));
                oy = Math.max(-amount, Math.min(amount, oy + (rnd.nextDouble() - 0.5) * amount));
                wobbly.add(new Point(p.x() + ox, p.y() + oy));
            }
            out.add(wobbly);
        }
        return out;
    }

    /**
     * Imitates careful but imperfect handwriting: every stroke is independently shifted, rotated
     * about its midpoint and resized by random amounts up to the given limits, then wobbled.
     *
     * @param shift largest shift per axis, in reference units
     * @param rotationDegrees largest rotation either way
     * @param resize largest relative change in size (0.2 = ±20%)
     * @param wobble jitter amount (see {@link #jitter})
     */
    static List<List<Point>> sloppy(List<List<Point>> strokes, double shift, double rotationDegrees, double resize,
                                    double wobble, long seed) {
        Random rnd = new Random(seed);
        List<List<Point>> out = new ArrayList<>();
        for (List<Point> s : strokes) {
            double dx = (rnd.nextDouble() * 2 - 1) * shift, dy = (rnd.nextDouble() * 2 - 1) * shift;
            double a = Math.toRadians((rnd.nextDouble() * 2 - 1) * rotationDegrees);
            double k = 1 + (rnd.nextDouble() * 2 - 1) * resize;
            Point mid = Polylines.truncate(s, 0.5).get(Polylines.truncate(s, 0.5).size() - 1);
            double cos = Math.cos(a), sin = Math.sin(a);
            out.add(s.stream().map(p -> {
                double x = (p.x() - mid.x()) * k, y = (p.y() - mid.y()) * k;
                return new Point(mid.x() + cos * x - sin * y + dx, mid.y() + sin * x + cos * y + dy);
            }).toList());
        }
        return jitter(out, wobble, seed * 31 + 7);
    }

    /** A point inside the cell as far as possible from every stroke, for placing a stray dot. */
    static Point emptySpot(List<List<Point>> strokes) {
        List<Point> all = new ArrayList<>();
        strokes.forEach(s -> all.addAll(Polylines.resample(s, 1.0)));
        Point best = null;
        double bestD = -1;
        for (double y = 15; y <= 94; y += 1) {
            for (double x = 15; x <= 94; x += 1) {
                double d = Double.MAX_VALUE;
                for (Point p : all) {
                    d = Math.min(d, Math.hypot(p.x() - x, p.y() - y));
                }
                if (d > bestD) {
                    bestD = d;
                    best = new Point(x, y);
                }
            }
        }
        return best;
    }
}
