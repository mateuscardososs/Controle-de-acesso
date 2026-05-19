package br.com.sport.accesscontrol.employees;

import br.com.sport.accesscontrol.common.TimestampedEntity;
import br.com.sport.accesscontrol.integration.sync.SyncStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
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

    @Column(name = "face_photo_url")
    private String facePhotoUrl;

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

    protected Employee() {
    }

    public Employee(String fullName, String cpf, String email, String phone, String registrationNumber,
                    String facePhotoUrl, EmployeeStatus status, Instant accessValidFrom, Instant accessValidUntil) {
        this.fullName = fullName;
        this.cpf = cpf;
        this.email = email;
        this.phone = phone;
        this.registrationNumber = registrationNumber;
        this.facePhotoUrl = facePhotoUrl;
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

    public String getFacePhotoUrl() {
        return facePhotoUrl;
    }

    public void setFacePhotoUrl(String facePhotoUrl) {
        this.facePhotoUrl = facePhotoUrl;
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

    public void markPendingSync() {
        syncStatus = SyncStatus.PENDING_SYNC;
        lastSyncError = null;
    }

    public void markSyncing() {
        syncStatus = SyncStatus.SYNCING;
        syncAttempts++;
    }

    public void markSynced() {
        syncStatus = SyncStatus.SYNCED;
        lastSyncAt = Instant.now();
        lastSyncError = null;
    }

    public void markSyncFailed(String error) {
        syncStatus = SyncStatus.SYNC_FAILED;
        lastSyncAt = Instant.now();
        lastSyncError = error;
    }
}
