package local.runningdashboard;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

public class GarminFitZipExtractor {
    private final int maxZipBytes;
    private final int maxExpandedBytes;

    public GarminFitZipExtractor(int maxZipBytes, int maxExpandedBytes) {
        if (maxZipBytes < 1 || maxExpandedBytes < 1) throw new IllegalArgumentException("ZIP limits must be positive");
        this.maxZipBytes = maxZipBytes;
        this.maxExpandedBytes = maxExpandedBytes;
    }

    public byte[] extract(byte[] archive) {
        if (archive == null || archive.length < 4 || archive[0] != 'P' || archive[1] != 'K') {
            throw new GarminClientException("INVALID_ARCHIVE");
        }
        if (archive.length > maxZipBytes) throw new GarminClientException("ZIP_TOO_LARGE");
        byte[] selected = null;
        long expanded = 0;
        int fitCount = 0;
        try (var input = new ZipInputStream(new ByteArrayInputStream(archive))) {
            for (var entry = input.getNextEntry(); entry != null; entry = input.getNextEntry()) {
                if (entry.getName().contains("/") || entry.getName().contains("\\") || entry.getName().equals(".") || entry.getName().equals("..")) {
                    throw new GarminClientException("INVALID_ARCHIVE");
                }
                boolean fit = !entry.isDirectory() && entry.getName().toLowerCase(Locale.ROOT).endsWith(".fit");
                ByteArrayOutputStream bytes = fit ? new ByteArrayOutputStream() : null;
                byte[] buffer = new byte[8192];
                for (int read; (read = input.read(buffer)) != -1;) {
                    expanded += read;
                    if (expanded > maxExpandedBytes) throw new GarminClientException("FILE_TOO_LARGE");
                    if (fit) bytes.write(buffer, 0, read);
                }
                input.closeEntry();
                if (fit) {
                    if (++fitCount > 1) throw new GarminClientException("AMBIGUOUS_FIT_FILES");
                    selected = bytes.toByteArray();
                    if (!isFit(selected)) throw new GarminClientException("INVALID_FIT_FILE");
                }
            }
        } catch (GarminClientException failure) {
            throw failure;
        } catch (ZipException invalid) {
            throw new GarminClientException("INVALID_ARCHIVE");
        } catch (IOException invalid) {
            throw new GarminClientException("INVALID_ARCHIVE");
        }
        if (selected == null) throw new GarminClientException("NO_FIT_FILE");
        return selected;
    }

    private static boolean isFit(byte[] bytes) {
        return bytes.length >= 12 && bytes[8] == '.' && bytes[9] == 'F' && bytes[10] == 'I' && bytes[11] == 'T';
    }
}
