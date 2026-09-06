package com.ramussoft.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.ramussoft.common.Attribute;
import com.ramussoft.common.Engine;
import com.ramussoft.common.Qualifier;
import com.ramussoft.core.attribute.standard.StandardAttributesPlugin;
import com.ramussoft.database.common.Row;
import com.ramussoft.database.common.RowSet;
import com.ramussoft.idef0.IDEF0Plugin;

/**
 * Renaming a model, in both of the places its name is kept.
 *
 * <p>
 * The name is on the qualifier AND on a row of the F_MODEL_TREE catalog, which is what the
 * application's Models panel lists. Setting the qualifier alone leaves the panel showing the
 * old name for ever - the file is right and the interface is wrong, which is the hardest kind
 * of wrong to notice. So the row is what gets written, and a listener carries it the other
 * way; this test is here to catch that listener going away.
 */
public class RenameModelTest {

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
    public void aRenamedModelIsRenamedInTheModelsPanelToo() throws Exception {
        File file = new File(folder.getRoot(), "named.rsf");

        try (ModelSession session = ModelSession.createNew(file)) {
            session.addModel("Черновик", -1);
            TitleTools.renameModel(session, Map.of("name", "Формирование ТП"));
            session.save();
        }

        // A fresh session, because a name that only exists in the session that set it is not
        // a rename.
        try (ModelSession session = new ModelSession(file, true)) {
            Engine engine = session.getEngine();
            List<Qualifier> models = IDEF0Plugin.getBaseQualifiers(engine);
            assertEquals(1, models.size());
            assertEquals("the model itself", "Формирование ТП", models.get(0).getName());
            assertEquals("and the row the Models panel reads",
                    List.of("Формирование ТП"), modelTreeNames(engine));
        }
    }

    /** The answer says what the model is called now, read back rather than echoed. */
    @Test
    public void theAnswerReportsTheNameThatArrived() throws Exception {
        try (ModelSession session = ModelSession.createNew(
                new File(folder.getRoot(), "answer.rsf"))) {
            session.addModel("Черновик", -1);
            @SuppressWarnings("unchecked")
            Map<String, Object> answer = (Map<String, Object>)
                    TitleTools.renameModel(session, Map.of("name", "Готовая модель"));
            assertEquals("Готовая модель", answer.get("model"));
            assertEquals("Черновик", answer.get("was"));
        }
    }

    /** An empty name would leave a model that cannot be named to any other tool. */
    @Test
    public void anEmptyNameIsRefused() throws Exception {
        try (ModelSession session = ModelSession.createNew(
                new File(folder.getRoot(), "empty.rsf"))) {
            session.addModel("Черновик", -1);
            try {
                TitleTools.renameModel(session, Map.of("name", "   "));
                org.junit.Assert.fail("an empty name must be refused");
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage(),
                        expected.getMessage().contains("needs a name"));
            }
        }
    }

    private List<String> modelTreeNames(Engine engine) {
        Attribute link = StandardAttributesPlugin.getAttributeQualifierId(engine);
        RowSet rows = new RowSet(engine, IDEF0Plugin.getModelTree(engine),
                new Attribute[]{link});
        try {
            List<String> names = new ArrayList<>();
            for (Row row : rows.getAllRows())
                names.add(row.getName());
            return names;
        } finally {
            rows.close();
        }
    }
}
