package com.ramussoft.resources;

import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.Test;

/**
 * Upstream issue #13, "The problem with the localization of the Russian language".
 *
 * <p>
 * Two lookup helpers in this application return <b>null</b> for a key no bundle defines
 * rather than echoing the key back, and their callers do not check. The result reaches the
 * user directly: a dialog with no title, a file filter whose description is blank, and -
 * where the result is concatenated - a label reading the literal word "null".
 *
 * <p>
 * Each helper reads exactly one bundle, which is what makes this checkable. So rather than
 * keep a list of known-bad keys that would rot, the test derives the list from the source
 * every time: every string literal handed to one of those two methods must resolve in that
 * method's own bundle. A new call site with a forgotten key fails here instead of in front
 * of a user.
 *
 * <p>
 * The second test is about Russian specifically. A key present in the base bundle but
 * missing from its Russian sibling does not fail loudly - ResourceBundle walks the parent
 * chain and returns the English text - so an English string simply appears in the middle of
 * a Russian dialog. Since the interface now defaults to Russian whatever the system says,
 * that is the default experience rather than a corner case.
 */
public class ResourceKeysResolveTest {

    /**
     * ResourceLoader.getString reads resources.clasificators and nothing else;
     * GlobalResourcesManager.getString reads com.ramussoft.gui.global and nothing else.
     * Deliberately NOT matched: plugin getString methods, which chain across several
     * bundles, and HTTPParser.RES, which is a third one. The trailing comma-or-paren is
     * what keeps a concatenated prefix out - {@code getString("AttributeType." + key)} is
     * a key built at runtime, not a key that should exist on its own.
     */
    private static final Pattern LOOKUP = Pattern.compile(
            "(ResourceLoader|GlobalResourcesManager)\\s*\\.\\s*getString\\s*\\(\\s*\"([^\"]+)\"\\s*[,)]");

    private static final String CLASIFICATORS =
            "idef0-common/src/main/resources/resources/clasificators";

    private static final String GLOBAL =
            "gui-framework-common/src/main/resources/com/ramussoft/gui/global";

    @Test
    public void everyKeyLookedUpInTheSourceResolves() throws Exception {
        Properties clasificators = bundle(CLASIFICATORS + ".properties");
        Properties global = bundle(GLOBAL + ".properties");

        List<String> unresolved = new ArrayList<>();
        for (Path source : javaSources()) {
            String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
            Matcher m = LOOKUP.matcher(text);
            while (m.find()) {
                String key = m.group(2);
                Properties where = "ResourceLoader".equals(m.group(1)) ? clasificators : global;
                if (key.isEmpty() || where.containsKey(key))
                    continue;
                unresolved.add(key + "  (" + m.group(1) + ", "
                        + repositoryRoot().relativize(source) + ")");
            }
        }
        assertTrue("These keys are looked up in the source and defined in no bundle, so the "
                + "lookup returns null and the user sees an empty title, a blank filter or the "
                + "literal text \"null\":\n  " + String.join("\n  ", unresolved),
                unresolved.isEmpty());
    }

    /**
     * Deliberately not scoped to keys the source looks up by name. Most interface text
     * never goes through getString at all: ResourceLoader.setJComponentsText walks a
     * component tree and uses each component's OWN TEXT as the lookup key, so dozens of
     * keys are reachable only through a {@code setText("SomeKey")} that no pattern can tell
     * apart from a literal caption. Comparing whole bundles sidesteps that entirely, and is
     * a stronger promise anyway: every English string has a Russian one.
     */
    @Test
    public void everyBundleIsFullyTranslatedIntoRussian() throws Exception {
        List<String> untranslated = new ArrayList<>();
        for (Path base : baseBundles()) {
            Path russian = base.resolveSibling(
                    base.getFileName().toString().replace(".properties", "_ru.properties"));
            if (!Files.exists(russian))
                continue;
            Properties english = bundle(base);
            Properties translated = bundle(russian);
            for (String key : english.stringPropertyNames())
                if (!translated.containsKey(key))
                    untranslated.add(key + "  (" + repositoryRoot().relativize(base) + ")");
        }
        assertTrue("These keys exist in English and not in Russian. That does not fail loudly: "
                + "ResourceBundle walks the parent chain and hands back the English text, so the "
                + "string simply appears untranslated in the middle of a Russian dialog - and "
                + "Russian is now everyone's default interface, whatever the system says.\n  "
                + String.join("\n  ", untranslated), untranslated.isEmpty());
    }

    /**
     * Every base bundle that ships, minus the report keyword files.
     *
     * <p>
     * Those four are exempt on purpose, and it is not an oversight to be tidied up later.
     * Both Keywords classes load them wholesale and build a value-to-key map used to parse
     * report queries, and those values are stored inside users' .rsf model files. Giving
     * one of their base-only keys a Russian value would not translate anything - it would
     * add a new token to that map and change how existing models parse.
     */
    private static List<Path> baseBundles() throws IOException {
        List<Path> bundles = new ArrayList<>();
        try (Stream<Path> tree = Files.walk(repositoryRoot())) {
            tree.filter(p -> p.toString().endsWith(".properties"))
                    .filter(p -> p.toString().contains(File.separator + "src" + File.separator + "main"))
                    .filter(p -> !p.getFileName().toString().matches(".*_[a-z]{2}\\.properties"))
                    .filter(p -> !p.getFileName().toString().startsWith("reportgui"))
                    .filter(p -> !p.getFileName().toString().startsWith("print"))
                    .forEach(bundles::add);
        }
        return bundles;
    }

    private static Properties bundle(String relative) throws IOException {
        return bundle(repositoryRoot().resolve(relative));
    }

    private static Properties bundle(Path path) throws IOException {
        Properties properties = new Properties();
        // Properties.load reads ISO-8859-1 and expands \\uXXXX itself, which is exactly how
        // these files are written and exactly how the application reads them.
        try (InputStream in = new FileInputStream(path.toFile())) {
            properties.load(in);
        }
        return properties;
    }

    private static List<Path> javaSources() throws IOException {
        List<Path> sources = new ArrayList<>();
        try (Stream<Path> tree = Files.walk(repositoryRoot())) {
            tree.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> p.toString().contains(File.separator + "src" + File.separator + "main"))
                    .forEach(sources::add);
        }
        return sources;
    }

    /**
     * The working directory is the module, not the repository, and this test has to read
     * every module's sources. Walk up to the settings file that defines them all.
     */
    private static Path repositoryRoot() {
        Path path = new File(".").getAbsoluteFile().toPath().normalize();
        while (path != null && !Files.exists(path.resolve("settings.gradle")))
            path = path.getParent();
        if (path == null)
            throw new IllegalStateException("no settings.gradle above " + new File(".").getAbsolutePath());
        return path;
    }
}
