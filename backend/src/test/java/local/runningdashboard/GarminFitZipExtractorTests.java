package local.runningdashboard;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class GarminFitZipExtractorTests {
    private final byte[] fit = readFit();

    @Test
    void returnsTheSingleFitEntryFromZip() throws Exception {
        assertArrayEquals(fit, new GarminFitZipExtractor(100_000, 100_000).extract(zip("activity.fit", fit)));
    }

    @Test
    void rejectsArchivesWithoutAFitFile() throws Exception {
        var failure = assertThrows(GarminClientException.class,
                () -> new GarminFitZipExtractor(100_000, 100_000).extract(zip("notes.txt", new byte[] {1})));
        assertEquals("NO_FIT_FILE", failure.code());
    }

    @Test
    void rejectsAmbiguousFitFilesAndUnsafeNames() throws Exception {
        var extractor = new GarminFitZipExtractor(100_000, 100_000);
        assertEquals("AMBIGUOUS_FIT_FILES", assertThrows(GarminClientException.class,
                () -> extractor.extract(zip("a.fit", fit, "b.fit", fit))).code());
        assertEquals("INVALID_ARCHIVE", assertThrows(GarminClientException.class,
                () -> extractor.extract(zip("../run.fit", fit))).code());
    }

    @Test
    void enforcesArchiveAndExpandedSizeLimits() throws Exception {
        var extractor = new GarminFitZipExtractor(100_000, fit.length - 1);
        assertEquals("FILE_TOO_LARGE", assertThrows(GarminClientException.class,
                () -> extractor.extract(zip("activity.fit", fit))).code());
        assertEquals("ZIP_TOO_LARGE", assertThrows(GarminClientException.class,
                () -> new GarminFitZipExtractor(1, 100_000).extract(zip("activity.fit", fit))).code());
    }

    private static byte[] zip(Object... nameAndContents) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var output = new ZipOutputStream(bytes)) {
            for (int i = 0; i < nameAndContents.length; i += 2) {
                output.putNextEntry(new ZipEntry((String) nameAndContents[i]));
                output.write((byte[]) nameAndContents[i + 1]);
                output.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static byte[] readFit() {
        try (InputStream input = new ClassPathResource("fit/synthetic-running.fit").getInputStream()) { return input.readAllBytes(); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
    }
}
