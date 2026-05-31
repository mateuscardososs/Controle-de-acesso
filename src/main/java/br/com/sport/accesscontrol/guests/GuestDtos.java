package br.com.sport.accesscontrol.guests;

import br.com.sport.accesscontrol.areas.Area;
import br.com.sport.accesscontrol.integration.sync.SyncStatus;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public final class GuestDtos {
    private GuestDtos() {
    }

    public record GuestRequest(
            @NotBlank String fullName,
            @NotBlank String cpf,
            @Email String email,
            String phone,
            String company,
            @NotBlank String visitReason,
            @NotBlank String hostName,
            @NotNull Instant visitStart,
            @NotNull Instant visitEnd,
            LocalDate invitedDay,
            String invitedLounge,
            GuestStatus status
    ) {
        public GuestRequest(
                String fullName,
                String cpf,
                String email,
                String phone,
                String company,
                String visitReason,
                String hostName,
                Instant visitStart,
                Instant visitEnd,
                GuestStatus status
        ) {
            this(fullName, cpf, email, phone, company, visitReason, hostName, visitStart, visitEnd, null, null, status);
        }
    }

    public record GuestCleanupRequest(
            GuestCleanupMode mode,
            List<GuestStatus> status,
            List<SyncStatus> integrationStatus,
            @Min(0) int olderThanDays,
            boolean onlyTestRecords,
            String confirmationPhrase
    ) {
        public GuestCleanupRequest(List<GuestStatus> status, List<SyncStatus> integrationStatus,
                                   int olderThanDays, boolean onlyTestRecords) {
            this(null, status, integrationStatus, olderThanDays, onlyTestRecords, null);
        }
    }

    public enum GuestCleanupMode {
        CANCELLED,
        FAILED,
        TEST_RECORDS,
        ALL
    }

    public record GuestCleanupResponse(int removedCount, String message) {
    }

    public record GuestResponse(
            UUID id,
            String fullName,
            String cpf,
            String email,
            String phone,
            String company,
            String visitReason,
            String hostName,
            Instant visitStart,
            Instant visitEnd,
            LocalDate invitedDay,
            String invitedLounge,
            GuestStatus status,
            String facePhotoUrl,
            Instant invitedAt,
            Instant completedAt,
            String inviteToken,
            String inviteUrl,
            Instant inviteExpiresAt,
            String emailDeliveryStatus,
            String emailDeliveryMessage,
            SyncStatus syncStatus,
            Instant lastSyncAt,
            String lastSyncError,
            int syncAttempts,
            int syncTargetCount,
            int syncSuccessCount,
            int syncFailedCount,
            int syncSkippedCount,
            Instant accessApprovedEmailSentAt,
            String accessApprovedEmailStatus,
            String accessApprovedEmailMessage,
            List<UUID> allowedAreaIds,
            List<String> allowedAreaNames,
            String displayAllowedAreas
    ) {
        static GuestResponse from(Guest guest, GuestInvite invite) {
            return from(guest, invite, null, null, null);
        }

        static GuestResponse from(Guest guest, GuestInvite invite, String inviteUrl, String emailDeliveryStatus,
                                  String emailDeliveryMessage) {
            List<Area> allowed = guest.getAllowedAreas() == null
                    ? Collections.emptyList()
                    : guest.getAllowedAreas().stream().toList();
            List<UUID> allowedIds = allowed.stream().map(Area::getId).toList();
            List<String> allowedNames = allowed.stream().map(Area::getName).toList();
            String display = allowedNames.isEmpty() ? null : String.join(" / ", allowedNames);
            return new GuestResponse(
                    guest.getId(),
                    guest.getFullName(),
                    guest.getCpf(),
                    guest.getEmail(),
                    guest.getPhone(),
                    guest.getCompany(),
                    guest.getVisitReason(),
                    guest.getHostName(),
                    guest.getVisitStart(),
                    guest.getVisitEnd(),
                    guest.getInvitedDay(),
                    guest.getInvitedLounge(),
                    guest.getStatus(),
                    guest.getFacePhotoUrl(),
                    guest.getInvitedAt(),
                    guest.getCompletedAt(),
                    invite == null ? null : invite.getToken(),
                    inviteUrl,
                    invite == null ? null : invite.getExpiresAt(),
                    emailDeliveryStatus,
                    emailDeliveryMessage,
                    guest.getSyncStatus(),
                    guest.getLastSyncAt(),
                    guest.getLastSyncError(),
                    guest.getSyncAttempts(),
                    guest.getSyncTargetCount(),
                    guest.getSyncSuccessCount(),
                    guest.getSyncFailedCount(),
                    guest.getSyncSkippedCount(),
                    guest.getAccessApprovedEmailSentAt(),
                    guest.getAccessApprovedEmailStatus(),
                    guest.getAccessApprovedEmailMessage(),
                    allowedIds,
                    allowedNames,
                    display
            );
        }
    }

    public record PublicGuestRegistrationResponse(
            UUID id,
            String fullName,
            String company,
            String visitReason,
            String hostName,
            Instant visitStart,
            Instant visitEnd,
            LocalDate invitedDay,
            String invitedLounge,
            GuestStatus status,
            boolean requiresFacePhoto
    ) {
        static PublicGuestRegistrationResponse from(Guest guest) {
            return new PublicGuestRegistrationResponse(
                    guest.getId(),
                    guest.getFullName(),
                    guest.getCompany(),
                    guest.getVisitReason(),
                    guest.getHostName(),
                    guest.getVisitStart(),
                    guest.getVisitEnd(),
                    guest.getInvitedDay(),
                    guest.getInvitedLounge(),
                    guest.getStatus(),
                    guest.getFacePhotoUrl() == null || guest.getFacePhotoUrl().isBlank()
            );
        }
    }

    public record PublicVisitorRegistrationResponse(
            UUID id,
            String fullName,
            GuestStatus status,
            String message,
            boolean facePhotoReceived
    ) {
        static PublicVisitorRegistrationResponse from(Guest guest, String message) {
            return new PublicVisitorRegistrationResponse(
                    guest.getId(),
                    guest.getFullName(),
                    guest.getStatus(),
                    message,
                    guest.getFacePhotoUrl() != null && !guest.getFacePhotoUrl().isBlank()
            );
        }
    }

    /** Feature 3 — public CPF-validation check-in flow. */
    public record CpfValidationRequest(String cpf) {
    }

    public record CpfValidationResponse(
            boolean found,
            String fullName,
            String invitedLounge,
            String message
    ) {
        public static CpfValidationResponse notFound() {
            return new CpfValidationResponse(false, null, null,
                    "CPF não encontrado. Verifique com o organizador.");
        }

        public static CpfValidationResponse alreadyRegistered() {
            return new CpfValidationResponse(false, null, null,
                    "Usuário já cadastrado. Seu acesso já está ativo.");
        }

        public static CpfValidationResponse welcome(Guest guest) {
            return new CpfValidationResponse(true, guest.getFullName(), guest.getInvitedLounge(),
                    "Seja bem-vindo(a), " + guest.getFullName() + "!");
        }
    }

    public record CpfCheckinRequest(String cpf, String facePhoto) {
    }

    public record CpfCheckinResponse(
            boolean success,
            String fullName,
            String invitedLounge,
            String message
    ) {
        public static CpfCheckinResponse done(Guest guest) {
            return new CpfCheckinResponse(true, guest.getFullName(), guest.getInvitedLounge(),
                    "Cadastro concluído! Bem-vindo(a) ao evento.");
        }
    }
}
