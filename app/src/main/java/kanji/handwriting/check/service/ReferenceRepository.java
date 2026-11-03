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

/** Loads and caches KanjiVG data. Also backs GET /reference/{kanji}. */
public class ReferenceRepository {
    /** Line segments used to approximate each Bézier curve segment. */
    private static final int CURVE_SEGMENTS = 8;

    /** Stroke path ids look like "kvg:03042-s1". */
    private static final Pattern STROKE_ID = Pattern.compile("-s(\\d+)$");
    private static final Pattern PATH_TOKEN =
            Pattern.compile("[A-Za-z]|[-+]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?");

    /** Classpath folder holding KanjiVG files named like "03042.svg" (src/main/resources/kanjivg). */
    private static final String RESOURCE_DIR = "/kanjivg/";

    /**
     * Loads the KanjiVG reference for a single character.
     *
     * @return empty if the input is not exactly one character or no KanjiVG file exists for it
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

    /** Converts SVG path data into a polyline, approximating curves with straight segments. */
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
