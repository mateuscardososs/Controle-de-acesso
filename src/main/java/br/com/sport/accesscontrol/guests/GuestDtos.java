package br.com.sport.accesscontrol.guests;

import br.com.sport.accesscontrol.integration.sync.SyncStatus;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
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
            GuestStatus status
    ) {
    }

    public record GuestCleanupRequest(
            List<GuestStatus> status,
            List<SyncStatus> integrationStatus,
            @Min(0) int olderThanDays,
            boolean onlyTestRecords
    ) {
    }

    public record GuestCleanupResponse(int removedCount) {
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
            int syncAttempts
    ) {
        static GuestResponse from(Guest guest, GuestInvite invite) {
            return from(guest, invite, null, null, null);
        }

        static GuestResponse from(Guest guest, GuestInvite invite, String inviteUrl, String emailDeliveryStatus,
                                  String emailDeliveryMessage) {
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
                    guest.getSyncAttempts()
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
}
