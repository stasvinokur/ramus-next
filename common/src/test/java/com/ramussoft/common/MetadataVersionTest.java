package com.ramussoft.common;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Properties;

import org.junit.Test;

/**
 * The application version is declared twice, and nothing used to notice when the two drifted.
 *
 * <p>{@code Metadata.APPLICATION_VERSION} is what the About panel shows, what goes into every
 * saved model and what the update check sends. The Gradle {@code version} is what jpackage
 * stamps into the macOS bundle and the Windows product, and what names the installer files.
 * They are not derived from one another - they agree because someone edits both.
 */
public class MetadataVersionTest {

    /** The test runs with the module directory as its working directory. */
    private static Path repositoryRoot() {
        Path here = new File("").getAbsoluteFile().toPath();
        return here.getFileName().toString().equals("common") ? here.getParent() : here;
    }

    private static String gradleVersion() throws Exception {
        Properties properties = new Properties();
        try (InputStream in = new FileInputStream(
                repositoryRoot().resolve("gradle.properties").toFile())) {
            properties.load(in);
        }
        return properties.getProperty("version");
    }

    @Test
    public void bothDeclarationsOfTheVersionAgree() throws Exception {
        assertEquals("gradle.properties and Metadata disagree about the application version; "
                        + "the About panel and the installer would report different numbers",
                gradleVersion(), Metadata.getApplicationVersion());
    }

    /**
     * jpackage will not take anything else: macOS rejects a major of 0, Windows rejects a
     * fourth component. The build script silently substitutes 1.0.0 for a value it cannot
     * parse, so a bad version here would ship as 1.0.0 rather than fail.
     */
    @Test
    public void theVersionIsShapedTheWayJpackageDemands() throws Exception {
        String version = gradleVersion();
        assertTrue("not MAJOR.MINOR.PATCH: " + version, version.matches("\\d+\\.\\d+\\.\\d+"));
        assertFalse("the major component must be greater than zero for a macOS bundle: "
                + version, version.startsWith("0."));
    }

    /**
     * The file-compatibility gate compares the minimum stored inside a model against the
     * application version, and refuses the file when the application is older. So the version
     * may never drop below the minimum this build writes into the models it saves - otherwise
     * the application stops being able to open its own output. This is not hypothetical: it is
     * what ruled out numbering this release 1.0.0.
     */
    @Test
    public void theVersionIsNotOlderThanTheFilesThisBuildWrites() {
        String[] application = Metadata.getApplicationVersion().split("\\.");
        String[] minimum = Metadata.getFileOpenMinimumVersion().split("\\.");
        for (int i = 0; i < minimum.length; i++) {
            assertTrue("the application version has fewer parts than the file minimum, which "
                            + "the comparison treats as older", i < application.length);
            int minimumPart = Integer.parseInt(minimum[i]);
            int applicationPart = Integer.parseInt(application[i]);
            if (applicationPart != minimumPart) {
                assertTrue("application version " + Metadata.getApplicationVersion()
                        + " is older than the minimum it stamps into saved models, "
                        + Metadata.getFileOpenMinimumVersion()
                        + " - this build could not open its own files",
                        applicationPart > minimumPart);
                return;
            }
        }
    }
}
