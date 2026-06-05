package br.com.sport.accesscontrol.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@RestController
@RequestMapping("/api/admin/face-photos")
public class AdminFacePhotoAuditController {

    private static final Logger log = LoggerFactory.getLogger(AdminFacePhotoAuditController.class);

    private static final long LIMIT_STORAGE_BYTES = 99_328L;
    private static final long LIMIT_INTELBRAS_BYTES = 97_280L;
    private static final long LIMIT_100KB = 102_400L;

    private final Path uploadRoot;

    public AdminFacePhotoAuditController(@Value("${app.uploads.faces-dir:uploads/faces}") String facesDir) {
        this.uploadRoot = Path.of(facesDir).toAbsolutePath().normalize();
    }

    @GetMapping("/audit")
    public AuditResponse audit() throws IOException {
        if (!Files.exists(uploadRoot)) {
            return new AuditResponse(0, 0, 0, 0, 0.0, 0, 0, 0, List.of());
        }

        var sizes = new ArrayList<Long>();
        var largest = new ArrayList<FileEntry>();

        try (var stream = Files.walk(uploadRoot, 1)) {
            stream.filter(Files::isRegularFile).forEach(path -> {
                try {
                    long size = Files.size(path);
                    sizes.add(size);
                    largest.add(new FileEntry(path.getFileName().toString(), size));
                } catch (IOException e) {
                    log.warn("FACE_AUDIT_SKIP file={} error={}", path, e.getMessage());
                }
            });
        }

        if (sizes.isEmpty()) {
            return new AuditResponse(0, 0, 0, 0, 0.0, 0, 0, 0, List.of());
        }

        long total = sizes.stream().mapToLong(Long::longValue).sum();
        long min = sizes.stream().mapToLong(Long::longValue).min().orElse(0);
        long max = sizes.stream().mapToLong(Long::longValue).max().orElse(0);
        double avg = sizes.stream().mapToLong(Long::longValue).average().orElse(0);
        long countOverStorageLimit = sizes.stream().filter(s -> s > LIMIT_STORAGE_BYTES).count();
        long countOverIntelbrasLimit = sizes.stream().filter(s -> s > LIMIT_INTELBRAS_BYTES).count();
        long countOver100KB = sizes.stream().filter(s -> s > LIMIT_100KB).count();

        var top10 = largest.stream()
                .sorted(Comparator.comparingLong(FileEntry::sizeBytes).reversed())
                .limit(10)
                .toList();

        log.info("FACE_AUDIT_DONE totalFiles={} totalBytes={} minBytes={} maxBytes={} avgBytes={} countOverStorageLimit={} countOverIntelbrasLimit={} countOver100KB={}",
                sizes.size(), total, min, max, Math.round(avg), countOverStorageLimit, countOverIntelbrasLimit, countOver100KB);

        return new AuditResponse(sizes.size(), total, min, max, avg,
                countOverStorageLimit, countOverIntelbrasLimit, countOver100KB, top10);
    }

    public record AuditResponse(
            int totalFiles,
            long totalBytes,
            long minBytes,
            long maxBytes,
            double avgBytes,
            long countOverStorageLimit,
            long countOverIntelbrasLimit,
            long countOver100KB,
            List<FileEntry> largestFiles
    ) {}

    public record FileEntry(String filename, long sizeBytes) {}
}
