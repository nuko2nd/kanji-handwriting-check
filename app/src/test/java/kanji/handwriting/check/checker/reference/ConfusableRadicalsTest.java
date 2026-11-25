package kanji.handwriting.check.checker.reference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import kanji.handwriting.check.checker.CheckerConfig;
import kanji.handwriting.check.service.ReferenceRepository;
import org.junit.jupiter.api.Test;

class ConfusableRadicalsTest {
    private static final ReferenceRepository REPOSITORY = new ReferenceRepository();
    private static final CheckerConfig CONFIG = CheckerConfig.defaults();

    @Test
    void lookAlikesAreSymmetricWithinAGroup() {
        assertEquals(List.of("彳"), ConfusableRadicals.lookAlikes("亻"));
        assertEquals(List.of("亻"), ConfusableRadicals.lookAlikes("彳"));
        assertTrue(ConfusableRadicals.lookAlikes("木").containsAll(List.of("扌", "牛", "禾", "米")));
        assertTrue(ConfusableRadicals.lookAlikes("言").isEmpty());
    }

    @Test
    void labelsLoseTheirNumberSuffix() {
        assertEquals("行", ConfusableRadicals.baseLabel("行#2"));
        assertEquals("亻", ConfusableRadicals.baseLabel("亻"));
    }

    @Test
    void swapReplacesOneComponentWithinItsBox() {
        ReferenceShapeLoader loader = new ReferenceShapeLoader(REPOSITORY);
        ReferenceShape shape = loader.load("住", CONFIG).orElseThrow();
        List<ConfusableRadicals.Swap> swaps = new ConfusableRadicals(loader).swaps(shape, CONFIG);
        ConfusableRadicals.Swap toGyouninben = swaps.stream().filter(s -> s.radical().equals("彳")).findFirst().orElseThrow();
        ReferenceShape.Component swapped = toGyouninben.model().components().get(toGyouninben.component());
        assertEquals("彳", swapped.label());
        assertEquals(3, swapped.strokes().size());
        // The other component is untouched.
        assertEquals(shape.components().size(), toGyouninben.model().components().size());
        assertFalse(swapped.strokes().stream().anyMatch(s -> s.samples().isEmpty()));
    }
}
