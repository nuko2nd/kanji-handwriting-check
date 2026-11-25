package kanji.handwriting.check.gui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Image;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.swing.filechooser.FileNameExtensionFilter;
import kanji.handwriting.check.checker.CheckerConfig;
import kanji.handwriting.check.checker.Messages;
import kanji.handwriting.check.checker.Verdict;
import kanji.handwriting.check.grid.GridChecker;
import kanji.handwriting.check.grid.KanjiGrid;
import kanji.handwriting.check.service.ReferenceRepository;

/**
 * A small window for checking a filled-in practice sheet: choose the sheet image, type the text
 * that was written on it, and press Check. The window and the feedback can be in English or
 * Japanese (the language box; Japanese by default on a Japanese system). A progress bar runs while each kanji is checked; the
 * results are then listed failures first, then those to check manually, then passes (lowest score
 * first within each), or in the order of the text (the
 * sort box), each with its overlay. Click a result to see the overlay full size. "Compare text…"
 * shows each typed character under the cell it belongs to, to catch typing mistakes in the text.
 *
 * <pre>{@code ./gradlew gui}</pre>
 */
public final class GridCheckerWindow extends JFrame {
    /** The window's own wording, in English and Japanese. */
    private record Ui(String title, String noSheet, String choose, String textLabel, String check, String imageFilter,
                      String chooseFirst, String noSheetTitle, String typeText, String noTextTitle, String reading,
                      String progress, String done, String noKanji, String counts, String where, String couldNotCheck,
                      String notReadable, String overlayTitle, String[] strictness, String[] sortOrders,
                      String compare, String compareTitle, String compareHint, String tooLong, String noTextYet,
                      String textTitle, String textHint, String charCount, String ok, String cancel) {
        static final Ui EN = new Ui("Kanji handwriting check", "No sheet chosen", "Choose sheet…",
                "Enter text…", "Check", "Images (PNG, JPEG, BMP, GIF)",
                "Choose the sheet image first.", "No sheet", "Type the text that was written on the sheet.", "No text",
                "Reading sheet…", "%d / %d kanji", "Done", "No kanji in the text", "%d pass, %d unsure, %d fail",
                "Character %d — row %d, column %d", "Could not check the sheet", "Not a readable image: %s",
                "%s — character %d", new String[] {"Strict", "Medium", "Relaxed"},
                new String[] {"Fail, unsure, pass", "Text order"}, "Compare text…", "Text and sheet",
                "Check that each typed character matches its cell. Grey characters are not checked (not kanji).",
                "%d characters do not fit on the sheet.", "(no text yet)", "Text written on the sheet",
                "<html>Type the text exactly as written: one character per cell, left to right, top to bottom.<br>"
                        + "A line break starts the next row (a new paragraph). Indent with a full-width space (　).</html>",
                "%d characters", "OK", "Cancel");
        static final Ui JA = new Ui("漢字手書きチェック", "シート未選択", "シートを選択…",
                "文を入力…", "判定", "画像（PNG・JPEG・BMP・GIF）",
                "先にシートの画像を選択してください。", "シート未選択", "シートに書いた文を入力してください。", "文が未入力",
                "シートを読み込み中…", "%d / %d 字", "完了", "文に漢字がありません", "合格 %d・要確認 %d・不合格 %d",
                "%d 文字目（%d 行 %d 列）", "シートを判定できませんでした", "画像を読み込めません：%s",
                "%s（%d 文字目）", new String[] {"厳しい", "普通", "甘い"},
                new String[] {"不合格・要確認・合格の順", "文の順"}, "文と見比べる…", "入力した文とシート",
                "入力した字が各マスの字と合っているか確認してください。灰色の字（漢字以外）は判定しません。",
                "%d 字がシートに入りきりません。", "（文が未入力）", "シートに書いた文",
                "<html>書いたとおりに入力してください。一マスに一字、左から右、上から下の順です。<br>"
                        + "改行すると次の行から書き始めます（段落）。字下げには全角スペース（　）を使います。</html>",
                "%d 字", "OK", "キャンセル");
    }

    private static final int THUMBNAIL = 140;
    private static final Color PASS = new Color(0x2e7d32), UNSURE = new Color(0xb26a00), FAIL = new Color(0xc62828);

    private final GridChecker checker = new GridChecker(new ReferenceRepository());
    private final JLabel sheetLabel = new JLabel();
    private final JButton chooseButton = new JButton();
    private final JButton textButton = new JButton();
    private final JLabel textPreview = new JLabel();
    private final JComboBox<String> language = new JComboBox<>(new String[] {"English", "日本語"});
    private final JComboBox<String> strictness = new JComboBox<>(Ui.EN.strictness());
    private final JButton checkButton = new JButton();
    private final JComboBox<String> sortOrder = new JComboBox<>(Ui.EN.sortOrders());
    private final JButton compareButton = new JButton();
    private final JProgressBar progress = new JProgressBar();
    private final JLabel status = new JLabel(" ");
    private final JPanel results = new JPanel();
    private File sheet;
    /** The text written on the sheet; line breaks start a new row. */
    private String text = "";
    private Ui ui = Ui.EN;
    private List<GridChecker.CellResult> shown = List.of();
    private Messages shownMessages = Messages.of(CheckerConfig.Language.EN);

    private GridCheckerWindow() {
        setDefaultCloseOperation(EXIT_ON_CLOSE);

        chooseButton.addActionListener(e -> chooseSheet());
        checkButton.addActionListener(e -> runCheck());
        textButton.addActionListener(e -> editText());
        textPreview.setFont(textPreview.getFont().deriveFont(16f));
        language.setPrototypeDisplayValue("English   ");
        strictness.setPrototypeDisplayValue("Relaxed   ");
        // Start in Japanese on a Japanese system; switching relabels the window at once.
        language.setSelectedIndex(java.util.Locale.getDefault().getLanguage().equals("ja") ? 1 : 0);
        language.addActionListener(e -> applyLanguage());
        sortOrder.setPrototypeDisplayValue("不合格・要確認・合格の順   ");
        sortOrder.addActionListener(e -> {
            if (!shown.isEmpty()) {
                show(shown, shownMessages);
            }
        });
        compareButton.addActionListener(e -> compareText());

        JPanel inputs = new JPanel();
        inputs.setLayout(new BoxLayout(inputs, BoxLayout.Y_AXIS));
        JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT));
        row1.add(chooseButton);
        row1.add(sheetLabel);
        row1.add(compareButton);
        row1.add(sortOrder);
        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JPanel row2b = new JPanel(new FlowLayout(FlowLayout.LEFT));
        row2.add(textButton);
        row2.add(textPreview);
        row2b.add(strictness);
        row2b.add(language);
        row2b.add(checkButton);
        JPanel row3 = new JPanel(new BorderLayout(8, 0));
        row3.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));
        progress.setStringPainted(true);
        progress.setString("");
        row3.add(progress, BorderLayout.CENTER);
        row3.add(status, BorderLayout.EAST);
        inputs.add(row1);
        inputs.add(row2);
        inputs.add(row2b);
        inputs.add(row3);

        results.setLayout(new BoxLayout(results, BoxLayout.Y_AXIS));
        JScrollPane scroll = new JScrollPane(results);
        scroll.getVerticalScrollBar().setUnitIncrement(24);

        add(inputs, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        setSize(820, 760);
        setLocationRelativeTo(null);
        applyLanguage();
    }

    private CheckerConfig.Language selectedLanguage() {
        return language.getSelectedIndex() == 1 ? CheckerConfig.Language.JA : CheckerConfig.Language.EN;
    }

    /**
     * Relabels the window, and any listed results, in the chosen language. Feedback text keeps the
     * language it was checked in until the next check.
     */
    private void applyLanguage() {
        ui = selectedLanguage() == CheckerConfig.Language.JA ? Ui.JA : Ui.EN;
        setTitle(ui.title());
        chooseButton.setText(ui.choose());
        textButton.setText(ui.textLabel());
        updateTextPreview();
        checkButton.setText(ui.check());
        compareButton.setText(ui.compare());
        int order = sortOrder.getSelectedIndex();
        sortOrder.setModel(new javax.swing.DefaultComboBoxModel<>(ui.sortOrders()));
        sortOrder.setSelectedIndex(Math.max(0, order));
        int level = strictness.getSelectedIndex();
        strictness.setModel(new javax.swing.DefaultComboBoxModel<>(ui.strictness()));
        strictness.setSelectedIndex(Math.max(0, level));
        sheetLabel.setText(sheet == null ? ui.noSheet() : sheet.getName());
        if (!shown.isEmpty()) {
            show(shown, shownMessages);
        }
    }

    private void chooseSheet() {
        JFileChooser chooser = new JFileChooser(sheet != null ? sheet.getParentFile() : new File("."));
        chooser.setFileFilter(new FileNameExtensionFilter(ui.imageFilter(), "png", "jpg", "jpeg", "bmp", "gif"));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            sheet = chooser.getSelectedFile();
            sheetLabel.setText(sheet.getName());
        }
    }

    private void runCheck() {
        if (sheet == null) {
            JOptionPane.showMessageDialog(this, ui.chooseFirst(), ui.noSheetTitle(), JOptionPane.WARNING_MESSAGE);
            return;
        }
        String text = this.text;
        if (GridChecker.writtenCharacters(text) == 0) {
            JOptionPane.showMessageDialog(this, ui.typeText(), ui.noTextTitle(), JOptionPane.WARNING_MESSAGE);
            return;
        }
        CheckerConfig config = CheckerConfig.preset(CheckerConfig.Strictness.values()[strictness.getSelectedIndex()]).toBuilder()
                .language(selectedLanguage())
                .build();
        File file = sheet;
        checkButton.setEnabled(false);
        shown = List.of();
        results.removeAll();
        results.revalidate();
        results.repaint();
        progress.setValue(0);
        progress.setString(ui.reading());
        status.setText(" ");
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));

        new SwingWorker<List<GridChecker.CellResult>, int[]>() {
            @Override
            protected List<GridChecker.CellResult> doInBackground() throws Exception {
                BufferedImage img = ImageIO.read(file);
                if (img == null) {
                    throw new IllegalArgumentException(String.format(ui.notReadable(), file.getName()));
                }
                return checker.check(img, text, config, (done, total) -> publish(new int[] {done, total}));
            }

            @Override
            protected void process(List<int[]> updates) {
                int[] last = updates.get(updates.size() - 1);
                progress.setMaximum(Math.max(1, last[1]));
                progress.setValue(last[0]);
                progress.setString(String.format(ui.progress(), last[0], last[1]));
            }

            @Override
            protected void done() {
                checkButton.setEnabled(true);
                setCursor(Cursor.getDefaultCursor());
                try {
                    show(get(), Messages.of(config.language()));
                } catch (ExecutionException e) {
                    progress.setString("");
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    JOptionPane.showMessageDialog(GridCheckerWindow.this, cause.getMessage(), ui.couldNotCheck(),
                            JOptionPane.ERROR_MESSAGE);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }.execute();
    }

    /**
     * Lists the results: failures first, then those to check manually, then passes (lowest score
     * first within each), or in the order of the text.
     */
    private void show(List<GridChecker.CellResult> cellResults, Messages m) {
        shown = List.copyOf(cellResults);
        shownMessages = m;
        results.removeAll();
        List<GridChecker.CellResult> sorted = new ArrayList<>(cellResults);
        Comparator<GridChecker.CellResult> byPosition = Comparator.comparingInt(GridChecker.CellResult::position);
        // Worst first: FAIL, then UNSURE (check manually), then PASS, whatever the scores; lowest
        // score first within each.
        Comparator<GridChecker.CellResult> worstFirst = Comparator
                .comparingInt((GridChecker.CellResult r) -> -r.result().verdict().ordinal())
                .thenComparingDouble(r -> r.result().score())
                .thenComparing(byPosition);
        sorted.sort(sortOrder.getSelectedIndex() == 1 ? byPosition : worstFirst);
        long pass = sorted.stream().filter(r -> r.result().verdict() == Verdict.PASS).count();
        long unsure = sorted.stream().filter(r -> r.result().verdict() == Verdict.UNSURE).count();
        progress.setValue(progress.getMaximum());
        progress.setString(ui.done());
        status.setText(sorted.isEmpty() ? ui.noKanji()
                : String.format(ui.counts(), pass, unsure, sorted.size() - pass - unsure));
        for (GridChecker.CellResult r : sorted) {
            results.add(row(r, m));
        }
        results.add(Box.createVerticalGlue());
        results.revalidate();
        results.repaint();
    }

    private JPanel row(GridChecker.CellResult r, Messages m) {
        JPanel row = new JPanel(new BorderLayout(12, 0));
        row.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Color.LIGHT_GRAY),
                BorderFactory.createEmptyBorder(8, 8, 8, 8)));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, THUMBNAIL + 20));

        BufferedImage overlay = r.result().overlay();
        int h = THUMBNAIL, w = overlay.getWidth() * h / overlay.getHeight();
        JLabel thumb = new JLabel(new ImageIcon(overlay.getScaledInstance(w, h, Image.SCALE_SMOOTH)));
        row.add(thumb, BorderLayout.WEST);

        Color colour = switch (r.result().verdict()) {
            case PASS -> PASS;
            case UNSURE -> UNSURE;
            case FAIL -> FAIL;
        };
        JLabel kanji = new JLabel(r.kanji());
        kanji.setFont(new Font(Font.DIALOG, Font.PLAIN, 40));
        JLabel verdict = new JLabel(m.verdict(r.result().verdict()) + "   " + m.score() + " "
                + String.format("%.2f", r.result().score()));
        verdict.setForeground(colour);
        verdict.setFont(verdict.getFont().deriveFont(Font.BOLD, 15f));
        JLabel where = new JLabel(String.format(ui.where(), r.position() + 1,
                r.cell().row() + 1, r.cell().column() + 1));
        where.setForeground(Color.GRAY);

        JTextArea feedback = new JTextArea(String.join("\n", r.result().feedback()));
        feedback.setEditable(false);
        feedback.setLineWrap(true);
        feedback.setWrapStyleWord(true);
        feedback.setOpaque(false);
        feedback.setFont(UIManager.getFont("Label.font"));

        JPanel text = new JPanel();
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        JPanel head = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        head.add(kanji);
        head.add(verdict);
        head.setAlignmentX(LEFT_ALIGNMENT);
        where.setAlignmentX(LEFT_ALIGNMENT);
        feedback.setAlignmentX(LEFT_ALIGNMENT);
        text.add(head);
        text.add(where);
        text.add(feedback);
        row.add(text, BorderLayout.CENTER);

        row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        MouseAdapter open = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                showOverlay(r);
            }
        };
        row.addMouseListener(open);
        thumb.addMouseListener(open);
        feedback.addMouseListener(open);
        return row;
    }

    /**
     * Opens a window for typing the text written on the sheet: several lines, where a line break
     * starts the next row of the grid, as a new paragraph does on 原稿用紙.
     */
    private void editText() {
        javax.swing.JTextArea area = new javax.swing.JTextArea(text, 12, 30);
        area.setFont(area.getFont().deriveFont(20f));
        area.setLineWrap(true);
        JLabel count = new JLabel();
        Runnable recount = () -> count.setText(String.format(ui.charCount(), GridChecker.writtenCharacters(area.getText())));
        recount.run();
        area.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                recount.run();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                recount.run();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                recount.run();
            }
        });
        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.add(new JLabel(ui.textHint()), BorderLayout.NORTH);
        content.add(new JScrollPane(area), BorderLayout.CENTER);
        content.add(count, BorderLayout.SOUTH);
        Object[] options = {ui.ok(), ui.cancel()};
        int choice = JOptionPane.showOptionDialog(this, content, ui.textTitle(), JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE, null, options, options[0]);
        if (choice == 0) {
            // Keep everything as typed (a leading full-width space is an indent), except line
            // breaks at the very end.
            text = area.getText().replaceAll("[\\r\\n]+$", "");
            updateTextPreview();
        }
    }

    /** Shows the start of the text next to its button, line breaks as ↵. */
    private void updateTextPreview() {
        if (GridChecker.writtenCharacters(text) == 0) {
            textPreview.setText(ui.noTextYet());
            textPreview.setForeground(Color.GRAY);
            return;
        }
        String oneLine = text.replace("\r\n", "\n").replace('\n', '↵');
        int[] cps = oneLine.codePoints().toArray();
        textPreview.setText(cps.length <= 30 ? oneLine : new String(cps, 0, 30) + "…");
        textPreview.setForeground(UIManager.getColor("Label.foreground"));
    }

    /**
     * Opens a window showing every typed character under the cell it belongs to, laid out as on
     * the sheet, so that a typing mistake in the text is easy to spot before (or after) checking.
     */
    private void compareText() {
        if (sheet == null) {
            JOptionPane.showMessageDialog(this, ui.chooseFirst(), ui.noSheetTitle(), JOptionPane.WARNING_MESSAGE);
            return;
        }
        String text = this.text;
        if (GridChecker.writtenCharacters(text) == 0) {
            JOptionPane.showMessageDialog(this, ui.typeText(), ui.noTextTitle(), JOptionPane.WARNING_MESSAGE);
            return;
        }
        File file = sheet;
        Ui words = ui;
        compareButton.setEnabled(false);
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        new SwingWorker<JPanel, Void>() {
            private String warning;

            @Override
            protected JPanel doInBackground() throws Exception {
                BufferedImage img = ImageIO.read(file);
                if (img == null) {
                    throw new IllegalArgumentException(String.format(words.notReadable(), file.getName()));
                }
                List<KanjiGrid.Cell> cells = KanjiGrid.cells(img);
                List<GridChecker.Placement> placed = GridChecker.layout(text, cells, true);
                int left = GridChecker.writtenCharacters(text) - placed.size();
                if (left > 0) {
                    warning = String.format(words.tooLong(), left);
                }
                int n = placed.size();
                int columns = 1;
                for (KanjiGrid.Cell c : cells) {
                    columns = Math.max(columns, c.column() + 1);
                }
                JPanel grid = new JPanel(new java.awt.GridLayout(0, columns, 4, 6));
                grid.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
                // Lay the tiles out by row and column, as on the sheet; gaps stay empty.
                int lastRow = n == 0 ? -1 : placed.get(n - 1).cell().row();
                JPanel[][] slots = new JPanel[lastRow + 1][columns];
                for (GridChecker.Placement p : placed) {
                    slots[p.cell().row()][p.cell().column()] = tile(img, p.cell(), p.codePoint());
                }
                // Rows left empty by a line break still show their cells, so the layout matches.
                for (KanjiGrid.Cell c : cells) {
                    if (c.row() <= lastRow && slots[c.row()][c.column()] == null) {
                        slots[c.row()][c.column()] = tile(img, c, -1);
                    }
                }
                for (JPanel[] row : slots) {
                    for (JPanel slot : row) {
                        grid.add(slot != null ? slot : new JPanel());
                    }
                }
                return grid;
            }

            @Override
            protected void done() {
                compareButton.setEnabled(true);
                setCursor(Cursor.getDefaultCursor());
                try {
                    JPanel grid = get();
                    javax.swing.JDialog dialog = new javax.swing.JDialog(GridCheckerWindow.this, words.compareTitle(), false);
                    JPanel content = new JPanel(new BorderLayout(0, 6));
                    JLabel hint = new JLabel(warning == null ? words.compareHint()
                            : "<html>" + words.compareHint() + "<br><font color='#c62828'>" + warning + "</font></html>");
                    hint.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));
                    content.add(hint, BorderLayout.NORTH);
                    JScrollPane scroll = new JScrollPane(grid);
                    scroll.getVerticalScrollBar().setUnitIncrement(24);
                    content.add(scroll, BorderLayout.CENTER);
                    dialog.setContentPane(content);
                    dialog.setSize(Math.min(1200, grid.getPreferredSize().width + 40), 720);
                    dialog.setLocationRelativeTo(GridCheckerWindow.this);
                    dialog.setVisible(true);
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    JOptionPane.showMessageDialog(GridCheckerWindow.this, cause.getMessage(), words.couldNotCheck(),
                            JOptionPane.ERROR_MESSAGE);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }.execute();
    }

    /** One cell of the sheet as written, with the typed character under it. */
    private static JPanel tile(BufferedImage sheetImage, KanjiGrid.Cell cell, int typed) {
        java.awt.Rectangle b = cell.bounds().intersection(new java.awt.Rectangle(0, 0, sheetImage.getWidth(), sheetImage.getHeight()));
        BufferedImage crop = sheetImage.getSubimage(b.x, b.y, Math.max(1, b.width), Math.max(1, b.height));
        int size = COMPARE_TILE;
        JLabel written = new JLabel(new ImageIcon(crop.getScaledInstance(size, size * crop.getHeight() / crop.getWidth(),
                Image.SCALE_SMOOTH)));
        written.setBorder(BorderFactory.createLineBorder(Color.LIGHT_GRAY));
        written.setAlignmentX(CENTER_ALIGNMENT);
        JLabel character = new JLabel(typed < 0 ? " " : Character.toString(typed));
        character.setFont(new Font(Font.DIALOG, Font.PLAIN, 30));
        character.setForeground(typed >= 0 && GridChecker.isKanji(typed) ? Color.BLACK : Color.GRAY);
        character.setAlignmentX(CENTER_ALIGNMENT);
        JPanel tile = new JPanel();
        tile.setLayout(new BoxLayout(tile, BoxLayout.Y_AXIS));
        tile.add(written);
        tile.add(character);
        return tile;
    }

    private static final int COMPARE_TILE = 64;

    private void showOverlay(GridChecker.CellResult r) {
        JLabel image = new JLabel(new ImageIcon(r.result().overlay()));
        JScrollPane scroll = new JScrollPane(image);
        scroll.setPreferredSize(new Dimension(Math.min(720, r.result().overlay().getWidth() + 24),
                Math.min(820, r.result().overlay().getHeight() + 24)));
        JOptionPane.showMessageDialog(this, scroll, String.format(ui.overlayTitle(), r.kanji(), r.position() + 1),
                JOptionPane.PLAIN_MESSAGE);
    }

    /**
     * Opens the window.
     *
     * @param args ignored
     */
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
                // The default look and feel works too.
            }
            new GridCheckerWindow().setVisible(true);
        });
    }
}
