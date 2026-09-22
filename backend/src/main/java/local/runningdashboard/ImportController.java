package local.runningdashboard;

import java.util.List;
import org.springframework.util.MultiValueMap;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/imports")
public class ImportController {
    private final ImportService imports;

    public ImportController(ImportService imports) { this.imports = imports; }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImportResponse importFiles(@RequestParam MultiValueMap<String, MultipartFile> parts) {
        List<MultipartFile> files = parts.get("files");
        if (files == null) files = parts.getOrDefault("file", List.of()); // backwards-compatible tracer field name
        ImportService.ImportBatch batch = imports.importFiles(files);
        BatchError error = batch.batchError() == null ? null : new BatchError(batch.batchError().code(), batch.batchError().message());
        return new ImportResponse(batch.outcomes(), error);
    }

    public record ImportResponse(List<ImportService.ImportOutcome> outcomes, BatchError batchError) { }
    public record BatchError(String code, String message) { }
}
