import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import javax.imageio.ImageIO;

/**
 * Turns the user guide Markdown into a PDF, with no library of any kind.
 *
 * <p>The app itself has no dependencies, and neither does its toolchain: this is a single file that
 * any JDK 11 or newer can run straight from source, on a developer machine and in CI alike.
 *
 * <pre>
 * java tools/MdPdf.java docs/USER_GUIDE.md docs/navi-user-guide.pdf
 * </pre>
 *
 * <p>Supported Markdown: headings, paragraphs, bulleted and numbered lists, fenced code, pipe
 * tables, horizontal rules, images with a caption, and inline emphasis with {@code **bold**} and
 * {@code `code`}.
 */
public final class MdPdf {

    // A4 in points, and a margin that leaves a comfortable measure for 10pt text.
    private static final float PAGE_W = 595;
    private static final float PAGE_H = 842;
    private static final float MARGIN = 56;
    private static final float CONTENT_W = PAGE_W - 2 * MARGIN;

    private static final float BODY_SIZE = 10f;
    private static final float LEADING = 14f;

    private static final String REGULAR = "F1";
    private static final String BOLD = "F2";
    private static final String MONO = "F3";

    /** Largest image width in pixels; screenshots are scaled down to this. */
    private static final int MAX_IMAGE_PX = 1000;

    private MdPdf() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: MdPdf <input.md> <output.pdf>");
            System.exit(2);
        }
        Path in = Paths.get(args[0]);
        Path out = Paths.get(args[1]);
        String markdown = new String(Files.readAllBytes(in), StandardCharsets.UTF_8);

        MdPdf pdf = new MdPdf();
        Doc doc = new Doc(in.toAbsolutePath().getParent());
        doc.render(markdown);
        byte[] bytes = doc.finish();
        Files.createDirectories(out.toAbsolutePath().getParent());
        Files.write(out, bytes);
        System.out.printf("%s: %d pages, %d KB%n", out, doc.pageCount, bytes.length / 1024);
    }

    // ───────────────────────────── text encoding ─────────────────────────────

    /**
     * Single-byte codes for the characters the guide actually uses.
     *
     * <p>The base-14 PDF fonts are written with WinAnsiEncoding, which has no Polish letters, so
     * the few that are needed are mapped into free codes and named in a /Differences array. Viewers
     * resolve those glyph names against the Helvetica substitute they use, which does carry them.
     */
    private static final Map<Character, Integer> EXTRA = new HashMap<>();

    private static final String[] DIFFERENCES = {
        "Aogonek",
        "Cacute",
        "Eogonek",
        "Lslash",
        "Nacute",
        "Sacute",
        "Zacute",
        "Zdotaccent",
        "aogonek",
        "cacute",
        "eogonek",
        "lslash",
        "nacute",
        "sacute",
        "zacute",
        "zdotaccent"
    };
    private static final char[] DIFF_CHARS = {
        'Ą', 'Ć', 'Ę', 'Ł', 'Ń', 'Ś', 'Ź', 'Ż',
        'ą', 'ć', 'ę', 'ł', 'ń', 'ś', 'ź', 'ż'
    };

    /** The first code the /Differences array occupies. */
    private static final int DIFF_START = 128;

    static {
        for (int i = 0; i < DIFF_CHARS.length; i++) {
            EXTRA.put(DIFF_CHARS[i], DIFF_START + i);
        }
        // WinAnsi positions for the punctuation the guide uses.
        EXTRA.put('…', 0x85); // ellipsis
        EXTRA.put('‘', 0x91);
        EXTRA.put('’', 0x92);
        EXTRA.put('“', 0x93);
        EXTRA.put('”', 0x94);
        EXTRA.put('–', 0x96); // en dash
        EXTRA.put('—', 0x97); // em dash
        EXTRA.put('‹', 0x8B);
        EXTRA.put('›', 0x9B);
        EXTRA.put('·', 0xB7); // middle dot
        EXTRA.put('°', 0xB0); // degree
        EXTRA.put(' ', 0x20);
        EXTRA.put('→', 0x3E); // arrow, as ">"
    }

    private static int code(char c) {
        if (c >= 32 && c < 127) {
            return c;
        }
        Integer mapped = EXTRA.get(c);
        if (mapped != null) {
            return mapped;
        }
        if (c >= 160 && c <= 255) {
            return c; // Latin-1 matches WinAnsi here
        }
        return '?';
    }

    /** A PDF string literal, with the escapes the syntax requires. */
    static String literal(String s) {
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < s.length(); i++) {
            int b = code(s.charAt(i));
            if (b == '(' || b == ')' || b == '\\') {
                sb.append('\\').append((char) b);
            } else if (b < 32 || b > 126) {
                sb.append('\\').append(String.format("%03o", b));
            } else {
                sb.append((char) b);
            }
        }
        return sb.append(')').toString();
    }

    // ───────────────────────────── font metrics ─────────────────────────────

    /**
     * Helvetica advance widths in 1/1000 em, for the ASCII range.
     *
     * <p>They are needed to wrap lines and to centre text; the values are the ones from the Adobe
     * Font Metrics for Helvetica and Helvetica-Bold.
     */
    private static final int[] HELV = new int[256];

    private static final int[] HELV_BOLD = new int[256];

    /** Codes 32..126, straight from the Helvetica AFM file. */
    private static final int[] HELV_ASCII = {
        278, 278, 355, 556, 556, 889, 667, 191, 333, 333, 389, 584, 278, 333, 278, 278,
        556, 556, 556, 556, 556, 556, 556, 556, 556, 556, 278, 278, 584, 584, 584, 556,
        1015, 667, 667, 722, 722, 667, 611, 778, 722, 278, 500, 667, 556, 833, 722, 778,
        667, 778, 722, 667, 611, 722, 667, 944, 667, 667, 611, 278, 278, 278, 469, 556,
        333, 556, 556, 500, 556, 556, 278, 556, 556, 222, 222, 500, 222, 833, 556, 556,
        556, 556, 333, 500, 278, 556, 500, 722, 500, 500, 500, 334, 260, 334, 584
    };

    /** The same for Helvetica-Bold. */
    private static final int[] HELV_BOLD_ASCII = {
        278, 333, 474, 556, 556, 889, 722, 238, 333, 333, 389, 584, 278, 333, 278, 278,
        556, 556, 556, 556, 556, 556, 556, 556, 556, 556, 333, 333, 584, 584, 584, 611,
        975, 722, 722, 722, 722, 667, 611, 778, 722, 278, 556, 722, 611, 833, 722, 778,
        667, 778, 722, 667, 611, 722, 667, 944, 667, 667, 611, 333, 278, 333, 584, 556,
        333, 556, 611, 556, 611, 556, 333, 611, 611, 278, 278, 556, 278, 889, 611, 611,
        611, 611, 389, 556, 333, 611, 556, 778, 556, 556, 500, 389, 280, 389, 584
    };

    static {
        // Anything outside the ASCII range is close enough to an average letter;
        // the accented letters the guide uses are as wide as their base letter.
        Arrays.fill(HELV, 556);
        Arrays.fill(HELV_BOLD, 611);
        System.arraycopy(HELV_ASCII, 0, HELV, 32, HELV_ASCII.length);
        System.arraycopy(HELV_BOLD_ASCII, 0, HELV_BOLD, 32, HELV_BOLD_ASCII.length);
        // The punctuation mapped into the high codes by EXTRA.
        int[][] wide = {
            {0x85, 1000},
            {0x96, 556},
            {0x97, 1000},
            {0xB7, 278},
            {0xB0, 400},
            {0x91, 222},
            {0x92, 222},
            {0x93, 333},
            {0x94, 333},
            {0xD7, 584}
        };
        for (int[] w : wide) {
            HELV[w[0]] = w[1];
            HELV_BOLD[w[0]] = w[1];
        }
    }

    static float width(String text, String font, float size) {
        float total = 0;
        for (int i = 0; i < text.length(); i++) {
            int c = code(text.charAt(i));
            if (MONO.equals(font)) {
                total += 600; // Courier is monospaced
            } else if (BOLD.equals(font)) {
                total += c < 256 ? HELV_BOLD[c] : 611;
            } else {
                total += c < 256 ? HELV[c] : 556;
            }
        }
        return total * size / 1000f;
    }

    // ───────────────────────────── the document ─────────────────────────────

    /** One styled run of text inside a line. */
    static final class Run {
        final String text;
        final String font;
        final float size;
        final float grey;

        Run(String text, String font, float size, float grey) {
            this.text = text;
            this.font = font;
            this.size = size;
            this.grey = grey;
        }
    }

    static final class Doc {

        private final Path base;
        private final List<byte[]> objects = new ArrayList<>();
        private final List<Integer> pageObjects = new ArrayList<>();
        private final List<String> imageNames = new ArrayList<>();
        private final List<Integer> imageObjects = new ArrayList<>();
        private final Map<String, String> imageByPath = new HashMap<>();

        private StringBuilder page = new StringBuilder();
        private float y = PAGE_H - MARGIN;
        int pageCount;
        private String title = "User guide";

        Doc(Path base) {
            this.base = base;
        }

        // ───────────────────────── markdown ─────────────────────────

        void render(String markdown) throws IOException {
            String[] lines = markdown.replace("\r\n", "\n").split("\n", -1);
            List<String> paragraph = new ArrayList<>();
            boolean inCode = false;
            List<String> code = new ArrayList<>();
            List<String[]> table = new ArrayList<>();
            Item item = new Item();

            for (String raw : lines) {
                String line = raw.stripTrailing();

                if (line.startsWith("```")) {
                    flush(paragraph);
                    if (inCode) {
                        codeBlock(code);
                        code.clear();
                    }
                    inCode = !inCode;
                    continue;
                }
                if (inCode) {
                    code.add(line);
                    continue;
                }

                if (line.startsWith("|")) {
                    flush(paragraph);
                    flushItem(item);
                    if (!line.replace("|", "").replace("-", "").replace(":", "").isBlank()) {
                        table.add(cells(line));
                    }
                    continue;
                }
                if (!table.isEmpty()) {
                    table(table);
                    table.clear();
                }

                if (line.isBlank()) {
                    flush(paragraph);
                    flushItem(item);
                } else if (line.startsWith("#")) {
                    flush(paragraph);
                    flushItem(item);
                    int level = 0;
                    while (level < line.length() && line.charAt(level) == '#') {
                        level++;
                    }
                    heading(line.substring(level).trim(), level);
                } else if (line.startsWith("![")) {
                    flush(paragraph);
                    flushItem(item);
                    image(line);
                } else if (line.equals("---") || line.equals("***")) {
                    flush(paragraph);
                    flushItem(item);
                    rule();
                } else if (line.startsWith("- ") || line.startsWith("* ")) {
                    flush(paragraph);
                    flushItem(item);
                    item.marker = "·";
                    item.text.add(line.substring(2).trim());
                } else if (line.matches("\\d+\\. .*")) {
                    flush(paragraph);
                    flushItem(item);
                    int dot = line.indexOf('.');
                    item.marker = line.substring(0, dot + 1);
                    item.text.add(line.substring(dot + 2).trim());
                } else if (!item.text.isEmpty() && raw.startsWith("  ")) {
                    // A lazy continuation line: part of the list item above it.
                    item.text.add(line.trim());
                } else if (line.startsWith(">")) {
                    flush(paragraph);
                    flushItem(item);
                    quote(line.substring(1).trim());
                } else {
                    flushItem(item);
                    paragraph.add(line.trim());
                }
            }
            flushItem(item);
            flush(paragraph);
            if (!table.isEmpty()) {
                table(table);
            }
            closePage();
        }

        /** A list item being gathered, with the continuation lines that follow it. */
        private static final class Item {
            String marker = "00B7";
            final List<String> text = new ArrayList<>();
        }

        private void flushItem(Item item) {
            if (item.text.isEmpty()) {
                return;
            }
            bullet(item.marker, String.join(" ", item.text));
            item.text.clear();
        }

        private static String[] cells(String line) {
            String trimmed = line.trim();
            if (trimmed.startsWith("|")) {
                trimmed = trimmed.substring(1);
            }
            if (trimmed.endsWith("|")) {
                trimmed = trimmed.substring(0, trimmed.length() - 1);
            }
            String[] out = trimmed.split("\\|", -1);
            for (int i = 0; i < out.length; i++) {
                out[i] = out[i].trim();
            }
            return out;
        }

        private void flush(List<String> paragraph) {
            if (paragraph.isEmpty()) {
                return;
            }
            paragraphText(String.join(" ", paragraph), MARGIN, CONTENT_W, BODY_SIZE, 0.1f);
            y -= 5;
            paragraph.clear();
        }

        // ───────────────────────── block layout ─────────────────────────

        private void heading(String text, int level) {
            if (level == 1) {
                if (pageCount == 0) {
                    title = text;
                }
                if (page.length() > 0) {
                    closePage();
                }
                need(60);
                y -= 10;
                line(new Run(text, BOLD, 19f, 0f), MARGIN);
                y -= 10;
                rule();
                y -= 6;
                return;
            }
            float size = level == 2 ? 13.5f : 11f;
            need(50);
            y -= level == 2 ? 14 : 10;
            line(new Run(text, BOLD, size, 0.05f), MARGIN);
            y -= level == 2 ? 6 : 4;
        }

        private void paragraphText(String text, float x, float w, float size, float grey) {
            List<Run> runs = inline(text, size, grey);
            float lineWidth = 0;
            List<Run> current = new ArrayList<>();
            for (Run run : runs) {
                for (String word : run.text.split(" ", -1)) {
                    if (word.isEmpty()) {
                        continue;
                    }
                    float wordWidth = width(word + " ", run.font, run.size);
                    if (lineWidth + wordWidth > w && !current.isEmpty()) {
                        writeLine(current, x);
                        current = new ArrayList<>();
                        lineWidth = 0;
                    }
                    current.add(new Run(word + " ", run.font, run.size, run.grey));
                    lineWidth += wordWidth;
                }
            }
            if (!current.isEmpty()) {
                writeLine(current, x);
            }
        }

        private void writeLine(List<Run> runs, float x) {
            need(LEADING);
            y -= runs.get(0).size + 3.5f;
            float cursor = x;
            for (Run run : runs) {
                emit(run, cursor);
                cursor += width(run.text, run.font, run.size);
            }
        }

        private void line(Run run, float x) {
            need(run.size + 8);
            y -= run.size + 4;
            emit(run, x);
        }

        private void bullet(String marker, String text) {
            need(LEADING);
            float indent = 16;
            y -= BODY_SIZE + 3.5f;
            emit(new Run(marker, REGULAR, BODY_SIZE, 0.45f), MARGIN + 4);
            y += BODY_SIZE + 3.5f; // paragraphText moves it down again
            paragraphText(text, MARGIN + indent, CONTENT_W - indent, BODY_SIZE, 0.1f);
            y -= 2;
        }

        private void quote(String text) {
            paragraphText(text, MARGIN + 16, CONTENT_W - 16, BODY_SIZE, 0.45f);
            y -= 5;
        }

        private void codeBlock(List<String> code) {
            need(code.size() * 12f + 12);
            y -= 6;
            float top = y;
            for (String row : code) {
                need(12);
                y -= 11.5f;
                emit(new Run(row, MONO, 8.5f, 0.15f), MARGIN + 8);
            }
            box(MARGIN, y - 6, CONTENT_W, top - y + 10, 0.93f);
            y -= 12;
        }

        private void rule() {
            need(12);
            y -= 6;
            page.append("0.75 G 0.6 w ")
                    .append(fmt(MARGIN))
                    .append(' ')
                    .append(fmt(y))
                    .append(" m ")
                    .append(fmt(PAGE_W - MARGIN))
                    .append(' ')
                    .append(fmt(y))
                    .append(" l S\n");
            y -= 6;
        }

        private void table(List<String[]> rows) {
            int columns = 0;
            for (String[] row : rows) {
                columns = Math.max(columns, row.length);
            }
            float cellWidth = CONTENT_W / columns;
            need(rows.size() * LEADING + 10);
            y -= 8;
            for (int r = 0; r < rows.size(); r++) {
                need(LEADING + 4);
                float rowTop = y;
                y -= BODY_SIZE + 4;
                String[] row = rows.get(r);
                for (int c = 0; c < columns; c++) {
                    String text = c < row.length ? row[c] : "";
                    emit(
                            new Run(strip(text), r == 0 ? BOLD : REGULAR, 9f, 0.1f),
                            MARGIN + c * cellWidth + 4);
                }
                if (r == 0) {
                    float under = y - 4;
                    page.append("0.75 G 0.6 w ")
                            .append(fmt(MARGIN))
                            .append(' ')
                            .append(fmt(under))
                            .append(" m ")
                            .append(fmt(PAGE_W - MARGIN))
                            .append(' ')
                            .append(fmt(under))
                            .append(" l S\n");
                    y -= 6;
                }
                if (rowTop < 0) {
                    break;
                }
            }
            y -= 8;
        }

        private void image(String line) throws IOException {
            int close = line.indexOf("](");
            if (close < 0) {
                return;
            }
            String alt = line.substring(2, close);
            String path = line.substring(close + 2, line.lastIndexOf(')'));
            File file = base.resolve(path).toFile();
            if (!file.exists()) {
                System.err.println("missing image: " + file);
                return;
            }
            String name = imageByPath.get(file.getAbsolutePath());
            int[] size;
            if (name == null) {
                BufferedImage img = ImageIO.read(file);
                if (img == null) {
                    System.err.println("unreadable image: " + file);
                    return;
                }
                img = scaled(img);
                name = "Im" + (imageNames.size() + 1);
                imageNames.add(name);
                imageObjects.add(addImage(img));
                imageByPath.put(file.getAbsolutePath(), name);
                sizes.put(name, new int[] {img.getWidth(), img.getHeight()});
            }
            size = sizes.get(name);

            float w = CONTENT_W;
            float h = w * size[1] / size[0];
            need(h + 24);
            y -= h + 4;
            page.append("q ")
                    .append(fmt(w))
                    .append(" 0 0 ")
                    .append(fmt(h))
                    .append(' ')
                    .append(fmt(MARGIN))
                    .append(' ')
                    .append(fmt(y))
                    .append(" cm /")
                    .append(name)
                    .append(" Do Q\n");
            if (!alt.isBlank()) {
                y -= 2;
                centred(new Run(alt, REGULAR, 8.5f, 0.45f));
            }
            y -= 10;
        }

        private final Map<String, int[]> sizes = new HashMap<>();

        private void centred(Run run) {
            need(run.size + 6);
            y -= run.size + 2;
            emit(run, MARGIN + (CONTENT_W - width(run.text, run.font, run.size)) / 2);
        }

        private static String strip(String text) {
            return text.replace("**", "").replace("`", "");
        }

        /** Splits a line into runs at **bold** and `code` spans. */
        private static List<Run> inline(String text, float size, float grey) {
            List<Run> runs = new ArrayList<>();
            StringBuilder plain = new StringBuilder();
            int i = 0;
            while (i < text.length()) {
                if (text.startsWith("**", i)) {
                    int end = text.indexOf("**", i + 2);
                    if (end > 0) {
                        if (plain.length() > 0) {
                            runs.add(new Run(plain.toString(), REGULAR, size, grey));
                            plain.setLength(0);
                        }
                        runs.add(new Run(text.substring(i + 2, end), BOLD, size, grey));
                        i = end + 2;
                        continue;
                    }
                } else if (text.charAt(i) == '`') {
                    int end = text.indexOf('`', i + 1);
                    if (end > 0) {
                        if (plain.length() > 0) {
                            runs.add(new Run(plain.toString(), REGULAR, size, grey));
                            plain.setLength(0);
                        }
                        runs.add(new Run(text.substring(i + 1, end), MONO, size - 0.5f, 0.3f));
                        i = end + 1;
                        continue;
                    }
                } else if (text.charAt(i) == '[') {
                    int close = text.indexOf("](", i);
                    int end = close < 0 ? -1 : text.indexOf(')', close);
                    if (end > 0) {
                        plain.append(text, i + 1, close);
                        i = end + 1;
                        continue;
                    }
                }
                plain.append(text.charAt(i));
                i++;
            }
            if (plain.length() > 0) {
                runs.add(new Run(plain.toString(), REGULAR, size, grey));
            }
            return runs;
        }

        // ───────────────────────── page plumbing ─────────────────────────

        private void emit(Run run, float x) {
            page.append("BT /")
                    .append(run.font)
                    .append(' ')
                    .append(fmt(run.size))
                    .append(" Tf ")
                    .append(fmt(run.grey))
                    .append(" g ")
                    .append(fmt(x))
                    .append(' ')
                    .append(fmt(y))
                    .append(" Td ")
                    .append(literal(run.text))
                    .append(" Tj ET\n");
        }

        private void box(float x, float bottom, float w, float h, float grey) {
            // Drawn behind the text that was already written: prepend it.
            String rect =
                    fmt(grey)
                            + " g "
                            + fmt(x)
                            + ' '
                            + fmt(bottom)
                            + ' '
                            + fmt(w)
                            + ' '
                            + fmt(h)
                            + " re f\n";
            page.insert(codeBlockStart, rect);
        }

        private int codeBlockStart;

        private void need(float space) {
            if (y - space < MARGIN + 24) {
                closePage();
            }
            codeBlockStart = page.length();
        }

        private void closePage() {
            if (page.length() == 0) {
                y = PAGE_H - MARGIN;
                return;
            }
            pageCount++;
            String footer = title + "   ·   " + pageCount;
            page.append("BT /")
                    .append(REGULAR)
                    .append(" 8 Tf 0.6 g ")
                    .append(fmt(MARGIN))
                    .append(' ')
                    .append(fmt(MARGIN - 14))
                    .append(" Td ")
                    .append(literal(footer))
                    .append(" Tj ET\n");

            byte[] content = page.toString().getBytes(StandardCharsets.ISO_8859_1);
            int contentObj = add(stream("", content));
            pageObjects.add(contentObj);
            page = new StringBuilder();
            y = PAGE_H - MARGIN;
        }

        // ───────────────────────── PDF objects ─────────────────────────

        private int add(byte[] object) {
            objects.add(object);
            return objects.size(); // object numbers are 1-based
        }

        private static byte[] stream(String extraDict, byte[] data) {
            byte[] deflated = deflate(data);
            String head =
                    "<< /Length "
                            + deflated.length
                            + " /Filter /FlateDecode "
                            + extraDict
                            + ">>\nstream\n";
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.writeBytes(head.getBytes(StandardCharsets.ISO_8859_1));
            out.writeBytes(deflated);
            out.writeBytes("\nendstream".getBytes(StandardCharsets.ISO_8859_1));
            return out.toByteArray();
        }

        private static byte[] deflate(byte[] data) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
            try (DeflaterOutputStream dos = new DeflaterOutputStream(out, deflater)) {
                dos.write(data);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            } finally {
                deflater.end();
            }
            return out.toByteArray();
        }

        private static BufferedImage scaled(BufferedImage src) {
            if (src.getWidth() <= MAX_IMAGE_PX) {
                return src;
            }
            int w = MAX_IMAGE_PX;
            int h = Math.max(1, src.getHeight() * w / src.getWidth());
            BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = out.createGraphics();
            g.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(src, 0, 0, w, h, null);
            g.dispose();
            return out;
        }

        private int addImage(BufferedImage img) {
            int w = img.getWidth();
            int h = img.getHeight();
            byte[] rgb = new byte[w * h * 3];
            int i = 0;
            for (int yy = 0; yy < h; yy++) {
                for (int xx = 0; xx < w; xx++) {
                    int p = img.getRGB(xx, yy);
                    rgb[i++] = (byte) (p >> 16);
                    rgb[i++] = (byte) (p >> 8);
                    rgb[i++] = (byte) p;
                }
            }
            String dict =
                    "/Type /XObject /Subtype /Image /Width "
                            + w
                            + " /Height "
                            + h
                            + " /ColorSpace /DeviceRGB /BitsPerComponent 8 ";
            return add(stream(dict, rgb));
        }

        byte[] finish() {
            closePage();

            StringBuilder diff = new StringBuilder("[ " + DIFF_START);
            for (String name : DIFFERENCES) {
                diff.append(" /").append(name);
            }
            diff.append(" ]");
            int encoding =
                    add(
                            ("<< /Type /Encoding /BaseEncoding /WinAnsiEncoding "
                                            + "/Differences "
                                            + diff
                                            + " >>")
                                    .getBytes(StandardCharsets.ISO_8859_1));
            int fRegular = font("Helvetica", encoding);
            int fBold = font("Helvetica-Bold", encoding);
            int fMono = font("Courier", encoding);

            StringBuilder xobjects = new StringBuilder();
            for (int i = 0; i < imageNames.size(); i++) {
                xobjects.append('/')
                        .append(imageNames.get(i))
                        .append(' ')
                        .append(imageObjects.get(i))
                        .append(" 0 R ");
            }
            String resources =
                    "<< /Font << /"
                            + REGULAR
                            + ' '
                            + fRegular
                            + " 0 R /"
                            + BOLD
                            + ' '
                            + fBold
                            + " 0 R /"
                            + MONO
                            + ' '
                            + fMono
                            + " 0 R >>"
                            + (xobjects.length() == 0 ? "" : " /XObject << " + xobjects + ">>")
                            + " >>";

            // The pages node has to know its children, and they have to know it,
            // so its number is reserved before the page objects are written.
            int pagesNumber = objects.size() + pageObjects.size() + 1;
            List<Integer> pageRefs = new ArrayList<>();
            for (int contentObj : pageObjects) {
                pageRefs.add(
                        add(
                                ("<< /Type /Page /Parent "
                                                + pagesNumber
                                                + " 0 R "
                                                + "/MediaBox [0 0 "
                                                + (int) PAGE_W
                                                + ' '
                                                + (int) PAGE_H
                                                + "] "
                                                + "/Resources "
                                                + resources
                                                + " /Contents "
                                                + contentObj
                                                + " 0 R >>")
                                        .getBytes(StandardCharsets.ISO_8859_1)));
            }
            StringBuilder kids = new StringBuilder("[ ");
            for (int ref : pageRefs) {
                kids.append(ref).append(" 0 R ");
            }
            kids.append(']');
            int pages =
                    add(
                            ("<< /Type /Pages /Count " + pageRefs.size() + " /Kids " + kids + " >>")
                                    .getBytes(StandardCharsets.ISO_8859_1));
            if (pages != pagesNumber) {
                throw new IllegalStateException(
                        "pages object number drifted: " + pages + " != " + pagesNumber);
            }
            int info =
                    add(
                            ("<< /Title "
                                            + literal(title)
                                            + " /Producer (MdPdf, part of the Navi simple"
                                            + " repository) >>")
                                    .getBytes(StandardCharsets.ISO_8859_1));
            int catalog =
                    add(
                            ("<< /Type /Catalog /Pages " + pages + " 0 R >>")
                                    .getBytes(StandardCharsets.ISO_8859_1));

            return assemble(catalog, info);
        }

        private int font(String name, int encoding) {
            return add(
                    ("<< /Type /Font /Subtype /Type1 /BaseFont /"
                                    + name
                                    + " /Encoding "
                                    + encoding
                                    + " 0 R >>")
                            .getBytes(StandardCharsets.ISO_8859_1));
        }

        private byte[] assemble(int catalog, int info) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            write(out, "%PDF-1.4\n%âãÏÓ\n");
            int[] offsets = new int[objects.size() + 1];
            for (int i = 0; i < objects.size(); i++) {
                offsets[i + 1] = out.size();
                write(out, (i + 1) + " 0 obj\n");
                out.writeBytes(objects.get(i));
                write(out, "\nendobj\n");
            }
            int xref = out.size();
            write(out, "xref\n0 " + (objects.size() + 1) + "\n");
            write(out, "0000000000 65535 f \n");
            for (int i = 1; i <= objects.size(); i++) {
                write(out, String.format("%010d 00000 n %n", offsets[i]).replace("\r", ""));
            }
            write(
                    out,
                    "trailer\n<< /Size "
                            + (objects.size() + 1)
                            + " /Root "
                            + catalog
                            + " 0 R /Info "
                            + info
                            + " 0 R >>\nstartxref\n"
                            + xref
                            + "\n%%EOF\n");
            return out.toByteArray();
        }

        private static void write(OutputStream out, String s) {
            try {
                out.write(s.getBytes(StandardCharsets.ISO_8859_1));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        private static String fmt(float value) {
            if (value == Math.round(value)) {
                return Integer.toString(Math.round(value));
            }
            return String.format(java.util.Locale.US, "%.2f", value);
        }
    }
}
