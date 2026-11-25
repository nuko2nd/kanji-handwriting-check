package kanji.handwriting.check.checker.geometry;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import kanji.handwriting.check.model.Point;
import org.junit.jupiter.api.Test;

class PointIndexTest {
    /** The nearest point by brute force: smallest distance within the radius, lowest index on ties. */
    private static int bruteNearest(List<Point> pts, Point q, double max) {
        int best = -1;
        double bestD2 = max * max;
        for (int i = 0; i < pts.size(); i++) {
            double dx = pts.get(i).x() - q.x(), dy = pts.get(i).y() - q.y(), d2 = dx * dx + dy * dy;
            if (d2 < bestD2 || (d2 == bestD2 && best < 0)) {
                bestD2 = d2;
                best = i;
            }
        }
        return best;
    }

    @Test
    void nearestMatchesBruteForce() {
        Random rnd = new Random(7);
        for (double cell : new double[] {1, 3, 6, 25}) {
            List<Point> pts = new ArrayList<>();
            for (int i = 0; i < 400; i++) {
                // Whole numbers too, so that ties occur.
                pts.add(i % 2 == 0 ? new Point(rnd.nextInt(60), rnd.nextInt(60)) : new Point(rnd.nextDouble() * 60, rnd.nextDouble() * 60));
            }
            PointIndex index = new PointIndex(pts, cell);
            for (int k = 0; k < 2000; k++) {
                Point q = new Point(rnd.nextDouble() * 140 - 40, rnd.nextDouble() * 140 - 40);
                double max = rnd.nextDouble() * 30;
                assertEquals(bruteNearest(pts, q, max), index.nearest(q, max), "cell " + cell + " q " + q + " max " + max);
            }
        }
    }

    @Test
    void emptyIndexFindsNothing() {
        assertEquals(-1, new PointIndex(List.of(), 4).nearest(new Point(0, 0), 100));
    }
}
