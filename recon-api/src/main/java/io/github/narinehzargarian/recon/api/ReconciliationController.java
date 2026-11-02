package io.github.narinehzargarian.recon.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.narinehzargarian.recon.MatchConfig;
import io.github.narinehzargarian.recon.Reconciler;
import io.github.narinehzargarian.recon.ReconciliationResult;
import io.github.narinehzargarian.recon.io.CsvMapping;
import io.github.narinehzargarian.recon.io.CsvTransactionReader;
import io.github.narinehzargarian.recon.io.OfxTransactionReader;
import io.github.narinehzargarian.recon.io.ReadResult;
import io.github.narinehzargarian.recon.io.ReportWriter;
import io.github.narinehzargarian.recon.io.RowError;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/reconciliations")
public class ReconciliationController {

    /**
     * Optional settings sent with the files. Amounts are in minor units, such as cents.
     */
    public record Options(CsvMapping leftMapping, CsvMapping rightMapping, MatchConfig config) {
    }

    public record CreatedResponse(UUID id, ReconciliationResult.Summary summary, List<RowError> leftErrors, List<RowError> rightErrors) {
    }

    private final ReconciliationStore store;
    private final ObjectMapper mapper;
    private final int maxRows;

    public ReconciliationController(ReconciliationStore store, ObjectMapper mapper, @Value("${recon.max-rows:200000}") int maxRows) {
        this.store = store;
        this.mapper = mapper;
        this.maxRows = maxRows;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<CreatedResponse> create(
            @RequestPart("left") MultipartFile left,
            @RequestPart("right") MultipartFile right,
            @RequestPart(name = "options", required = false) Options options) throws IOException {
        Options settings = options == null ? new Options(null, null, null) : options;
        ReadResult leftRead = read(left, settings.leftMapping());
        ReadResult rightRead = read(right, settings.rightMapping());
        MatchConfig config = settings.config() == null ? MatchConfig.defaults() : settings.config();
        ReconciliationResult result = new Reconciler(config).reconcile(leftRead.transactions(), rightRead.transactions());
        UUID id = store.save(name(left), name(right), result, mapper.writeValueAsString(result));
        return ResponseEntity.created(URI.create("/api/reconciliations/" + id))
                .body(new CreatedResponse(id, result.summary(), leftRead.errors(), rightRead.errors()));
    }

    @GetMapping
    public List<ReconciliationStore.Listing> list(@RequestParam(defaultValue = "20") int limit) {
        return store.list(Math.max(1, Math.min(limit, 100)));
    }

    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> get(@PathVariable UUID id) {
        return store.resultJson(id).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping(value = "/{id}/report.csv", produces = "text/csv")
    public ResponseEntity<String> report(@PathVariable UUID id) throws IOException {
        var json = store.resultJson(id);
        if (json.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        StringWriter out = new StringWriter();
        ReportWriter.writeCsv(mapper.readValue(json.get(), ReconciliationResult.class), out);
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"reconciliation-" + id + ".csv\"")
                .body(out.toString());
    }

    private ReadResult read(MultipartFile file, CsvMapping mapping) throws IOException {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("'" + name(file) + "' is empty");
        }
        String name = name(file).toLowerCase(Locale.ROOT);
        if (name.endsWith(".ofx") || name.endsWith(".qfx")) {
            return new OfxTransactionReader().read(new String(file.getBytes(), StandardCharsets.ISO_8859_1), maxRows);
        }
        try (var reader = new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8)) {
            return new CsvTransactionReader(mapping == null ? CsvMapping.standard() : mapping).read(reader, maxRows);
        }
    }

    private static String name(MultipartFile file) {
        String name = file.getOriginalFilename();
        return name == null || name.isBlank() ? file.getName() : name;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail badInput(IllegalArgumentException error) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, error.getMessage());
    }
}
