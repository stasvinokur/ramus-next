package com.ramussoft.pb.data.negine;

import java.awt.Color;
import java.awt.Font;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.Vector;

public class IDL {

    protected static final double HEIGHT = 440;

    protected static final double WIDTH = 820;

    protected static final float Y_ADD = 0.015f;

    protected static final float X_ADD = 0.01f;

    protected final NDataPlugin dataPlugin;

    protected Color[] COLORS = new Color[]{Color.red, Color.green,
            Color.blue, new Color(0, 255, 255), Color.magenta, Color.yellow,
            Color.white, Color.black, Color.pink, new Color(175, 255, 175),
            new Color(175, 175, 255), new Color(64, 175, 175),
            new Color(255, 175, 255), new Color(255, 255, 175),
            new Color(64, 64, 64), new Color(175, 175, 175)};

    /**
     * IDL is an interchange format, so what is written must not depend on the machine that
     * writes it. Both of these used to be built in the default locale, which meant a
     * comma-decimal machine emitted coordinates as {@code (0,123;0,456)} where a
     * dot-decimal one emitted {@code (0.123,0.456)} - and once the interface language
     * defaulted to Russian regardless of the system, the comma form became the common
     * case. Our own importer copes with both spellings and continues to, see
     * {@code IDLImporter.toPoint}; another tool reading IDL has no reason to.
     */
    protected SimpleDateFormat format = new SimpleDateFormat("d/M/yyyy", Locale.ENGLISH);

    protected NumberFormat numberFormat = coordinateFormat();

    private static NumberFormat coordinateFormat() {
        NumberFormat format = NumberFormat.getNumberInstance(Locale.ENGLISH);
        // A grouping separator would put a comma inside a single coordinate, where the
        // reader expects a comma to separate the pair. Coordinates never reach 1000 today,
        // so this changes no output; it just removes the way it could.
        format.setGroupingUsed(false);
        return format;
    }

    protected Vector<Font> uniqueFonts = new Vector<Font>();

    protected final String encoding;

    public IDL(NDataPlugin dataPlugin, String encoding) {
        this.dataPlugin = dataPlugin;
        this.encoding = encoding;
    }

    protected void addFont(Font font) {
        if (font != null)
            if (uniqueFonts.indexOf(font) < 0) {
                uniqueFonts.add(font);
            }
    }
}
