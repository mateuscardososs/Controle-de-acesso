package br.com.sport.accesscontrol.events;

import br.com.sport.accesscontrol.areas.Area;
import br.com.sport.accesscontrol.common.PersonType;
import br.com.sport.accesscontrol.devices.Device;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "access_events")
public class AccessEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "person_type", nullable = false)
    private PersonType personType;

    @Column(name = "person_id")
    private UUID personId;

    @Column(name = "person_name")
    private String personName;

    @Column(name = "person_cpf")
    private String personCpf;

    @Column(name = "person_email")
    private String personEmail;

    @Column(name = "person_phone")
    private String personPhone;

    @Column(name = "invited_day")
    private LocalDate invitedDay;

    @Column(name = "invited_lounge")
    private String invitedLounge;

    @Column(name = "external_user_id")
    private String externalUserId;

    @Column(name = "raw_card_name")
    private String rawCardName;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "device_id", nullable = false)
    private Device device;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "area_id", nullable = false)
    private Area area;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false)
    private AccessEventType eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "access_result", nullable = false)
    private AccessResult accessResult;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_category")
    private EventCategory eventCategory;

    @Enumerated(EnumType.STRING)
    @Column(name = "recognition_status")
    private RecognitionStatus recognitionStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "passage_status")
    private PassageStatus passageStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "release_method")
    private ReleaseMethod releaseMethod;

    @Column(name = "operator_user_id")
    private UUID operatorUserId;

    @Column(name = "manual_reason")
    private String manualReason;

    @Column(name = "controller_method")
    private String controllerMethod;

    @Column(name = "controller_door")
    private String controllerDoor;

    @Column(name = "controller_reader_id")
    private String controllerReaderId;

    @Column(name = "controller_rec_no")
    private String controllerRecNo;

    @Column(name = "decision_reason")
    private String decisionReason;

    @Column(name = "event_time", nullable = false)
    private Instant eventTime;

    @Column(name = "occurred_at")
    private Instant occurredAt;

    @Column(nullable = false)
    private String origin;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_payload", columnDefinition = "jsonb")
    private Map<String, Object> rawPayload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AccessEvent() {
    }

    public AccessEvent(PersonType personType, UUID personId, Device device, Area area, AccessEventType eventType,
                       AccessResult accessResult, Instant eventTime, String origin, Map<String, Object> rawPayload) {
        this(personType, personId, null, null, null, null, device, area, eventType, accessResult, eventTime, origin, rawPayload);
    }

    public AccessEvent(PersonType personType, UUID personId, String personName, String personCpf,
                       String externalUserId, String rawCardName, Device device, Area area, AccessEventType eventType,
                       AccessResult accessResult, Instant eventTime, String origin, Map<String, Object> rawPayload) {
        this.personType = personType;
        this.personId = personId;
        this.personName = personName;
        this.personCpf = personCpf;
        this.externalUserId = externalUserId;
        this.rawCardName = rawCardName;
        this.device = device;
        this.area = area;
        this.eventType = eventType;
        this.accessResult = accessResult;
        this.eventTime = eventTime == null ? Instant.now() : eventTime;
        this.occurredAt = this.eventTime;
        this.origin = origin == null ? "SIMULATION" : origin;
        this.rawPayload = rawPayload;
    }

    @PrePersist
    void prePersist() {
        createdAt = Instant.now();
        if (occurredAt == null) {
            occurredAt = eventTime == null ? createdAt : eventTime;
        }
    }

    public void applyOperationalFields(EventCategory eventCategory, RecognitionStatus recognitionStatus,
                                       PassageStatus passageStatus, ReleaseMethod releaseMethod,
                                       UUID operatorUserId, String manualReason, String controllerMethod,
                                       String controllerDoor, String controllerReaderId, String controllerRecNo,
                                       String decisionReason, Instant occurredAt) {
        this.eventCategory = eventCategory;
        this.recognitionStatus = recognitionStatus;
        this.passageStatus = passageStatus;
        this.releaseMethod = releaseMethod;
        this.operatorUserId = operatorUserId;
        this.manualReason = blankToNull(manualReason);
        this.controllerMethod = blankToNull(controllerMethod);
        this.controllerDoor = blankToNull(controllerDoor);
        this.controllerReaderId = blankToNull(controllerReaderId);
        this.controllerRecNo = blankToNull(controllerRecNo);
        this.decisionReason = blankToNull(decisionReason);
        this.occurredAt = occurredAt == null ? this.eventTime : occurredAt;
    }

    public void applyPersonSnapshot(String personName, String personCpf, String personEmail, String personPhone,
                                    LocalDate invitedDay, String invitedLounge) {
        if (this.personName == null || this.personName.isBlank()) {
            this.personName = blankToNull(personName);
        }
        if (this.personCpf == null || this.personCpf.isBlank()) {
            this.personCpf = blankToNull(personCpf);
        }
        this.personEmail = blankToNull(personEmail);
        this.personPhone = blankToNull(personPhone);
        this.invitedDay = invitedDay;
        this.invitedLounge = blankToNull(invitedLounge);
    }

    public UUID getId() {
        return id;
    }

    public PersonType getPersonType() {
        return personType;
    }

    public UUID getPersonId() {
        return personId;
    }

    public String getPersonName() {
        return personName;
    }

    public String getPersonCpf() {
        return personCpf;
    }

    public String getPersonEmail() {
        return personEmail;
    }

    public String getPersonPhone() {
        return personPhone;
    }

    public LocalDate getInvitedDay() {
        return invitedDay;
    }

    public String getInvitedLounge() {
        return invitedLounge;
    }

    public String getExternalUserId() {
        return externalUserId;
    }

    public String getRawCardName() {
        return rawCardName;
    }

    public Device getDevice() {
        return device;
    }

    public Area getArea() {
        return area;
    }

    public AccessEventType getEventType() {
        return eventType;
    }

    public AccessResult getAccessResult() {
        return accessResult;
    }

    public EventCategory getEventCategory() {
        return eventCategory;
    }

    public RecognitionStatus getRecognitionStatus() {
        return recognitionStatus;
    }

    public PassageStatus getPassageStatus() {
        return passageStatus;
    }

    public ReleaseMethod getReleaseMethod() {
        return releaseMethod;
    }

    public UUID getOperatorUserId() {
        return operatorUserId;
    }

    public String getManualReason() {
        return manualReason;
    }

    public String getControllerMethod() {
        return controllerMethod;
    }

    public String getControllerDoor() {
        return controllerDoor;
    }

    public String getControllerReaderId() {
        return controllerReaderId;
    }

    public String getControllerRecNo() {
        return controllerRecNo;
    }

    public String getDecisionReason() {
        return decisionReason;
    }

    public Instant getEventTime() {
        return eventTime;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public String getOrigin() {
        return origin;
    }

    public Map<String, Object> getRawPayload() {
        return rawPayload;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
