package kanji.handwriting.check.checker.align;

import java.util.List;
import kanji.handwriting.check.model.Point;

/** A mapping of points in the plane, such as a {@link SimilarityTransform} or {@link AxisTransform}. */
public interface PointMap {
    /**
     * Maps one point.
     *
     * @param p a point
     * @return the mapped point
     */
    Point apply(Point p);

    /**
     * Maps many points.
     *
     * @param points the points
     * @return the mapped points, in the same order
     */
    default List<Point> apply(List<Point> points) {
        return points.stream().map(this::apply).toList();
    }
}
