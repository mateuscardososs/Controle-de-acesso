package br.com.sport.accesscontrol.guests;

import br.com.sport.accesscontrol.common.TimestampedEntity;
import jakarta.persistence.*;

import java.time.Instant;
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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private GuestStatus status = GuestStatus.PENDING_REGISTRATION;

    @Column(name = "invited_at")
    private Instant invitedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected Guest() {
    }

    public Guest(String fullName, String cpf, String email, String phone, String company, String visitReason,
                 String hostName, Instant visitStart, Instant visitEnd) {
        this.fullName = fullName;
        this.cpf = cpf;
        this.email = email;
        this.phone = phone;
        this.company = company;
        this.visitReason = visitReason;
        this.hostName = hostName;
        this.visitStart = visitStart;
        this.visitEnd = visitEnd;
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
                       String hostName, Instant visitStart, Instant visitEnd, GuestStatus status) {
        this.fullName = fullName;
        this.cpf = cpf;
        this.email = email;
        this.phone = phone;
        this.company = company;
        this.visitReason = visitReason;
        this.hostName = hostName;
        this.visitStart = visitStart;
        this.visitEnd = visitEnd;
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
    }

    public void cancel() {
        this.status = GuestStatus.CANCELLED;
    }

    public void expire() {
        this.status = GuestStatus.EXPIRED;
    }
}
