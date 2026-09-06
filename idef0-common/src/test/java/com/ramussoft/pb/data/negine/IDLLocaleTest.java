package com.ramussoft.pb.data.negine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Locale;
import java.util.StringTokenizer;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.dsoft.pb.types.FloatPoint;
import com.ramussoft.pb.Function;

/**
 * Upstream issue #33, "Не работает экспорт и импорт в IDL".
 *
 * <p>
 * IDL is an interchange format, but the exporter built its number and date formats in the
 * default locale, so what it wrote depended on the machine that wrote it: a comma-decimal
 * machine emitted {@code (0,123;0,456)} where a dot-decimal one emitted
 * {@code (0.123,0.456)}. This fork made that the common case rather than the rare one, by
 * defaulting the interface to Russian whatever the system says.
 *
 * <p>
 * Note what is NOT claimed here. Ramus reads both spellings, so a file exported by one
 * build and imported by another has always worked; the round-trip case below documents
 * that and guards it. What was broken is portability to anything that is not Ramus.
 */
public class IDLLocaleTest {

    /**
     * Rounding budget. toCoortinate divides by the page size, casts to float and keeps
     * three fraction digits, so half a unit in the last digit is the most the round trip
     * can preserve.
     */
    private static final double X_TOLERANCE = 820 * 0.0005 + 0.001;

    private static final double Y_TOLERANCE = 440 * 0.0005 + 0.001;

    private Locale original;

    @Before
    public void rememberLocale() {
        original = Locale.getDefault();
    }

    @After
    public void restoreLocale() {
        Locale.setDefault(original);
    }

    /**
     * The regression guard. Before the fix these two strings differed - a comma where the
     * other had a dot, and a semicolon where the other had a comma.
     */
    @Test
    public void whatIsWrittenDoesNotDependOnTheDefaultLocale() {
        Locale.setDefault(new Locale("ru", "RU"));
        String underRussian = exporter().toCoortinate(123.4, 234.5);

        Locale.setDefault(Locale.US);
        String underEnglish = exporter().toCoortinate(123.4, 234.5);

        assertEquals("IDL is an interchange format; a machine's locale must not change "
                + "what it writes.", underEnglish, underRussian);
        assertFalse("A comma-decimal locale must no longer produce the semicolon-separated "
                + "spelling: " + underRussian, underRussian.indexOf(';') >= 0);
    }

    /**
     * The date is written but never read back, so this only has to be stable and Latin -
     * a locale with its own digits would otherwise put them in the file.
     */
    @Test
    public void theCreationDateDoesNotDependOnTheDefaultLocale() {
        java.util.Date fixed = new java.util.Date(0L);

        Locale.setDefault(new Locale("ar", "EG"));
        String underArabic = new IDL(null, "UTF-8").format.format(fixed);

        Locale.setDefault(Locale.US);
        assertEquals(new IDL(null, "UTF-8").format.format(fixed), underArabic);
    }

    /**
     * The importer must still read what the exporter writes, in either locale - this is
     * the half that was never broken and must not become broken by pinning the format.
     */
    @Test
    public void theImporterReadsWhatTheExporterWrites() {
        assertRoundTrip(new Locale("ru", "RU"), 123.4, 234.5);
        assertRoundTrip(Locale.US, 123.4, 234.5);
        assertRoundTrip(new Locale("ru", "RU"), 0.0, 0.0);
        assertRoundTrip(Locale.US, 819.0, 439.0);
    }

    /**
     * The case that was reported to me as broken, and is not.
     *
     * <p>
     * The claim was that a pair whose coordinates both format as whole numbers is written
     * as {@code (1,1)} and read back as a NumberFormatException, because the separator only
     * becomes a semicolon once a comma is already present. It does not happen: every caller
     * tokenises on " ()" before toPoint sees the string, so the parentheses never reach it
     * and "1,1" parses. This pins that, because it is the kind of thing that would be
     * "fixed" twice otherwise.
     */
    @Test
    public void aPairOfWholeNumberCoordinatesSurvivesTheRoundTrip() {
        // Chosen so both coordinates land exactly on 1 after the page-size division and the
        // fixed offset: 811.8/820 + 0.01 and 433.4/440 + 0.015.
        Locale.setDefault(Locale.US);
        assertEquals("(1,1)", exporter().toCoortinate(811.8, 433.4));
        assertRoundTrip(Locale.US, 811.8, 433.4);
        assertRoundTrip(new Locale("ru", "RU"), 811.8, 433.4);
    }

    /**
     * And it must still read the comma-decimal spelling older builds wrote, because those
     * files exist.
     */
    @Test
    public void theImporterStillReadsTheOldCommaDecimalSpelling() {
        Locale.setDefault(Locale.US);
        FloatPoint point = importer().toPoint("0,163;0,548");

        assertEquals((0.163 - 0.01) * 820, point.getX(), X_TOLERANCE);
        assertEquals((0.548 - 0.015) * 440, point.getY(), Y_TOLERANCE);
    }

    private void assertRoundTrip(Locale locale, double x, double y) {
        Locale.setDefault(locale);
        String written = exporter().toCoortinate(x, y);

        // The importer never hands toPoint the parentheses: every call site tokenises on
        // " ()" first. Do the same here so the test exercises the real input.
        String token = new StringTokenizer(written, " ()").nextToken();
        FloatPoint read = importer().toPoint(token);

        assertEquals(locale + " wrote " + written, x, read.getX(), X_TOLERANCE);
        assertEquals(locale + " wrote " + written, y, read.getY(), Y_TOLERANCE);
    }

    private static IDLExporter exporter() {
        return new IDLExporter(null, null, "UTF-8");
    }

    private static IDLImporter importer() {
        // The constructor puts base into a Hashtable, which rejects null values.
        return new IDLImporter(null, stubFunction(), "UTF-8", null);
    }

    private static Function stubFunction() {
        return (Function) Proxy.newProxyInstance(Function.class.getClassLoader(),
                new Class<?>[]{Function.class}, new InvocationHandler() {

                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        Class<?> type = method.getReturnType();
                        if (!type.isPrimitive())
                            return null;
                        if (type == boolean.class)
                            return Boolean.FALSE;
                        if (type == void.class)
                            return null;
                        return Integer.valueOf(0);
                    }
                });
    }
}
