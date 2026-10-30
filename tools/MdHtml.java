import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the user guide Markdown into one self-contained HTML page.
 *
 * <p>Pictures are inlined as data URIs, so the result is a single file that can be mailed, dropped
 * on a USB stick or opened on the head unit without the previews folder beside it.
 *
 * <pre>
 * java tools/MdHtml.java docs/USER_GUIDE.md docs/navi-user-guide.html
 * </pre>
 *
 * <p>It understands the same Markdown subset as {@link MdPdf}: headings, paragraphs, bulleted and
 * numbered lists, fenced code, pipe tables, horizontal rules, block quotes, images with a caption,
 * and inline {@code **bold**}, {@code `code`} and links.
 */
public final class MdHtml {

    private MdHtml() {}

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("usage: MdHtml <input.md> <output.html>");
            System.exit(2);
        }
        Path in = Paths.get(args[0]);
        Path out = Paths.get(args[1]);
        String markdown = new String(Files.readAllBytes(in), StandardCharsets.UTF_8);

        String body = new MdHtml.Renderer(in.toAbsolutePath().getParent()).render(markdown);
        String page = PAGE.replace("{{title}}", escape(title(markdown))).replace("{{body}}", body);
        Files.createDirectories(out.toAbsolutePath().getParent());
        Files.write(out, page.getBytes(StandardCharsets.UTF_8));
        System.out.printf("%s: %d KB%n", out, page.getBytes(StandardCharsets.UTF_8).length / 1024);
    }

    private static String title(String markdown) {
        for (String line : markdown.split("\n")) {
            if (line.startsWith("# ")) {
                return line.substring(2).trim();
            }
        }
        return "User guide";
    }

    static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** The page around the rendered Markdown: dark like the app, readable on a phone. */
    private static final String PAGE =
            "<!DOCTYPE html>\n"
                + "<html lang=\"en\">\n"
                + "<head>\n"
                + "<meta charset=\"utf-8\">\n"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
                + "<title>{{title}}</title>\n"
                + "<style>\n"
                + ":root { color-scheme: dark; }\n"
                + "body { background: #0b0b0b; color: #ccc; margin: 0 auto; max-width: 820px;\n"
                + "       padding: 24px 16px 64px; line-height: 1.65;\n"
                + "       font-family: -apple-system, system-ui, Segoe UI, Roboto, sans-serif; }\n"
                + "h1 { font-size: 28px; margin: 48px 0 8px; color: #eee;\n"
                + "     border-bottom: 1px solid #262626; padding-bottom: 10px; }\n"
                + "h1:first-child { margin-top: 8px; }\n"
                + "h2 { font-size: 20px; margin: 36px 0 6px; color: #e0e0e0; }\n"
                + "h3 { font-size: 16px; margin: 24px 0 4px; color: #d0d0d0; }\n"
                + "p, li { font-size: 16px; }\n"
                + "a { color: #8ab4f8; }\n"
                + "figure { margin: 24px 0; }\n"
                + "img { width: 100%; height: auto; display: block; border-radius: 8px;\n"
                + "      border: 1px solid #222; background: #000; }\n"
                + "figcaption { color: #777; font-size: 13px; text-align: center;\n"
                + "             margin-top: 8px; }\n"
                + "table { border-collapse: collapse; width: 100%; margin: 20px 0;\n"
                + "        font-size: 15px; }\n"
                + "th, td { text-align: left; padding: 9px 12px;\n"
                + "         border-bottom: 1px solid #222; vertical-align: top; }\n"
                + "th { color: #eee; border-bottom: 1px solid #3a3a3a; }\n"
                + "code { background: #171717; border: 1px solid #262626; border-radius: 4px;\n"
                + "       padding: 1px 5px; font-size: 90%; color: #d7ba7d; }\n"
                + "pre { background: #141414; border: 1px solid #242424; border-radius: 8px;\n"
                + "      padding: 14px 16px; overflow-x: auto; }\n"
                + "pre code { background: none; border: 0; padding: 0; color: #bdbdbd; }\n"
                + "blockquote { margin: 18px 0; padding: 2px 16px; color: #9a9a9a;\n"
                + "             border-left: 3px solid #D4A600; background: #121212; }\n"
                + "hr { border: 0; border-top: 1px solid #222; margin: 36px 0; }\n"
                + "strong { color: #e8e8e8; }\n"
                + "</style>\n"
                + "</head>\n"
                + "<body>\n"
                + "{{body}}</body>\n"
                + "</html>\n";

    /** Markdown to HTML, one line at a time. */
    static final class Renderer {

        private final Path base;
        private final StringBuilder out = new StringBuilder();

        Renderer(Path base) {
            this.base = base;
        }

        String render(String markdown) throws IOException {
            String[] lines = markdown.replace("\r\n", "\n").split("\n", -1);
            List<String> paragraph = new ArrayList<>();
            List<String> code = new ArrayList<>();
            List<String[]> table = new ArrayList<>();
            List<String> item = new ArrayList<>();
            String listTag = null;
            boolean inCode = false;

            for (String raw : lines) {
                String line = raw.stripTrailing();

                if (line.startsWith("```")) {
                    flushParagraph(paragraph);
                    if (inCode) {
                        out.append("<pre><code>")
                                .append(escape(String.join("\n", code)))
                                .append("</code></pre>\n");
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
                    flushParagraph(paragraph);
                    listTag = closeList(item, listTag);
                    if (!line.replace("|", "").replace("-", "").replace(":", "").isBlank()) {
                        table.add(cells(line));
                    }
                    continue;
                }
                if (!table.isEmpty()) {
                    table(table);
                    table.clear();
                }

                boolean bullet = line.startsWith("- ") || line.startsWith("* ");
                boolean numbered = line.matches("\\d+\\. .*");

                if (line.isBlank()) {
                    flushParagraph(paragraph);
                    listTag = closeList(item, listTag);
                } else if (line.startsWith("#")) {
                    flushParagraph(paragraph);
                    listTag = closeList(item, listTag);
                    int level = 0;
                    while (level < line.length() && line.charAt(level) == '#') {
                        level++;
                    }
                    level = Math.min(level, 6);
                    out.append("<h")
                            .append(level)
                            .append('>')
                            .append(inline(line.substring(level).trim()))
                            .append("</h")
                            .append(level)
                            .append(">\n");
                } else if (line.startsWith("![")) {
                    flushParagraph(paragraph);
                    listTag = closeList(item, listTag);
                    image(line);
                } else if (line.equals("---") || line.equals("***")) {
                    flushParagraph(paragraph);
                    listTag = closeList(item, listTag);
                    out.append("<hr>\n");
                } else if (bullet || numbered) {
                    flushParagraph(paragraph);
                    String wanted = bullet ? "ul" : "ol";
                    if (!wanted.equals(listTag)) {
                        listTag = closeList(item, listTag);
                        out.append('<').append(wanted).append(">\n");
                        listTag = wanted;
                    } else {
                        flushItem(item);
                    }
                    item.add(line.substring(bullet ? 2 : line.indexOf('.') + 2).trim());
                } else if (!item.isEmpty() && raw.startsWith("  ")) {
                    item.add(line.trim()); // a lazy continuation of the item above
                } else if (line.startsWith(">")) {
                    flushParagraph(paragraph);
                    listTag = closeList(item, listTag);
                    out.append("<blockquote><p>")
                            .append(inline(line.substring(1).trim()))
                            .append("</p></blockquote>\n");
                } else {
                    listTag = closeList(item, listTag);
                    paragraph.add(line.trim());
                }
            }
            flushParagraph(paragraph);
            closeList(item, listTag);
            if (!table.isEmpty()) {
                table(table);
            }
            return out.toString();
        }

        private void flushParagraph(List<String> paragraph) {
            if (paragraph.isEmpty()) {
                return;
            }
            out.append("<p>").append(inline(String.join(" ", paragraph))).append("</p>\n");
            paragraph.clear();
        }

        private void flushItem(List<String> item) {
            if (item.isEmpty()) {
                return;
            }
            out.append("<li>").append(inline(String.join(" ", item))).append("</li>\n");
            item.clear();
        }

        /** Closes the open list, if any, and returns the new (absent) list tag. */
        private String closeList(List<String> item, String listTag) {
            if (listTag == null) {
                return null;
            }
            flushItem(item);
            out.append("</").append(listTag).append(">\n");
            return null;
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

        private void table(List<String[]> rows) {
            out.append("<table>\n");
            for (int r = 0; r < rows.size(); r++) {
                String tag = r == 0 ? "th" : "td";
                out.append("<tr>");
                for (String cell : rows.get(r)) {
                    out.append('<')
                            .append(tag)
                            .append('>')
                            .append(inline(cell))
                            .append("</")
                            .append(tag)
                            .append('>');
                }
                out.append("</tr>\n");
            }
            out.append("</table>\n");
        }

        private void image(String line) throws IOException {
            Matcher m = Pattern.compile("!\\[([^\\]]*)\\]\\(([^)]*)\\)").matcher(line);
            if (!m.find()) {
                return;
            }
            String alt = m.group(1);
            Path file = base.resolve(m.group(2));
            if (!Files.exists(file)) {
                System.err.println("missing image: " + file);
                return;
            }
            String name = file.getFileName().toString().toLowerCase();
            String type = name.endsWith(".jpg") || name.endsWith(".jpeg") ? "jpeg" : "png";
            String data = Base64.getEncoder().encodeToString(Files.readAllBytes(file));
            out.append("<figure><img alt=\"")
                    .append(escape(alt))
                    .append("\" src=\"data:image/")
                    .append(type)
                    .append(";base64,")
                    .append(data)
                    .append("\">");
            if (!alt.isBlank()) {
                out.append("<figcaption>").append(escape(alt)).append("</figcaption>");
            }
            out.append("</figure>\n");
        }

        /** Inline markup: images are dropped, links kept, then bold and code spans. */
        private static String inline(String text) {
            String s = escape(text.replaceAll("!\\[[^\\]]*\\]\\([^)]*\\)", ""));
            StringBuilder sb = new StringBuilder();
            Matcher link = Pattern.compile("\\[([^\\]]*)\\]\\(([^)]*)\\)").matcher(s);
            while (link.find()) {
                link.appendReplacement(
                        sb,
                        Matcher.quoteReplacement(
                                "<a href=\"" + link.group(2) + "\">" + link.group(1) + "</a>"));
            }
            link.appendTail(sb);
            s = sb.toString();

            s = replacePairs(s, "`", "<code>", "</code>");
            s = replacePairs(s, "**", "<strong>", "</strong>");
            return s;
        }

        /** Replaces matched pairs of a marker with an opening and a closing tag. */
        private static String replacePairs(String s, String marker, String open, String close) {
            StringBuilder sb = new StringBuilder();
            int i = 0;
            boolean openNow = true;
            while (true) {
                int at = s.indexOf(marker, i);
                if (at < 0 || s.indexOf(marker, at + marker.length()) < 0 && openNow) {
                    sb.append(s, i, s.length());
                    return sb.toString();
                }
                sb.append(s, i, at).append(openNow ? open : close);
                openNow = !openNow;
                i = at + marker.length();
            }
        }
    }
}
