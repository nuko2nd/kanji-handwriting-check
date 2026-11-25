package kanji.handwriting.check.checker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import kanji.handwriting.check.checker.align.Aligner;
import kanji.handwriting.check.checker.align.Alignment;
import kanji.handwriting.check.checker.align.SimilarityTransform;
import kanji.handwriting.check.checker.geometry.PointIndex;
import kanji.handwriting.check.checker.geometry.Polylines;
import kanji.handwriting.check.checker.image.Skeletonizer;
import kanji.handwriting.check.checker.reference.ReferenceShape;
import kanji.handwriting.check.checker.reference.ReferenceShapeLoader;
import kanji.handwriting.check.model.Point;
import kanji.handwriting.check.service.ReferenceRepository;
import org.junit.jupiter.api.Test;

/** Unit tests for the individual pipeline stages. */
class StagesTest {

    @Test
    void resampleSpacingIsEven() {
        List<Point> line = List.of(new Point(0, 0), new Point(10, 0), new Point(10, 7.3));
        List<Point> pts = Polylines.resample(line, 0.5);
        assertEquals(new Point(0, 0), pts.get(0));
        assertEquals(new Point(10, 7.3), pts.get(pts.size() - 1));
        for (int i = 1; i < pts.size() - 1; i++) {
            // Each gap is 0.5 along the path; across the corner the straight-line gap is shorter.
            assertTrue(PointIndex.distance(pts.get(i - 1), pts.get(i)) <= 0.5 + 1e-9);
        }
        assertEquals(36, pts.size()); // 0, 0.5, ..., 17.0 along the path (35 points), then the end at 17.3
    }

    @Test
    void truncateKeepsFraction() {
        List<Point> line = List.of(new Point(0, 0), new Point(10, 0));
        assertEquals(7, Polylines.length(Polylines.truncate(line, 0.7)), 1e-9);
    }

    @Test
    void pointIndexMatchesBruteForce() {
        Random rnd = new Random(1);
        List<Point> pts = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            pts.add(new Point(rnd.nextDouble() * 109, rnd.nextDouble() * 109));
        }
        PointIndex index = new PointIndex(pts, 5);
        for (int q = 0; q < 200; q++) {
            Point p = new Point(rnd.nextDouble() * 109, rnd.nextDouble() * 109);
            int best = 0;
            for (int i = 1; i < pts.size(); i++) {
                if (PointIndex.distance(p, pts.get(i)) < PointIndex.distance(p, pts.get(best))) {
                    best = i;
                }
            }
            double d = PointIndex.distance(p, pts.get(best));
            assertEquals(d, PointIndex.distance(p, pts.get(index.nearest(p, 200))), 1e-12);
            assertEquals(d <= 3, index.anyWithin(p, 3));
        }
    }

    @Test
    void skeletonOfThickLineIsOnePixelWide() {
        boolean[][] img = new boolean[30][60];
        for (int y = 10; y < 20; y++) {
            for (int x = 5; x < 55; x++) {
                img[y][x] = true;
            }
        }
        boolean[][] skel = Skeletonizer.skeletonize(img);
        for (int x = 15; x < 45; x++) {
            int count = 0;
            for (int y = 0; y < 30; y++) {
                if (skel[y][x]) {
                    count++;
                    assertTrue(y >= 13 && y <= 16, "skeleton should run along the middle");
                }
            }
            assertEquals(1, count, "column " + x);
        }
    }

    @Test
    void alignerRecoversSmallKnownTransform() {
        List<Point> ref = new ArrayList<>();
        for (ReferenceShape.Component c : new ReferenceShapeLoader(new ReferenceRepository())
                .load("語", CheckerConfig.defaults()).orElseThrow().components()) {
            c.strokes().forEach(s -> ref.addAll(s.samples()));
        }
        // Distort the reference with the inverse of a known transform; the aligner should undo it.
        SimilarityTransform truth = new SimilarityTransform(1.06, Math.toRadians(2), 2.5, -1.5, 54.5, 54.5);
        List<Point> drawing = ref.stream().map(p -> invert(truth, p)).toList();
        Alignment a = Aligner.align(drawing, ref, CheckerConfig.defaults());
        assertFalse(a.anyClamped());
        assertEquals(truth.scale(), a.transform().scale(), 0.01);
        assertEquals(truth.rotationDegrees(), a.transform().rotationDegrees(), 0.3);
        // Point-to-point matching can slide a little along straight strokes, so allow half a unit.
        assertEquals(truth.tx(), a.transform().tx(), 0.5);
        assertEquals(truth.ty(), a.transform().ty(), 0.5);
    }

    private static Point invert(SimilarityTransform t, Point p) {
        double dx = p.x() - t.cx() - t.tx(), dy = p.y() - t.cy() - t.ty();
        double cos = Math.cos(-t.rotation()), sin = Math.sin(-t.rotation());
        return new Point(t.cx() + (cos * dx - sin * dy) / t.scale(), t.cy() + (sin * dx + cos * dy) / t.scale());
    }

    @Test
    void loaderCoversEveryStrokeOnce() {
        ReferenceShape shape = new ReferenceShapeLoader(new ReferenceRepository()).load("衛", CheckerConfig.defaults()).orElseThrow();
        assertEquals(List.of("行#1", "韋", "行#2"), shape.components().stream().map(ReferenceShape.Component::label).toList());
        List<Integer> all = shape.components().stream().flatMap(c -> c.strokes().stream())
                .map(ReferenceShape.Stroke::index).sorted().toList();
        assertEquals(16, all.size());
        for (int i = 0; i < 16; i++) {
            assertEquals(i, all.get(i));
        }
    }
}
