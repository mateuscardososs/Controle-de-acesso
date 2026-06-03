package br.com.sport.accesscontrol.integration.sync;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SyncStatusConverterTests {

    private final SyncStatusConverter converter = new SyncStatusConverter();

    @Test
    void allKnownValuesRoundTrip() {
        for (SyncStatus status : SyncStatus.values()) {
            assertThat(converter.convertToDatabaseColumn(status)).isEqualTo(status.name());
            assertThat(converter.convertToEntityAttribute(status.name())).isEqualTo(status);
        }
    }

    @Test
    void legacyPendingValueMapsToPendingSyncInsteadOfThrowing() {
        // Este é o valor que quebrava a hidratação (No enum constant ... PENDING) e derrubava a lista.
        assertThat(converter.convertToEntityAttribute("PENDING")).isEqualTo(SyncStatus.PENDING_SYNC);
    }

    @Test
    void unknownValueMapsToRecoverableDefaultInsteadOfThrowing() {
        assertThat(converter.convertToEntityAttribute("QUALQUER_COISA")).isEqualTo(SyncStatus.PENDING_SYNC);
        assertThat(converter.convertToEntityAttribute("FAILED")).isEqualTo(SyncStatus.SYNC_FAILED);
    }

    @Test
    void nullAndBlankAreSafe() {
        assertThat(converter.convertToEntityAttribute(null)).isEqualTo(SyncStatus.NOT_REQUIRED);
        assertThat(converter.convertToEntityAttribute("   ")).isEqualTo(SyncStatus.NOT_REQUIRED);
        assertThat(converter.convertToDatabaseColumn(null)).isEqualTo(SyncStatus.NOT_REQUIRED.name());
    }
}
