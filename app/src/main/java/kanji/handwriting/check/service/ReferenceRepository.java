package kanji.handwriting.check.service;

import kanji.handwriting.check.model.Point;
import kanji.handwriting.check.model.ReferenceKanji;
import kanji.handwriting.check.model.ReferenceStroke;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Source of truth for how a character <em>should</em> be written: its strokes, their order, their
 * direction, and their shape. Everything else in the project (registration, stroke checking, the
 * stroke-order animation, {@code GET /reference/{kanji}}) consumes the {@link ReferenceKanji} values
 * produced here, so the guarantees below are the contract the rest of the code may rely on.
 *
 * <h2>1. The KanjiVG data set</h2>
 *
 * <p>KanjiVG (<a href="https://kanjivg.tagaini.net">kanjivg.tagaini.net</a>, by Ulrich Apel) is a
 * collection of SVG files, one per character, in which every stroke of the character is a separate
 * SVG path drawn in the order and direction a person writes it. It is licensed under
 * <b>Creative Commons Attribution-Share Alike 3.0</b>: anything that displays or redistributes the
 * data (including derived stroke data sent to a client) must credit KanjiVG with a link to its site
 * and be shared under the same or a compatible license.
 *
 * <p>This project bundles only the <b>main</b> KanjiVG folder (no stylistic variants) under
 * {@code src/main/resources/kanjivg/}. It contains 6,703 files:
 * <ul>
 *   <li>6,416 CJK Unified Ideographs: 6,413 in U+4E00–U+9FFF (the everyday kanji, including the
 *       jōyō list, and many less common ones) plus 3 in Extension A (U+3400–U+4DBF);
 *   <li>188 files in U+3000–U+30FF: hiragana and katakana (including small kana and the
 *       voiced/semi-voiced forms), the iteration marks ゝゞヽヾ々, the long-vowel mark ー, the
 *       middle dot ・, and the punctuation 、。〆;
 *   <li>68 Basic Latin characters (ASCII digits, letters and some punctuation);
 *   <li>15 CJK Radicals Supplement forms (U+2E80–U+2EFF), e.g. ⺉ (the "knife" radical as written
 *       on the right side of a kanji);
 *   <li>9 CJK Compatibility Ideographs (U+F900–U+FAFF) and the fullwidth "！" (U+FF01);
 *   <li>6 characters outside the Basic Multilingual Plane, e.g. 𠮟 (U+20B9F).
 * </ul>
 * Stroke counts range from 1 to 30 (驫 and 鸞 have 30).
 *
 * <p>KanjiVG also publishes variant files such as {@code 04e00-Kaisho.svg} (alternative
 * calligraphic forms). These are not bundled and this class never looks for them.
 *
 * <h2>2. File format (what this class reads, and what it ignores)</h2>
 *
 * <p><b>File name.</b> The character's Unicode code point in lowercase hexadecimal, zero-padded to
 * at least 5 digits: あ (U+3042) is {@code 03042.svg}, 漢 (U+6F22) is {@code 06f22.svg}, 𠮟 (U+20B9F)
 * is {@code 20b9f.svg}.
 *
 * <p><b>Structure.</b> A trimmed example (あ):
 * <pre>{@code
 * <svg xmlns="http://www.w3.org/2000/svg" width="109" height="109" viewBox="0 0 109 109" ...>
 *   <g id="kvg:StrokePaths_03042" style="fill:none;stroke:#000000;stroke-width:3;...">
 *     <g id="kvg:03042" kvg:element="あ">
 *       <path id="kvg:03042-s1" d="M31.01,33c0.88,0.88,2.75,1.82,5.25,1.75c8.62-0.25,..."/>
 *       <path id="kvg:03042-s2" d="M49.76,17.62c0.88,1,1.82,3.26,1.38,5.25c..."/>
 *       <path id="kvg:03042-s3" d="M65.63,44.12c0.75,1.12,1.16,4.39,0.5,6.12c..."/>
 *     </g>
 *   </g>
 *   <g id="kvg:StrokeNumbers_03042" style="font-size:8;fill:#808080">
 *     <text transform="matrix(1 0 0 1 22.51 35)">1</text> ...
 *   </g>
 * </svg>
 * }</pre>
 *
 * <ul>
 *   <li><b>Stroke paths</b> are the {@code <path>} elements whose {@code id} ends in
 *       {@code -s<N>}. {@code N} is the 1-based stroke number in correct stroke order. In every
 *       bundled file the numbers run 1, 2, …, n with no gaps and appear in document order;
 *       this class nevertheless sorts by {@code N} rather than trusting document order.
 *   <li><b>{@code d} attribute</b>: standard SVG path data. The path starts where the brush touches
 *       the paper and ends where it lifts, so the order of points <em>is</em> the writing
 *       direction. Each stroke is a single open path that starts with {@code M} or {@code m};
 *       the bundled data otherwise only uses cubic Béziers ({@code C c S s}). Coordinates may be
 *       written with commas, spaces, or neither ({@code "0.5-1"} is two numbers).
 *   <li><b>{@code kvg:type}</b> on a stroke path: the stroke's shape class (see section 4).
 *   <li><b>Nested {@code <g>} groups</b> describe the character's components (radicals and
 *       other parts) with attributes such as {@code kvg:element} (the component's character),
 *       {@code kvg:position} (left/right/top/bottom/…), {@code kvg:radical}, {@code kvg:phon}
 *       (phonetic component), {@code kvg:part}/{@code kvg:partial} (a component split around
 *       another), and {@code kvg:variant}/{@code kvg:original}. <b>Currently ignored.</b> Only the
 *       flat, ordered stroke list is extracted.
 *   <li><b>{@code StrokeNumbers} group</b>: {@code <text>} labels showing where to print each
 *       stroke's number in a diagram. <b>Ignored.</b>
 *   <li>The {@code style} on the outer group (3-unit-wide black lines with round caps and joins)
 *       describes how KanjiVG renders strokes. It is not part of the extracted data, but a
 *       renderer that wants to match KanjiVG's look should use a 3-unit width in reference space.
 * </ul>
 *
 * <h2>3. Coordinate system ("reference space")</h2>
 *
 * <ul>
 *   <li>A 109 × 109 square (SVG {@code viewBox="0 0 109 109"}); units are arbitrary.
 *   <li>Origin at the <b>top-left</b>, <b>x increases to the right, y increases downward</b>
 *       (screen/image convention, not mathematical convention).
 *   <li>Every point of every bundled stroke lies within [0, 109] on both axes (verified by
 *       {@code ReferenceRepositoryTest.everyBundledFileLoads}). In practice glyphs occupy roughly
 *       10–100, leaving a margin, but the exact extent varies per character: a small kana or "、"
 *       occupies only part of the square, positioned where it sits in a full-width cell.
 *   <li>The square represents the whole character cell, so relative size and position within
 *       the cell are meaningful (e.g. small っ sits low and is smaller than つ).
 * </ul>
 *
 * <h2>4. Stroke types ({@code kvg:type})</h2>
 *
 * <p>The value is built from characters in Unicode's <b>CJK Strokes</b> block (U+31C0–U+31EF),
 * each naming a basic brush-stroke shape. Values seen in the bundled data, most frequent first:
 * <table>
 *   <caption>Base stroke types</caption>
 *   <tr><th>Char</th><th>Code point</th><th>Unicode name</th><th>Shape</th></tr>
 *   <tr><td>㇐</td><td>U+31D0</td><td>H</td><td>horizontal, left to right</td></tr>
 *   <tr><td>㇒</td><td>U+31D2</td><td>P</td><td>left-falling sweep (top-right to bottom-left)</td></tr>
 *   <tr><td>㇑</td><td>U+31D1</td><td>S</td><td>vertical, top to bottom</td></tr>
 *   <tr><td>㇔</td><td>U+31D4</td><td>D</td><td>dot (short, usually toward bottom-right)</td></tr>
 *   <tr><td>㇏</td><td>U+31CF</td><td>N</td><td>right-falling sweep (top-left to bottom-right)</td></tr>
 *   <tr><td>㇕</td><td>U+31D5</td><td>HZ</td><td>horizontal then turn down</td></tr>
 *   <tr><td>㇀</td><td>U+31C0</td><td>T</td><td>rising stroke (bottom-left to top-right)</td></tr>
 *   <tr><td>㇇</td><td>U+31C7</td><td>HP</td><td>horizontal then left-falling</td></tr>
 *   <tr><td>㇆</td><td>U+31C6</td><td>HZG</td><td>horizontal, turn down, end in a hook</td></tr>
 *   <tr><td>㇜</td><td>U+31DC</td><td>PZ</td><td>left-falling then turn right</td></tr>
 *   <tr><td>㇚</td><td>U+31DA</td><td>SG</td><td>vertical ending in a hook</td></tr>
 *   <tr><td>㇟</td><td>U+31DF</td><td>SWG</td><td>vertical, bend right, end in an upward hook</td></tr>
 *   <tr><td>㇖</td><td>U+31D6</td><td>HG</td><td>horizontal ending in a hook</td></tr>
 *   <tr><td>㇁</td><td>U+31C1</td><td>WG</td><td>curved stroke ending in a hook</td></tr>
 *   <tr><td>㇛</td><td>U+31DB</td><td>PD</td><td>left-falling then dot</td></tr>
 *   <tr><td>㇙</td><td>U+31D9</td><td>ST</td><td>vertical then rising</td></tr>
 *   <tr><td>㇂</td><td>U+31C2</td><td>XG</td><td>slanted (bottom-right) curve ending in a hook</td></tr>
 *   <tr><td>㇄</td><td>U+31C4</td><td>SW</td><td>vertical then bend right</td></tr>
 *   <tr><td>㇓</td><td>U+31D3</td><td>SP</td><td>vertical then left-falling</td></tr>
 *   <tr><td>㇃</td><td>U+31C3</td><td>BXG</td><td>flattened slanted hook</td></tr>
 *   <tr><td>㇋</td><td>U+31CB</td><td>HZZP</td><td>horizontal, turn, turn, left-falling</td></tr>
 *   <tr><td>㇉</td><td>U+31C9</td><td>SZWG</td><td>vertical, turn, bend, hook</td></tr>
 *   <tr><td>㇗</td><td>U+31D7</td><td>SZ</td><td>vertical then turn right</td></tr>
 *   <tr><td>㇈</td><td>U+31C8</td><td>HZWG</td><td>horizontal, turn, bend, hook</td></tr>
 *   <tr><td>㇞</td><td>U+31DE</td><td>SZZ</td><td>vertical, turn, turn</td></tr>
 *   <tr><td>㇡</td><td>U+31E1</td><td>HZZZG</td><td>horizontal, three turns, hook</td></tr>
 *   <tr><td>㇅</td><td>U+31C5</td><td>HZZ</td><td>horizontal, turn, turn</td></tr>
 * </table>
 * In the Unicode names, H = horizontal, S = vertical, P = left-falling, N = right-falling,
 * D = dot, T = rising, Z = turn, W = bend/curve, G = hook, X = slanted, B = flat.
 *
 * <p>Modifiers on top of the base character:
 * <ul>
 *   <li><b>Lowercase suffix</b>, e.g. {@code ㇐a}, {@code ㇑a}, {@code ㇕b}: a stroke-type refinement
 *       designed by KanjiVG's author but never formally documented (see KanjiVG GitHub issue 324).
 *       The letters appear to describe how the <em>end</em> of the stroke meets another stroke:
 *       <table>
 *         <caption>Stroke-type suffixes</caption>
 *         <tr><th>Suffix</th><th>Meaning</th><th>Occurrences</th></tr>
 *         <tr><td>{@code a}</td><td>the end touches the middle part of another stroke</td>
 *             <td>~15,000</td></tr>
 *         <tr><td>{@code b}</td><td>the end touches the end of another stroke</td>
 *             <td>~6,400</td></tr>
 *         <tr><td>{@code c}</td><td>unknown</td><td>~560</td></tr>
 *         <tr><td>{@code v}</td><td>unknown (only on ㇆v)</td><td>10</td></tr>
 *       </table>
 *       The suffix does not change the stroke's basic shape: the base character is the shape class.
 *       A stroke without a suffix may still touch other strokes, so a missing suffix does not mean
 *       "free-standing". Because the meanings are inferred rather than specified, treat suffixes as
 *       hints (for example, as a weak signal that a stroke ending should meet another stroke) and
 *       never as hard rules.
 *   <li><b>Slash</b>, e.g. {@code ㇔/㇏} or {@code ㇆/㇚}: the stroke is ambiguous between the listed
 *       types (it depends on the font or handwriting style). Either should be accepted.
 * </ul>
 *
 * <p>Known data quirks, passed through unchanged: 11 strokes have type {@code "６"} (a fullwidth
 * digit six; e.g. in 勵, 寓) and one has {@code "一"} (the kanji "one" instead of ㇐). Strokes of
 * non-kanji characters (kana, Latin letters, punctuation, ！) have no {@code kvg:type} at all;
 * every stroke of every CJK ideograph and radical file has one.
 *
 * <h2>5. Conversion to polylines</h2>
 *
 * <p>Each stroke's path data is flattened into a {@link ReferenceStroke#polyline()}:
 * <ul>
 *   <li>The first point is the {@code M}/{@code m} start point, i.e. where the brush lands.
 *       The last point is the path's end point, i.e. where the brush lifts. Endpoints are exact
 *       (no approximation error).
 *   <li>Each line command ({@code L l H h V v}, or an implicit line after {@code M}) adds its end
 *       point. Each Bézier segment ({@code C c S s Q q T t}) adds {@value #CURVE_SEGMENTS} points,
 *       sampled at <b>equal steps of the curve parameter t</b>, not at equal distances along the
 *       curve. Point spacing is therefore uneven (tighter where the curve is slow, e.g. near a
 *       control point, and wider elsewhere). Consumers that need uniform spacing or a fixed point
 *       count must resample.
 *   <li>A stroke made of k curve segments has 1 + 8k points; every bundled stroke has at least 2.
 *       Consecutive points may coincide when the source has zero-length segments.
 *   <li>{@code Z}/{@code z} appends the subpath's start point (not used by bundled data).
 *       Elliptical arcs ({@code A a}) are rejected (not used by bundled data).
 * </ul>
 *
 * <h2>6. Behavior</h2>
 *
 * <ul>
 *   <li><b>No caching yet.</b> Every {@link #get} call reads and parses the file again
 *       (about a millisecond per character).
 *   <li><b>Thread-safe and stateless.</b> One instance can be shared freely.
 *   <li><b>No network access.</b> KanjiVG files reference an external DTD URL. The parser uses
 *       the inline DTD (which declares the {@code kvg:*} attributes) and never fetches the
 *       external one.
 *   <li><b>Returned values are immutable.</b> {@link ReferenceKanji} and {@link ReferenceStroke} are
 *       records holding unmodifiable lists, so they compare by value and can be cached or shared.
 * </ul>
 */
public class ReferenceRepository {
    /** Points added per Bézier segment (sampled at t = 1/8, 2/8, …, 1). See class docs, section 5. */
    static final int CURVE_SEGMENTS = 8;

    /** Stroke path ids look like "kvg:03042-s1"; group 1 is the 1-based stroke number. */
    private static final Pattern STROKE_ID = Pattern.compile("-s(\\d+)$");
    /** One path-data token: a command letter or a number (signs, leading dots and exponents allowed). */
    private static final Pattern PATH_TOKEN =
            Pattern.compile("[A-Za-z]|[-+]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?");

    /** Classpath folder holding KanjiVG files named like "03042.svg" (src/main/resources/kanjivg). */
    private static final String RESOURCE_DIR = "/kanjivg/";

    /**
     * Loads the KanjiVG reference for one character.
     *
     * <p><b>Input.</b> {@code kanji} must be exactly one Unicode <em>code point</em>, which may be
     * two Java {@code char}s (a surrogate pair) for characters such as 𠮟 (U+20B9F). The lookup is
     * exact: no normalization is applied. Fullwidth and halfwidth forms, compatibility ideographs,
     * and a character followed by a variation selector are each looked up as given (the last one is
     * two code points and is rejected).
     *
     * <p><b>Output.</b> When present, the result satisfies:
     * <ul>
     *   <li>{@code kanji()} equals the argument;
     *   <li>{@code strokes()} is non-empty and in correct stroke order;
     *   <li>{@code strokes().get(i).index() == i} (0-based; KanjiVG's stroke {@code -s1} is index 0);
     *   <li>each polyline runs in the writing direction, has at least 2 points, and lies in
     *       reference space (109 × 109, origin top-left, y down);
     *   <li>{@code kvgType()} is the raw {@code kvg:type} string, or {@code null} if the file has
     *       none for that stroke.
     * </ul>
     * See the class documentation for the meaning of each of these.
     *
     * @param kanji a single character, e.g. {@code "漢"}, {@code "あ"} or {@code "A"}
     * @return the reference; empty if {@code kanji} is {@code null}, empty, more than one code point,
     *     or a character not in the bundled data set
     * @throws UncheckedIOException if the file exists but cannot be read
     * @throws IllegalStateException if the file is not well-formed XML
     * @throws IllegalArgumentException if a stroke's path data is malformed or uses an unsupported
     *     command (never the case for the bundled files)
     */
    public Optional<ReferenceKanji> get(String kanji) {
        if (kanji == null || kanji.codePointCount(0, kanji.length()) != 1) {
            return Optional.empty();
        }
        String file = RESOURCE_DIR + String.format(Locale.ROOT, "%05x.svg", kanji.codePointAt(0));
        try (InputStream in = ReferenceRepository.class.getResourceAsStream(file)) {
            if (in == null) {
                return Optional.empty();
            }
            return Optional.of(new ReferenceKanji(kanji, parseStrokes(newDocumentBuilder().parse(in))));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + file, e);
        } catch (SAXException | ParserConfigurationException e) {
            throw new IllegalStateException("Malformed KanjiVG file " + file, e);
        }
    }

    private static DocumentBuilder newDocumentBuilder() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        // KanjiVG files declare an internal DTD subset (needed for kvg:* attributes) plus an external
        // DTD URL. Process the internal subset, but never fetch anything over the network.
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setExpandEntityReferences(false);
        factory.setNamespaceAware(false); // read "kvg:type" by its literal name
        return factory.newDocumentBuilder();
    }

    private static List<ReferenceStroke> parseStrokes(Document doc) {
        record Raw(int number, String d, String type) {}
        List<Raw> raws = new ArrayList<>();
        NodeList paths = doc.getElementsByTagName("path");
        for (int i = 0; i < paths.getLength(); i++) {
            Element path = (Element) paths.item(i);
            Matcher m = STROKE_ID.matcher(path.getAttribute("id"));
            if (m.find()) {
                String type = path.getAttribute("kvg:type");
                raws.add(new Raw(Integer.parseInt(m.group(1)), path.getAttribute("d"),
                        type.isEmpty() ? null : type));
            }
        }
        raws.sort(Comparator.comparingInt(Raw::number));

        List<ReferenceStroke> strokes = new ArrayList<>(raws.size());
        for (int i = 0; i < raws.size(); i++) {
            Raw raw = raws.get(i);
            strokes.add(new ReferenceStroke(i, flatten(raw.d()), raw.type()));
        }
        return List.copyOf(strokes);
    }

    /**
     * Converts SVG path data into a polyline, following the rules in section 5 of the class
     * documentation. Supports {@code M L H V C S Q T Z} in absolute and relative forms, implicit
     * repeated commands, and compact number syntax ({@code "M1-2L.5.5"}).
     *
     * @param d the value of an SVG {@code d} attribute
     * @return an immutable list starting with the first move-to point
     * @throws IllegalArgumentException if {@code d} does not start with a command, ends in the middle
     *     of a command, or uses an unsupported command such as an arc ({@code A})
     */
    static List<Point> flatten(String d) {
        List<String> tokens = new ArrayList<>();
        Matcher m = PATH_TOKEN.matcher(d);
        while (m.find()) {
            tokens.add(m.group());
        }

        List<Point> out = new ArrayList<>();
        double x = 0, y = 0;            // current point
        double startX = 0, startY = 0;  // subpath start, for Z
        double ctrlX = 0, ctrlY = 0;    // last control point, for S/T reflection
        char cmd = 0, prev = 0;
        int i = 0;
        while (i < tokens.size()) {
            String t = tokens.get(i);
            if (Character.isLetter(t.charAt(0))) {
                cmd = t.charAt(0);
                i++;
            } else if (cmd == 0) {
                throw new IllegalArgumentException("Path data must start with a command: " + d);
            }
            boolean rel = Character.isLowerCase(cmd);
            double ox = rel ? x : 0, oy = rel ? y : 0;

            switch (Character.toUpperCase(cmd)) {
                case 'M' -> {
                    x = ox + num(tokens, i++);
                    y = oy + num(tokens, i++);
                    startX = x;
                    startY = y;
                    out.add(new Point(x, y));
                    cmd = rel ? 'l' : 'L'; // further coordinate pairs are implicit linetos
                }
                case 'L' -> {
                    x = ox + num(tokens, i++);
                    y = oy + num(tokens, i++);
                    out.add(new Point(x, y));
                }
                case 'H' -> {
                    x = ox + num(tokens, i++);
                    out.add(new Point(x, y));
                }
                case 'V' -> {
                    y = oy + num(tokens, i++);
                    out.add(new Point(x, y));
                }
                case 'C', 'S' -> {
                    double x1, y1;
                    if (Character.toUpperCase(cmd) == 'C') {
                        x1 = ox + num(tokens, i++);
                        y1 = oy + num(tokens, i++);
                    } else if ("CcSs".indexOf(prev) >= 0) {
                        x1 = 2 * x - ctrlX;
                        y1 = 2 * y - ctrlY;
                    } else {
                        x1 = x;
                        y1 = y;
                    }
                    double x2 = ox + num(tokens, i++), y2 = oy + num(tokens, i++);
                    double ex = ox + num(tokens, i++), ey = oy + num(tokens, i++);
                    for (int s = 1; s <= CURVE_SEGMENTS; s++) {
                        double u = (double) s / CURVE_SEGMENTS, v = 1 - u;
                        double a = v * v * v, b = 3 * v * v * u, c = 3 * v * u * u, e = u * u * u;
                        out.add(new Point(a * x + b * x1 + c * x2 + e * ex, a * y + b * y1 + c * y2 + e * ey));
                    }
                    ctrlX = x2;
                    ctrlY = y2;
                    x = ex;
                    y = ey;
                }
                case 'Q', 'T' -> {
                    double x1, y1;
                    if (Character.toUpperCase(cmd) == 'Q') {
                        x1 = ox + num(tokens, i++);
                        y1 = oy + num(tokens, i++);
                    } else if ("QqTt".indexOf(prev) >= 0) {
                        x1 = 2 * x - ctrlX;
                        y1 = 2 * y - ctrlY;
                    } else {
                        x1 = x;
                        y1 = y;
                    }
                    double ex = ox + num(tokens, i++), ey = oy + num(tokens, i++);
                    for (int s = 1; s <= CURVE_SEGMENTS; s++) {
                        double u = (double) s / CURVE_SEGMENTS, v = 1 - u;
                        out.add(new Point(v * v * x + 2 * v * u * x1 + u * u * ex,
                                v * v * y + 2 * v * u * y1 + u * u * ey));
                    }
                    ctrlX = x1;
                    ctrlY = y1;
                    x = ex;
                    y = ey;
                }
                case 'Z' -> {
                    x = startX;
                    y = startY;
                    out.add(new Point(x, y));
                }
                default -> throw new IllegalArgumentException("Unsupported path command '" + cmd + "' in: " + d);
            }
            prev = cmd;
        }
        return List.copyOf(out);
    }

    private static double num(List<String> tokens, int i) {
        if (i >= tokens.size() || Character.isLetter(tokens.get(i).charAt(0))) {
            throw new IllegalArgumentException("Expected a number at token " + i + " in path data");
        }
        return Double.parseDouble(tokens.get(i));
    }
}
