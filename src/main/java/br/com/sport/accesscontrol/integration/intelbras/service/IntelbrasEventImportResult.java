package br.com.sport.accesscontrol.integration.intelbras.service;

import java.util.UUID;

public record IntelbrasEventImportResult(
        UUID deviceId,
        int received,
        int imported,
        int skipped
) {
}
