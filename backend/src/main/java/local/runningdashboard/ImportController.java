package local.runningdashboard;

import java.util.List;
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
    public ImportResponse importFile(@RequestParam("file") MultipartFile file) {
        return new ImportResponse(List.of(imports.importFile(file)));
    }

    public record ImportResponse(List<ImportService.ImportOutcome> outcomes) { }
}
