package kanji.handwriting.check.model;

import java.util.List;

/**
 * The correct way to write one character, as loaded from KanjiVG by
 * {@link kanji.handwriting.check.service.ReferenceRepository}. That class's documentation describes
 * the source data and the coordinate system in detail.
 *
 * <p>It is the target that handwriting is compared against, and it drives the stroke-order animation.
 *
 * @param kanji the character, exactly one Unicode code point (possibly a surrogate pair). Despite the
 *     name, it may also be kana, a Latin letter or punctuation.
 * @param strokes all strokes in correct stroke order; non-empty and unmodifiable.
 *     {@code strokes.get(i).index() == i}.
 * @param components the character's top-level components (radicals and other parts), ordered by
 *     their first stroke, each with its sub-components. Non-empty: a character KanjiVG does not
 *     divide (一, あ, A) has a single component covering all of its strokes. Together the
 *     top-level components cover every stroke exactly once, so checking each of them checks the
 *     whole character. See {@link kanji.handwriting.check.service.ReferenceRepository} section 7.
 */
public record ReferenceKanji(String kanji, List<ReferenceStroke> strokes, List<ReferenceComponent> components) {}
