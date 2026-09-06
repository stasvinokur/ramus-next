package com.ramussoft.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.ramussoft.common.Attribute;
import com.ramussoft.common.Element;
import com.ramussoft.common.Engine;
import com.ramussoft.common.Qualifier;

/**
 * Naming an element of a catalog that has nowhere to put a name.
 *
 * <p>
 * An element's name is not a field - it is whichever attribute the catalog nominates as its
 * name - and a catalog can nominate none. The application never makes one like that: a plugin
 * watching for new qualifiers gives each the standard name attribute. But it only does so when
 * the file carries the registration that plugin reads, and a file made before this server
 * learned to write that registration carries none. So its catalogs cannot be named, and an
 * agent was handed an error it could do nothing about.
 */
public class CatalogNameTest {

    private static String realUserHome;

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @BeforeClass
    public static void redirectTheSettingsDirectory() throws Exception {
        realUserHome = System.getProperty("user.home");
        System.setProperty("user.home",
                Files.createTempDirectory("ramus-mcp-home").toString());
    }

    @AfterClass
    public static void restoreTheSettingsDirectory() {
        if (realUserHome != null)
            System.setProperty("user.home", realUserHome);
    }

    @Test
    public void anElementCanBeNamedInACatalogThatHadNoAttributeForNames() throws Exception {
        File file = new File(folder.getRoot(), "catalog.rsf");
        long catalogId;
        long elementId;

        try (ModelSession session = ModelSession.createNew(file)) {
            Engine engine = session.getEngine();

            // A catalog exactly as an older file holds one: created without the registration
            // that would have given it a name attribute.
            Qualifier catalog = engine.createQualifier();
            catalog.setName("Проба");
            catalog.getAttributes().clear();
            catalog.setAttributeForName(-1L);
            engine.updateQualifier(catalog);
            catalogId = catalog.getId();
            assertEquals("the catalog starts with nowhere to put a name",
                    -1L, engine.getQualifier(catalogId).getAttributeForName());

            Element element = engine.createElement(catalogId);
            elementId = element.getId();
            session.markChanged();
            // What create_element does, through the same call.
            WriteTools.nameFor(session, engine, engine.getQualifier(catalogId), element,
                    "Первый");
            session.save();
        }

        try (ModelSession session = new ModelSession(file, true)) {
            Engine engine = session.getEngine();
            Qualifier catalog = engine.getQualifier(catalogId);
            long forName = catalog.getAttributeForName();
            assertTrue("the catalog was given an attribute to hold names", forName > 0);

            Attribute name = null;
            List<Attribute> attributes = catalog.getAttributes();
            for (Attribute a : attributes)
                if (a.getId() == forName)
                    name = a;
            assertTrue("and it is one of its own attributes", name != null);

            assertEquals("the name survives being saved and reopened",
                    "Первый", engine.getAttribute(engine.getElement(elementId), name));
        }
    }
}
