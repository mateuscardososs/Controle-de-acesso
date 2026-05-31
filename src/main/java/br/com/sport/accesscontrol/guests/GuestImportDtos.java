package br.com.sport.accesscontrol.guests;

import java.util.List;

public final class GuestImportDtos {
    private GuestImportDtos() {
    }

    /** A single parsed (but not yet imported) row shown in the preview. */
    public record ImportPreviewRow(
            int line,
            String fullName,
            String cpf,
            String phone,
            String invitedLounge,
            String invitedDay
    ) {
    }

    /** Returned by POST /api/guests/import/preview — no DB writes. */
    public record ImportPreviewResponse(
            int totalRowsInFile,
            List<String> detectedHeaders,
            List<String> missingRequiredColumns,
            List<ImportPreviewRow> preview
    ) {
    }

    /** Returned by POST /api/guests/import after persisting. */
    public record ImportReport(
            int total,
            int created,
            int updated,
            int skipped,
            List<ImportRowError> errors
    ) {
    }

    public record ImportRowError(
            int line,
            String cpf,
            String reason
    ) {
    }
}
