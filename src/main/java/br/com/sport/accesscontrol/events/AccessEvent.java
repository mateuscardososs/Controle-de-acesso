package br.com.sport.accesscontrol.events;

import br.com.sport.accesscontrol.areas.Area;
import br.com.sport.accesscontrol.common.PersonType;
import br.com.sport.accesscontrol.devices.Device;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
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

    @Column(name = "person_id", nullable = false)
    private UUID personId;

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

    @Column(name = "event_time", nullable = false)
    private Instant eventTime;

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
        this.personType = personType;
        this.personId = personId;
        this.device = device;
        this.area = area;
        this.eventType = eventType;
        this.accessResult = accessResult;
        this.eventTime = eventTime == null ? Instant.now() : eventTime;
        this.origin = origin == null ? "SIMULATION" : origin;
        this.rawPayload = rawPayload;
    }

    @PrePersist
    void prePersist() {
        createdAt = Instant.now();
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

    public Instant getEventTime() {
        return eventTime;
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
}
