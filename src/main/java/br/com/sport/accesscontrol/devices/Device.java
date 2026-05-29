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

    @Column(name = "http_port")
    private Integer httpPort = 80;

    @Column(name = "intelbras_username")
    private String intelbrasUsername;

    @Column(name = "intelbras_password")
    private String intelbrasPassword;

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

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    @Column(name = "last_heartbeat_at")
    private Instant lastHeartbeatAt;

    @Column(name = "last_success_at")
    private Instant lastSuccessAt;

    @Column(name = "last_failure_at")
    private Instant lastFailureAt;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "communication_failures", nullable = false)
    private int communicationFailures;

    @Enumerated(EnumType.STRING)
    @Column(name = "online_status", nullable = false)
    private DeviceStatus onlineStatus = DeviceStatus.UNKNOWN;

    protected Device() {
    }

    public Device(String name, String model, String serialNumber, String ipAddress, String location,
                  DeviceOperationType operationType, DeviceStatus status, Area area) {
        this(name, model, serialNumber, ipAddress, 80, location, operationType, status, area);
    }

    public Device(String name, String model, String serialNumber, String ipAddress, Integer httpPort, String location,
                  DeviceOperationType operationType, DeviceStatus status, Area area) {
        this.name = name;
        this.model = model;
        this.serialNumber = serialNumber;
        this.ipAddress = ipAddress;
        this.httpPort = normalizeHttpPort(httpPort);
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

    public Integer getHttpPort() {
        return httpPort;
    }

    public String getIntelbrasUsername() {
        return intelbrasUsername;
    }

    public void setIntelbrasUsername(String intelbrasUsername) {
        this.intelbrasUsername = blankToNull(intelbrasUsername);
    }

    public String getIntelbrasPassword() {
        return intelbrasPassword;
    }

    public void setIntelbrasPassword(String intelbrasPassword) {
        this.intelbrasPassword = blankToNull(intelbrasPassword);
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

    public boolean isActive() {
        return active;
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

    public Instant getLastSuccessAt() {
        return lastSuccessAt;
    }

    public Instant getLastFailureAt() {
        return lastFailureAt;
    }

    public String getLastError() {
        return lastError;
    }

    public int getCommunicationFailures() {
        return communicationFailures;
    }

    public DeviceStatus getOnlineStatus() {
        return onlineStatus;
    }

    public void markHeartbeat() {
        this.lastHeartbeatAt = Instant.now();
        this.lastSuccessAt = this.lastHeartbeatAt;
        this.lastError = null;
        this.communicationFailures = 0;
        setStatus(DeviceStatus.ONLINE);
    }

    public void registerCommunicationFailure(String error) {
        this.communicationFailures++;
        this.lastFailureAt = Instant.now();
        this.lastError = blankToNull(error);
        if (communicationFailures >= 3) {
            setStatus(DeviceStatus.OFFLINE);
        }
    }

    public void registerCommunicationFailure() {
        registerCommunicationFailure(null);
    }

    public void update(String name, String model, String serialNumber, String ipAddress, Integer httpPort,
                       String location, DeviceOperationType operationType, DeviceStatus status, Area area) {
        this.name = name;
        this.model = model;
        this.serialNumber = serialNumber;
        this.ipAddress = ipAddress;
        this.httpPort = normalizeHttpPort(httpPort);
        this.location = location;
        if (operationType != null) {
            this.operationType = operationType;
        }
        if (status != null) {
            setStatus(status);
        }
        if (area != null) {
            this.area = area;
        }
    }

    public void deactivate() {
        this.active = false;
        this.status = DeviceStatus.OFFLINE;
        this.onlineStatus = DeviceStatus.OFFLINE;
        this.lastSeenAt = Instant.now();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private Integer normalizeHttpPort(Integer value) {
        return value == null ? 80 : value;
    }
}
