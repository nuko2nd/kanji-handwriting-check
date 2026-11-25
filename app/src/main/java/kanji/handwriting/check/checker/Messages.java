package kanji.handwriting.check.checker;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * All user-facing text, in English or Japanese ({@link CheckerConfig#language()}). Distances are
 * shown as a percentage of the square (109 reference units = 100%), which means something to a
 * learner; reference units do not.
 */
public final class Messages {
    private static final Pattern STROKES = Pattern.compile("strokes (\\d+)-(\\d+)");
    private static final Pattern STROKE = Pattern.compile("stroke (\\d+)");

    private final boolean ja;

    private Messages(boolean ja) {
        this.ja = ja;
    }

    /**
     * Messages for a language.
     *
     * @param language the language
     * @return the messages
     */
    public static Messages of(CheckerConfig.Language language) {
        return new Messages(language == CheckerConfig.Language.JA);
    }

    /**
     * Whether this is Japanese.
     *
     * @return true for Japanese
     */
    public boolean japanese() {
        return ja;
    }

    /**
     * A component label in this language: {@code "stroke 3"} becomes 「3画目」 and
     * {@code "strokes 2-4"} 「2〜4画目」; element labels are unchanged.
     *
     * @param label label from {@code ReferenceShape.Component}
     * @return the display label
     */
    public String label(String label) {
        if (!ja) {
            return label;
        }
        Matcher m = STROKES.matcher(label);
        if (m.matches()) {
            return m.group(1) + "〜" + m.group(2) + "画目";
        }
        m = STROKE.matcher(label);
        return m.matches() ? m.group(1) + "画目" : label;
    }

    /**
     * Message text.
     *
     * @param verdict the verdict
     * @return 「合格」/「要確認」/「不合格」 or PASS/UNSURE (check manually)/FAIL
     */
    public String verdict(Verdict verdict) {
        return switch (verdict) {
            case PASS -> ja ? "合格" : "PASS";
            case UNSURE -> ja ? "要確認（判定できないため目視で確認してください）" : "UNSURE (check manually)";
            case FAIL -> ja ? "不合格" : "FAIL";
        };
    }

    /**
     * Message text.
     *
     * @return the word for "score"
     */
    public String score() {
        return ja ? "得点" : "score";
    }

    /**
     * Message text.
     *
     * @return feedback when the image is blank
     */
    public String noInk() {
        return ja ? "画像に線が見つかりません。" : "No ink found in the image.";
    }

    /**
     * Message text.
     *
     * @param smaller drawn smaller than the grid
     * @param percent size as a percentage of the full size
     * @param scored whether the size costs score (strict grading)
     * @return size note
     */
    public String sizeNote(boolean smaller, double percent, boolean scored) {
        if (scored) {
            return ja
                    ? String.format("マスより%sく書かれています（全体の約%.0f%%）。少し減点されます。", smaller ? "小さ" : "大き", percent)
                    : String.format("Drawn %s than the grid (about %.0f%% of the full size); this costs a little score.",
                            smaller ? "smaller" : "larger", percent);
        }
        return ja
                ? String.format("注：マスより%sく書かれています（全体の約%.0f%%）。大きさは採点に含まれません。", smaller ? "小さ" : "大き", percent)
                : String.format("Note: drawn %s than the grid (about %.0f%% of the full size); size is not scored.",
                        smaller ? "smaller" : "larger", percent);
    }

    /**
     * Message text.
     *
     * @param degrees tilt magnitude
     * @param clockwise direction
     * @return whole-drawing tilt message
     */
    public String tilted(double degrees, boolean clockwise) {
        return ja
                ? String.format("全体が約%.0f度、%sに傾いています。", degrees, clockwise ? "時計回り" : "反時計回り")
                : String.format("Drawn tilted (about %.0f degrees %s).", degrees, clockwise ? "clockwise" : "anticlockwise");
    }

    /**
     * Message text.
     *
     * @param dx shift needed in x (positive: drawing is too far left)
     * @param dy shift needed in y (positive: drawing is too high)
     * @param limit shifts beyond this are mentioned
     * @return whole-drawing off-centre message
     */
    public String offCentre(double dx, double dy, double limit) {
        StringBuilder dirs = new StringBuilder();
        if (dx > limit) dirs.append(ja ? "左" : "too far left");
        if (dx < -limit) dirs.append(ja ? "右" : "too far right");
        if (dy > limit) dirs.append(dirs.isEmpty() ? "" : ja ? "・" : " and ").append(ja ? "上" : "too high");
        if (dy < -limit) dirs.append(dirs.isEmpty() ? "" : ja ? "・" : " and ").append(ja ? "下" : "too low");
        return ja ? "注：全体の位置が" + dirs + "に寄っています。位置は採点に含まれません。"
                : "Note: drawn off-centre (" + dirs + "); position is not scored.";
    }

    /**
     * Message text.
     *
     * @param stroke 1-based stroke number
     * @param percent how much of it was drawn
     * @return missing/incomplete stroke
     */
    public String strokeMissing(int stroke, double percent) {
        return ja ? String.format("%d画目が足りないか短すぎます（%.0f%%）", stroke, percent)
                : String.format("stroke %d is missing or too short (%.0f%% drawn)", stroke, percent);
    }

    /**
     * Message text.
     *
     * @param stroke 1-based stroke number, or 0 for "the whole part"
     * @param dx shift in reference units (positive: right)
     * @param dy shift (positive: down)
     * @return shift description
     */
    public String shifted(int stroke, double dx, double dy) {
        double pct = Math.hypot(dx, dy) / 109 * 100;
        String dir = direction(dx, dy);
        if (ja) {
            return (stroke > 0 ? stroke + "画目が" : "位置が") + String.format("%sに約%.0f%%ずれています", dir, pct);
        }
        return (stroke > 0 ? "stroke " + stroke + " is " : "placed ")
                + String.format("about %.0f%% of the square %s", pct, dir);
    }

    /**
     * Message text.
     *
     * @param stroke 1-based stroke number, or 0 for the whole part
     * @param scale size factor relative to the reference
     * @return length/size description
     */
    public String resized(int stroke, double scale) {
        double pct = Math.abs(scale - 1) * 100;
        boolean bigger = scale > 1;
        if (ja) {
            return stroke > 0
                    ? String.format("%d画目が約%.0f%%%s", stroke, pct, bigger ? "長すぎます" : "短すぎます")
                    : String.format("ほかの部分と比べて約%.0f%%%s", pct, bigger ? "大きすぎます" : "小さすぎます");
        }
        return stroke > 0
                ? String.format("stroke %d is about %.0f%% too %s", stroke, pct, bigger ? "long" : "short")
                : String.format("about %.0f%% too %s compared with the rest", pct, bigger ? "large" : "small");
    }

    /**
     * Message text.
     *
     * @param stroke 1-based stroke number, or 0 for the whole part
     * @param degrees rotation (positive: clockwise)
     * @return tilt description
     */
    public String rotated(int stroke, double degrees) {
        String dir = ja ? (degrees > 0 ? "時計回り" : "反時計回り") : (degrees > 0 ? "clockwise" : "anticlockwise");
        if (ja) {
            return (stroke > 0 ? stroke + "画目が" : "") + String.format("約%.0f度%sに傾いています", Math.abs(degrees), dir);
        }
        return (stroke > 0 ? "stroke " + stroke + " is " : "") + String.format("tilted about %.0f degrees %s", Math.abs(degrees), dir);
    }

    /**
     * Message text.
     *
     * @param percent share of ink on target
     * @return extra-ink description
     */
    public String extraInk(double percent) {
        return ja ? String.format("余分な線、または位置のずれた線があります（正しい位置の線は%.0f%%）", percent)
                : String.format("extra or misplaced ink (%.0f%% of its ink is on target)", percent);
    }

    /**
     * Message text.
     *
     * @param count number of marks
     * @return extra-mark description
     */
    public String extraMarks(int count) {
        return ja ? String.format("近くに余分な線が%d本あります", count) : count + " extra mark(s) nearby";
    }

    /**
     * Message text.
     *
     * @return fallback when no specific problem was found
     */
    public String shapeDiffers() {
        return ja ? "形が違います" : "shape differs";
    }

    /**
     * Message text.
     *
     * @param label component label (already localized)
     * @param problems problem descriptions
     * @return one feedback line
     */
    public String componentLine(String label, List<String> problems) {
        return ja ? label + "：" + String.join("、", problems) + "。" : label + ": " + String.join("; ", problems) + ".";
    }

    /**
     * Message text.
     *
     * @return overlay legend explaining the colours
     */
    public String legend() {
        return ja ? "灰色：手本　赤の破線：足りない部分　橙色：余分な線　細線：合わせた位置"
                : "grey: reference   red dashes: missing   orange: extra   thin line: snapped position";
    }

    /**
     * Message text.
     *
     * @param label localized label
     * @param score component score
     * @param coverage stroke score/coverage
     * @param precision precision
     * @param marks extra marks
     * @return overlay legend line for one component
     */
    public String legendLine(String label, double score, double coverage, double precision, int marks) {
        if (ja) {
            return String.format("%s　得点 %.2f（画 %.2f、精度 %.2f%s）", label, score, coverage, precision,
                    marks > 0 ? "、余分な線 " + marks : "");
        }
        return String.format("%s  score %.2f  (strokes %.2f, precision %.2f%s)", label, score, coverage, precision,
                marks > 0 ? ", " + marks + " extra mark(s)" : "");
    }

    /**
     * A problem with a part of a component, e.g. "口 about 30% too large compared with the rest".
     *
     * @param label localized part label
     * @param text description from {@link #shifted}, {@link #resized} or {@link #rotated} with
     *     stroke 0
     * @return the combined description
     */
    public String part(String label, String text) {
        return ja ? label + "は" + text : label + " " + text;
    }

    /**
     * A short stroke written in a clearly different direction, e.g. ／ instead of ＼.
     *
     * @param stroke 1-based stroke number
     * @param degrees angle between the drawn and the reference direction
     * @return description
     */
    public String wrongDirection(int stroke, double degrees) {
        return ja ? String.format("%d画目の向きが違います（約%.0f度ずれています）", stroke, Math.abs(degrees))
                : String.format("stroke %d is drawn in the wrong direction (about %.0f degrees off)", stroke, Math.abs(degrees));
    }

    // ---- Command-line output ----

    /**
     * Message text.
     *
     * @param kanji the character
     * @param verdict the verdict
     * @param score kanji score
     * @return headline
     */
    public String headline(String kanji, Verdict verdict, double score) {
        return ja ? String.format("%s：%s（得点 %.2f）", kanji, verdict(verdict), score)
                : String.format("%s: %s (score %.2f)", kanji, verdict(verdict), score);
    }

    /**
     * Message text.
     *
     * @param reference character used instead
     * @return override line
     */
    public String referenceUsed(String reference) {
        return ja ? "  使用した手本：" + reference : "  reference used: " + reference;
    }

    /**
     * A radical that looks like a different, look-alike radical (彳 written for 亻).
     *
     * @param radical the radical that belongs in the kanji
     * @param lookAlike the radical the drawing looks like
     * @return the message
     */
    public String looksLike(String radical, String lookAlike) {
        return ja ? String.format("%sが%sのように見えます。", radical, lookAlike)
                : String.format("%s looks like %s.", radical, lookAlike);
    }

    /**
     * Ink that is not part of any stroke, close to the strokes (an extra short line).
     *
     * @return the message
     */
    public String extraLine() {
        return ja ? "どの画にも当たらない線があります（余分な画？）。"
                : "There is a line that is not part of any stroke (an extra stroke?).";
    }

    /**
     * A stroke that should cross another and carry on past it, but stops at it.
     *
     * @param stroke 1-based stroke number
     * @return the message
     */
    public String noProtrusion(int stroke) {
        return ja ? String.format("%d画目が、突き抜けるべき画で止まっています。", stroke)
                : String.format("Stroke %d stops at a stroke it should cross and carry on past.", stroke);
    }

    /**
     * Message text.
     *
     * @param stroke 1-based stroke number
     * @return a stroke drawn on through the stroke it should stop at
     */
    public String overshoot(int stroke) {
        return ja ? String.format("%d画目が、止めるべき画を突き抜けています。", stroke)
                : String.format("Stroke %d is drawn on through the stroke it should stop at.", stroke);
    }

    /**
     * Names the variant form the drawing matched.
     *
     * @param variant the variant's name, e.g. "Straight"
     * @return the CLI line
     */
    public String variantUsed(String variant) {
        return ja ? "  一致した字形：" + variant : "  matched form: " + variant;
    }

    /**
     * Message text.
     *
     * @param scale applied scale
     * @param rotation applied rotation in degrees
     * @param tx shift x
     * @param ty shift y
     * @param limitHit whether a rotation/translation limit was hit
     * @return alignment line
     */
    public String alignment(double scale, double rotation, double tx, double ty, boolean limitHit) {
        return ja
                ? String.format("  位置合わせ：拡大率 %.3f、回転 %.1f度、移動 (%.1f, %.1f)%s", scale, rotation, tx, ty, limitHit ? "　［上限に到達］" : "")
                : String.format("  alignment: scale %.3f, rotation %.1f deg, shift (%.1f, %.1f)%s", scale, rotation, tx, ty,
                        limitHit ? "  [limit hit]" : "");
    }

    /**
     * Message text.
     *
     * @return heading of the component list
     */
    public String componentsHeading() {
        return ja ? "  部品（得点の低い順）：" : "  components (worst first):";
    }

    /**
     * Message text.
     *
     * @param label localized label
     * @param score component score
     * @param strokes lowest stroke score
     * @param precision precision
     * @param marks extra marks
     * @return component line
     */
    public String componentRow(String label, double score, double strokes, double precision, int marks) {
        return ja
                ? String.format("    %-8s 得点 %.2f　画 %.2f　精度 %.2f%s", label, score, strokes, precision, marks > 0 ? "　余分な線 " + marks : "")
                : String.format("    %-12s score %.2f  strokes %.2f  precision %.2f%s", label, score, strokes, precision,
                        marks > 0 ? "  extra marks " + marks : "");
    }

    /**
     * Message text.
     *
     * @param placementScore component placement score
     * @param shift shift length
     * @param scale size factor
     * @param rotation rotation in degrees
     * @return component placement line
     */
    public String placementRow(double placementScore, double shift, double scale, double rotation) {
        return ja
                ? String.format("      位置 %.2f（ずれ %.0f%%、大きさ ×%.2f、傾き %.0f度）", placementScore, shift / 109 * 100, scale, rotation)
                : String.format("      placement %.2f (shift %.0f%%, size x%.2f, tilt %.0f deg)", placementScore, shift / 109 * 100,
                        scale, rotation);
    }

    /**
     * Message text.
     *
     * @param stroke 1-based stroke number
     * @param type KanjiVG stroke type or ""
     * @param score stroke score
     * @param coverage coverage
     * @param shift shift length
     * @param scale size factor
     * @param rotation rotation in degrees
     * @return stroke line
     */
    public String strokeRow(int stroke, String type, double score, double coverage, double shift, double scale, double rotation) {
        return ja
                ? String.format("      %2d画目 %-4s 得点 %.2f　被覆 %.2f　ずれ %.0f%%　長さ ×%.2f　傾き %.0f度", stroke, type, score,
                        coverage, shift / 109 * 100, scale, rotation)
                : String.format("      stroke %2d %-4s score %.2f  coverage %.2f  shift %.0f%%  length x%.2f  tilt %.0f deg", stroke,
                        type, score, coverage, shift / 109 * 100, scale, rotation);
    }

    /**
     * Message text.
     *
     * @param x centroid x (reference units)
     * @param y centroid y
     * @param area area in square units
     * @param component localized label
     * @return extra-mark line
     */
    public String extraMarkRow(double x, double y, double area, String component) {
        return ja ? String.format("  余分な線：(%.0f, %.0f) 付近、面積 %.1f、%s に関連", x, y, area, component)
                : String.format("  extra mark near (%.0f, %.0f), area %.1f, attributed to %s", x, y, area, component);
    }

    /**
     * Message text.
     *
     * @param path where the overlay was saved
     * @return confirmation line
     */
    public String overlaySaved(String path) {
        return ja ? "判定画像を保存しました：" + path : "Overlay saved to " + path;
    }

    private String direction(double dx, double dy) {
        // Name the dominant direction(s): only mention an axis carrying at least 40% of the shift.
        double len = Math.hypot(dx, dy);
        boolean horiz = Math.abs(dx) >= 0.4 * len, vert = Math.abs(dy) >= 0.4 * len;
        String h = dx > 0 ? (ja ? "右" : "too far right") : (ja ? "左" : "too far left");
        String v = dy > 0 ? (ja ? "下" : "too low") : (ja ? "上" : "too high");
        if (ja) {
            return horiz && vert ? v + h : horiz ? h : v;
        }
        return horiz && vert ? h + " and " + v : horiz ? h : v;
    }
}
