package br.com.sport.accesscontrol.guests;

import br.com.sport.accesscontrol.areas.Area;
import br.com.sport.accesscontrol.common.TimestampedEntity;
import br.com.sport.accesscontrol.integration.sync.SyncStatus;
import jakarta.persistence.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "guests")
public class Guest extends TimestampedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(nullable = false)
    private String cpf;

    private String email;
    private String phone;
    private String company;

    @Column(name = "visit_reason")
    private String visitReason;

    @Column(name = "host_name")
    private String hostName;

    @Column(name = "face_photo_url")
    private String facePhotoUrl;

    @Column(name = "visit_start", nullable = false)
    private Instant visitStart;

    @Column(name = "visit_end", nullable = false)
    private Instant visitEnd;

    @Column(name = "invited_day")
    private LocalDate invitedDay;

    @Column(name = "invited_lounge")
    private String invitedLounge;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private GuestStatus status = GuestStatus.PENDING_REGISTRATION;

    @Column(name = "invited_at")
    private Instant invitedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @jakarta.persistence.Convert(converter = br.com.sport.accesscontrol.integration.sync.SyncStatusConverter.class)
    @Column(name = "sync_status", nullable = false)
    private SyncStatus syncStatus = SyncStatus.NOT_REQUIRED;

    @Column(name = "last_sync_at")
    private Instant lastSyncAt;

    @Column(name = "last_sync_error")
    private String lastSyncError;

    @Column(name = "sync_attempts", nullable = false)
    private int syncAttempts;

    @Column(name = "sync_target_count", nullable = false)
    private int syncTargetCount;

    @Column(name = "sync_success_count", nullable = false)
    private int syncSuccessCount;

    @Column(name = "sync_failed_count", nullable = false)
    private int syncFailedCount;

    @Column(name = "sync_skipped_count", nullable = false)
    private int syncSkippedCount;

    @Column(name = "access_approved_email_sent_at")
    private Instant accessApprovedEmailSentAt;

    @Column(name = "access_approved_email_status")
    private String accessApprovedEmailStatus;

    @Column(name = "access_approved_email_message")
    private String accessApprovedEmailMessage;

    @Column(name = "intelbras_card_no", length = 10, unique = true)
    private String intelbrasCardNo;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "guest_allowed_areas",
            joinColumns = @JoinColumn(name = "guest_id"),
            inverseJoinColumns = @JoinColumn(name = "area_id")
    )
    private Set<Area> allowedAreas = new LinkedHashSet<>();

    protected Guest() {
    }

    public Guest(String fullName, String cpf, String email, String phone, String company, String visitReason,
                 String hostName, Instant visitStart, Instant visitEnd) {
        this(fullName, cpf, email, phone, company, visitReason, hostName, visitStart, visitEnd, null, null);
    }

    public Guest(String fullName, String cpf, String email, String phone, String company, String visitReason,
                 String hostName, Instant visitStart, Instant visitEnd, LocalDate invitedDay, String invitedLounge) {
        this.fullName = fullName;
        this.cpf = cpf;
        this.email = email;
        this.phone = phone;
        this.company = company;
        this.visitReason = visitReason;
        this.hostName = hostName;
        this.visitStart = visitStart;
        this.visitEnd = visitEnd;
        this.invitedDay = invitedDay;
        this.invitedLounge = invitedLounge;
        this.status = GuestStatus.PENDING_REGISTRATION;
        this.invitedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getFullName() {
        return fullName;
    }

    public void update(String fullName, String cpf, String email, String phone, String company, String visitReason,
                       String hostName, Instant visitStart, Instant visitEnd, LocalDate invitedDay,
                       String invitedLounge, GuestStatus status) {
        this.fullName = fullName;
        this.cpf = cpf;
        this.email = email;
        this.phone = phone;
        this.company = company;
        this.visitReason = visitReason;
        this.hostName = hostName;
        this.visitStart = visitStart;
        this.visitEnd = visitEnd;
        this.invitedDay = invitedDay;
        this.invitedLounge = invitedLounge;
        if (status != null) {
            this.status = status;
        }
    }

    public String getCpf() {
        return cpf;
    }

    public String getEmail() {
        return email;
    }

    public String getPhone() {
        return phone;
    }

    public String getCompany() {
        return company;
    }

    public String getVisitReason() {
        return visitReason;
    }

    public String getHostName() {
        return hostName;
    }

    public String getFacePhotoUrl() {
        return facePhotoUrl;
    }

    public Instant getVisitStart() {
        return visitStart;
    }

    public Instant getVisitEnd() {
        return visitEnd;
    }

    public LocalDate getInvitedDay() {
        return invitedDay;
    }

    public String getInvitedLounge() {
        return invitedLounge;
    }

    public GuestStatus getStatus() {
        return status;
    }

    public Instant getInvitedAt() {
        return invitedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void markInvited() {
        this.status = GuestStatus.PENDING_REGISTRATION;
        this.invitedAt = Instant.now();
    }

    public void completeRegistration(String phone, String company, String facePhotoUrl) {
        if (phone != null && !phone.isBlank()) {
            this.phone = phone;
        }
        if (company != null && !company.isBlank()) {
            this.company = company;
        }
        this.facePhotoUrl = facePhotoUrl;
        this.status = GuestStatus.COMPLETED;
        this.completedAt = Instant.now();
        if (syncStatus != SyncStatus.SYNCED && syncStatus != SyncStatus.SYNCING) {
            markPendingSync();
        }
    }

    public void cancel() {
        this.status = GuestStatus.CANCELLED;
    }

    public void expire() {
        this.status = GuestStatus.EXPIRED;
    }

    public SyncStatus getSyncStatus() {
        return syncStatus;
    }

    public Instant getLastSyncAt() {
        return lastSyncAt;
    }

    public String getLastSyncError() {
        return lastSyncError;
    }

    public int getSyncAttempts() {
        return syncAttempts;
    }

    public int getSyncTargetCount() {
        return syncTargetCount;
    }

    public int getSyncSuccessCount() {
        return syncSuccessCount;
    }

    public int getSyncFailedCount() {
        return syncFailedCount;
    }

    public int getSyncSkippedCount() {
        return syncSkippedCount;
    }

    public Instant getAccessApprovedEmailSentAt() {
        return accessApprovedEmailSentAt;
    }

    public String getAccessApprovedEmailStatus() {
        return accessApprovedEmailStatus;
    }

    public String getAccessApprovedEmailMessage() {
        return accessApprovedEmailMessage;
    }

    public boolean hasAccessApprovedEmailBeenSent() {
        return accessApprovedEmailSentAt != null;
    }

    public String getIntelbrasCardNo() {
        return intelbrasCardNo;
    }

    public void setIntelbrasCardNo(String intelbrasCardNo) {
        this.intelbrasCardNo = intelbrasCardNo;
    }

    public void markAccessApprovedEmail(String status, String message, boolean sent) {
        accessApprovedEmailStatus = status;
        accessApprovedEmailMessage = message;
        if (sent) {
            accessApprovedEmailSentAt = Instant.now();
        }
    }

    public void markPendingSync() {
        syncStatus = SyncStatus.PENDING_SYNC;
        lastSyncError = null;
        clearSyncCounts();
    }

    public void markSyncing() {
        syncStatus = SyncStatus.SYNCING;
        syncAttempts++;
    }

    public void markSynced() {
        markSynced(0, 0, 0, 0);
    }

    public void markSynced(int totalTargets, int successCount, int failedCount, int skippedCount) {
        syncStatus = SyncStatus.SYNCED;
        lastSyncAt = Instant.now();
        lastSyncError = null;
        setSyncCounts(totalTargets, successCount, failedCount, skippedCount);
    }

    public void markSyncedWithWarnings(String warning, int totalTargets, int successCount, int failedCount, int skippedCount) {
        syncStatus = SyncStatus.SYNCED_WITH_WARNINGS;
        lastSyncAt = Instant.now();
        lastSyncError = warning;
        setSyncCounts(totalTargets, successCount, failedCount, skippedCount);
    }

    public void markSyncFailed(String error) {
        markSyncFailed(error, 0, 0, 0, 0);
    }

    public void markSyncFailed(String error, int totalTargets, int successCount, int failedCount, int skippedCount) {
        syncStatus = SyncStatus.SYNC_FAILED;
        lastSyncAt = Instant.now();
        lastSyncError = error;
        setSyncCounts(totalTargets, successCount, failedCount, skippedCount);
    }

    public Set<Area> getAllowedAreas() {
        if (allowedAreas == null) {
            allowedAreas = new LinkedHashSet<>();
        }
        return allowedAreas;
    }

    public void replaceAllowedAreas(Set<Area> areas) {
        if (allowedAreas == null) {
            allowedAreas = new LinkedHashSet<>();
        } else {
            allowedAreas.clear();
        }
        if (areas != null) {
            allowedAreas.addAll(areas);
        }
    }

    private void clearSyncCounts() {
        setSyncCounts(0, 0, 0, 0);
    }

    private void setSyncCounts(int totalTargets, int successCount, int failedCount, int skippedCount) {
        this.syncTargetCount = Math.max(0, totalTargets);
        this.syncSuccessCount = Math.max(0, successCount);
        this.syncFailedCount = Math.max(0, failedCount);
        this.syncSkippedCount = Math.max(0, skippedCount);
    }
}
