package br.com.sport.accesscontrol.employees;

import br.com.sport.accesscontrol.areas.Area;
import br.com.sport.accesscontrol.common.TimestampedEntity;
import br.com.sport.accesscontrol.integration.sync.SyncStatus;
import br.com.sport.accesscontrol.users.UserRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "employees")
public class Employee extends TimestampedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(nullable = false, unique = true)
    private String cpf;

    private String email;
    private String phone;

    @Column(name = "registration_number")
    private String registrationNumber;

    @Column(name = "job_title")
    private String jobTitle;

    @Column(name = "card_no")
    private String cardNo;

    @Column(name = "face_photo_url")
    private String facePhotoUrl;

    @Column(name = "user_id")
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role")
    private UserRole role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EmployeeStatus status = EmployeeStatus.ACTIVE;

    @Column(name = "access_valid_from")
    private Instant accessValidFrom;

    @Column(name = "access_valid_until")
    private Instant accessValidUntil;

    @Enumerated(EnumType.STRING)
    @Column(name = "sync_status", nullable = false)
    private SyncStatus syncStatus = SyncStatus.PENDING_SYNC;

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

    @Column(name = "intelbras_card_no", length = 10, unique = true)
    private String intelbrasCardNo;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "employee_allowed_areas",
            joinColumns = @JoinColumn(name = "employee_id"),
            inverseJoinColumns = @JoinColumn(name = "area_id")
    )
    private Set<Area> allowedAreas = new LinkedHashSet<>();

    protected Employee() {
    }

    public Employee(String fullName, String cpf, String email, String phone, String registrationNumber,
                    String facePhotoUrl, EmployeeStatus status, Instant accessValidFrom, Instant accessValidUntil) {
        this(fullName, cpf, email, phone, registrationNumber, null, facePhotoUrl, null, status, accessValidFrom, accessValidUntil);
    }

    public Employee(String fullName, String cpf, String email, String phone, String registrationNumber,
                    String facePhotoUrl, UserRole role, EmployeeStatus status, Instant accessValidFrom,
                    Instant accessValidUntil) {
        this(fullName, cpf, email, phone, registrationNumber, null, facePhotoUrl, role, status, accessValidFrom,
                accessValidUntil);
    }

    public Employee(String fullName, String cpf, String email, String phone, String registrationNumber,
                    String cardNo, String facePhotoUrl, UserRole role, EmployeeStatus status, Instant accessValidFrom,
                    Instant accessValidUntil) {
        this.fullName = fullName;
        this.cpf = cpf;
        this.email = email;
        this.phone = phone;
        this.registrationNumber = registrationNumber;
        this.cardNo = cardNo;
        this.facePhotoUrl = facePhotoUrl;
        this.role = role;
        this.status = status == null ? EmployeeStatus.ACTIVE : status;
        this.accessValidFrom = accessValidFrom;
        this.accessValidUntil = accessValidUntil;
    }

    public UUID getId() {
        return id;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getCpf() {
        return cpf;
    }

    public void setCpf(String cpf) {
        this.cpf = cpf;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getRegistrationNumber() {
        return registrationNumber;
    }

    public void setRegistrationNumber(String registrationNumber) {
        this.registrationNumber = registrationNumber;
    }

    public String getJobTitle() {
        return jobTitle;
    }

    public void setJobTitle(String jobTitle) {
        this.jobTitle = jobTitle;
    }

    public String getCardNo() {
        return cardNo;
    }

    public void setCardNo(String cardNo) {
        this.cardNo = cardNo;
    }

    public String getIntelbrasCardNo() {
        return intelbrasCardNo;
    }

    public void setIntelbrasCardNo(String intelbrasCardNo) {
        this.intelbrasCardNo = intelbrasCardNo;
    }

    public String getFacePhotoUrl() {
        return facePhotoUrl;
    }

    public void setFacePhotoUrl(String facePhotoUrl) {
        this.facePhotoUrl = facePhotoUrl;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public UserRole getRole() {
        return role;
    }

    public void setRole(UserRole role) {
        this.role = role;
    }

    public EmployeeStatus getStatus() {
        return status;
    }

    public void setStatus(EmployeeStatus status) {
        this.status = status;
    }

    public Instant getAccessValidFrom() {
        return accessValidFrom;
    }

    public void setAccessValidFrom(Instant accessValidFrom) {
        this.accessValidFrom = accessValidFrom;
    }

    public Instant getAccessValidUntil() {
        return accessValidUntil;
    }

    public void setAccessValidUntil(Instant accessValidUntil) {
        this.accessValidUntil = accessValidUntil;
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
