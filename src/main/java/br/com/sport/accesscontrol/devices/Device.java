package br.com.sport.accesscontrol.devices;

import br.com.sport.accesscontrol.areas.Area;
import br.com.sport.accesscontrol.common.TimestampedEntity;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "devices")
public class Device extends TimestampedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String name;

    private String model;

    @Column(name = "serial_number")
    private String serialNumber;

    @Column(name = "ip_address", nullable = false)
    private String ipAddress;

    private String location;

    @Enumerated(EnumType.STRING)
    @Column(name = "operation_type", nullable = false)
    private DeviceOperationType operationType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DeviceStatus status;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "area_id", nullable = false)
    private Area area;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    @Column(name = "last_heartbeat_at")
    private Instant lastHeartbeatAt;

    @Column(name = "communication_failures", nullable = false)
    private int communicationFailures;

    @Enumerated(EnumType.STRING)
    @Column(name = "online_status", nullable = false)
    private DeviceStatus onlineStatus = DeviceStatus.UNKNOWN;

    protected Device() {
    }

    public Device(String name, String model, String serialNumber, String ipAddress, String location,
                  DeviceOperationType operationType, DeviceStatus status, Area area) {
        this.name = name;
        this.model = model;
        this.serialNumber = serialNumber;
        this.ipAddress = ipAddress;
        this.location = location;
        this.operationType = operationType == null ? DeviceOperationType.ENTRY_EXIT : operationType;
        this.status = status == null ? DeviceStatus.UNKNOWN : status;
        this.onlineStatus = this.status;
        this.area = area;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getModel() {
        return model;
    }

    public String getSerialNumber() {
        return serialNumber;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public String getLocation() {
        return location;
    }

    public DeviceOperationType getOperationType() {
        return operationType;
    }

    public DeviceStatus getStatus() {
        return status;
    }

    public void setStatus(DeviceStatus status) {
        this.status = status;
        this.onlineStatus = status;
        this.lastSeenAt = Instant.now();
    }

    public Area getArea() {
        return area;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public Instant getLastHeartbeatAt() {
        return lastHeartbeatAt;
    }

    public int getCommunicationFailures() {
        return communicationFailures;
    }

    public DeviceStatus getOnlineStatus() {
        return onlineStatus;
    }

    public void markHeartbeat() {
        this.lastHeartbeatAt = Instant.now();
        this.communicationFailures = 0;
        setStatus(DeviceStatus.ONLINE);
    }

    public void registerCommunicationFailure() {
        this.communicationFailures++;
        if (communicationFailures >= 3) {
            setStatus(DeviceStatus.OFFLINE);
        }
    }
}
