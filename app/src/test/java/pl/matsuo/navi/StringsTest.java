package pl.matsuo.navi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.Test;

/**
 * Guards the resource files themselves.
 *
 * <p>Version 1.6 shipped a notification text whose placeholders had been flattened from {@code %1$s
 * · %2$s} to {@code %1 · %2}, and the app crashed the moment it tried to format it. Nothing in a
 * compiler catches that, so it is caught here, together with missing translations and dead strings.
 */
public class StringsTest {

    /** Deliberately the same in every language. */
    private static final Set<String> UNTRANSLATED =
            new HashSet<>(
                    Arrays.asList(
                            "app_name", // a proper noun
                            "tile_music", // the Spotify brand
                            "clock_date_format", // a SimpleDateFormat pattern
                            "notif_text", // placeholders and separators only
                            "lang_en", // every language name is in its own language
                            "lang_pl"));

    /** Used from somewhere other than Java code. */
    private static final Set<String> USED_ELSEWHERE = new HashSet<>(Arrays.asList("app_name"));

    private static final Pattern STRING =
            Pattern.compile("<string name=\"([a-z_0-9]+)\"[^>]*>(.*?)</string>", Pattern.DOTALL);
    private static final Pattern PLACEHOLDER = Pattern.compile("%(\\d+\\$[sdf]|.)");

    private static File moduleDir() {
        File app = new File("src/main/res");
        return app.exists() ? new File(".") : new File("app");
    }

    private static Map<String, String> strings(String qualifier) throws IOException {
        File f = new File(moduleDir(), "src/main/res/" + qualifier + "/strings.xml");
        assertTrue("missing " + f.getAbsolutePath(), f.exists());
        String xml = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        Map<String, String> out = new LinkedHashMap<>();
        Matcher m = STRING.matcher(xml);
        while (m.find()) {
            assertFalse("duplicate key " + m.group(1), out.containsKey(m.group(1)));
            out.put(m.group(1), m.group(2));
        }
        assertFalse("no strings found in " + f, out.isEmpty());
        return out;
    }

    /** The placeholders a format string uses, e.g. [1$s, 2$s]. */
    private static List<String> placeholders(String value) {
        List<String> out = new ArrayList<>();
        Matcher m = PLACEHOLDER.matcher(value);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    // ───────────────────────────── format strings ─────────────────────────────

    @Test
    public void everyPlaceholderIsPositional() throws IOException {
        for (String qualifier : new String[] {"values", "values-pl"}) {
            for (Map.Entry<String, String> e : strings(qualifier).entrySet()) {
                for (String p : placeholders(e.getValue())) {
                    assertTrue(
                            qualifier
                                    + "/"
                                    + e.getKey()
                                    + " uses \"%"
                                    + p
                                    + "\"; Android needs the positional form, e.g. %1$s",
                            p.matches("\\d+\\$[sdf]") || p.equals("%"));
                }
            }
        }
    }

    @Test
    public void everyFormatStringCanActuallyBeFormatted() throws IOException {
        // The exact failure that crashed 1.6: String.format throws on a broken
        // conversion, inside a foreground service, before any screen is drawn.
        for (String qualifier : new String[] {"values", "values-pl"}) {
            for (Map.Entry<String, String> e : strings(qualifier).entrySet()) {
                List<String> ps = placeholders(e.getValue());
                if (ps.isEmpty()) {
                    continue;
                }
                Object[] args = new Object[ps.size()];
                for (int i = 0; i < args.length; i++) {
                    args[i] = ps.get(i).endsWith("s") ? "x" : 1;
                }
                String formatted = String.format(Locale.US, e.getValue(), args);
                assertFalse(qualifier + "/" + e.getKey(), formatted.contains("%"));
            }
        }
    }

    @Test
    public void translationsUseTheSamePlaceholdersAsTheOriginal() throws IOException {
        Map<String, String> en = strings("values");
        Map<String, String> pl = strings("values-pl");
        for (Map.Entry<String, String> e : en.entrySet()) {
            String translated = pl.get(e.getKey());
            if (translated == null) {
                continue;
            }
            assertEquals(
                    "placeholders differ for " + e.getKey(),
                    new TreeSet<>(placeholders(e.getValue())),
                    new TreeSet<>(placeholders(translated)));
        }
    }

    // ───────────────────────────── completeness ─────────────────────────────

    @Test
    public void everyStringIsTranslatedUnlessItIsDeliberatelyNot() throws IOException {
        Set<String> missing = new TreeSet<>(strings("values").keySet());
        missing.removeAll(strings("values-pl").keySet());
        missing.removeAll(UNTRANSLATED);
        assertTrue("no Polish translation for " + missing, missing.isEmpty());
    }

    @Test
    public void thePolishFileHasNoStringsOfItsOwn() throws IOException {
        Set<String> extra = new TreeSet<>(strings("values-pl").keySet());
        extra.removeAll(strings("values").keySet());
        assertTrue("only in values-pl: " + extra, extra.isEmpty());
    }

    @Test
    public void theUntranslatedListIsNotStale() throws IOException {
        Set<String> en = strings("values").keySet();
        Set<String> pl = strings("values-pl").keySet();
        for (String key : UNTRANSLATED) {
            assertTrue("unknown key on the untranslated list: " + key, en.contains(key));
            assertFalse(
                    "key " + key + " is translated after all; drop it from the list",
                    pl.contains(key));
        }
    }

    @Test
    public void noStringIsEmpty() throws IOException {
        for (String qualifier : new String[] {"values", "values-pl"}) {
            for (Map.Entry<String, String> e : strings(qualifier).entrySet()) {
                assertFalse(
                        qualifier + "/" + e.getKey() + " is empty", e.getValue().trim().isEmpty());
            }
        }
    }

    // ───────────────────────────── use and disuse ─────────────────────────────

    private static Set<String> referencedKeys() throws IOException {
        Set<String> used = new HashSet<>();
        // Only the shipped sources count: a string that just happens to be
        // referenced from a test is still dead weight in the app.
        File src = new File(moduleDir(), "src/main");
        // Not android.R.string.ok and friends: those come from the platform.
        Pattern java = Pattern.compile("(?<!android\\.)R\\.string\\.([a-z_0-9]+)");
        Pattern xml = Pattern.compile("@string/([a-z_0-9]+)");
        try (Stream<Path> paths = Files.walk(src.toPath())) {
            for (Path p : (Iterable<Path>) paths.filter(Files::isRegularFile)::iterator) {
                String name = p.getFileName().toString();
                if (!name.endsWith(".java") && !name.endsWith(".xml")) {
                    continue;
                }
                if (name.equals("strings.xml")) {
                    continue;
                }
                String text = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                Matcher m = java.matcher(text);
                while (m.find()) {
                    used.add(m.group(1));
                }
                m = xml.matcher(text);
                while (m.find()) {
                    used.add(m.group(1));
                }
            }
        }
        return used;
    }

    @Test
    public void everyDefinedStringIsUsedSomewhere() throws IOException {
        Set<String> unused = new TreeSet<>(strings("values").keySet());
        unused.removeAll(referencedKeys());
        unused.removeAll(USED_ELSEWHERE);
        assertTrue("strings nothing refers to: " + unused, unused.isEmpty());
    }

    @Test
    public void everyReferencedStringIsDefined() throws IOException {
        Set<String> undefined = new TreeSet<>(referencedKeys());
        undefined.removeAll(strings("values").keySet());
        assertTrue("referenced but not defined: " + undefined, undefined.isEmpty());
    }
}
