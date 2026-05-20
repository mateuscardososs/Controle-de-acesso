package br.com.sport.accesscontrol.integration.intelbras.mapper;

import br.com.sport.accesscontrol.areas.Area;
import br.com.sport.accesscontrol.common.PersonType;
import br.com.sport.accesscontrol.devices.Device;
import br.com.sport.accesscontrol.devices.DeviceOperationType;
import br.com.sport.accesscontrol.devices.DeviceStatus;
import br.com.sport.accesscontrol.events.AccessEventType;
import br.com.sport.accesscontrol.events.AccessResult;
import br.com.sport.accesscontrol.integration.intelbras.model.IntelbrasPersonIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class IntelbrasEventMapperTests {

    @Test
    void parsesAccessControlCardRecFieldsAndNormalizesEvent() {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("CardName", "Mateus");
        payload.put("UserID", "12345678901");
        payload.put("Status", "1");
        payload.put("Type", "Entry");
        payload.put("CreateTime", "2026-05-19 20:30:00");
        payload.put("URL", "/snap.jpg");
        payload.put("ErrorCode", "0");
        payload.put("Door", "1");
        payload.put("ReaderID", "1");
        var mapper = new IntelbrasEventMapper();
        var device = device();
        var personId = UUID.randomUUID();

        var record = mapper.parseAccessControlCardRec(payload);
        var normalized = mapper.normalizeAccessControlCardRec(
                payload,
                device,
                new IntelbrasPersonIdentity(PersonType.EMPLOYEE, personId)
        );

        assertThat(record.cardName()).isEqualTo("Mateus");
        assertThat(record.userId()).isEqualTo("12345678901");
        assertThat(record.errorCode()).isEqualTo("0");
        assertThat(normalized.personId()).isEqualTo(personId);
        assertThat(normalized.deviceId()).isEqualTo(device.getId());
        assertThat(normalized.eventType()).isEqualTo(AccessEventType.ENTRY);
        assertThat(normalized.accessResult()).isEqualTo(AccessResult.ALLOWED);
        assertThat(normalized.origin()).isEqualTo("INTELBRAS_REAL");
    }

    private Device device() {
        var area = new Area("Social", "Social", true);
        ReflectionTestUtils.setField(area, "id", UUID.randomUUID());
        var device = new Device("SS 5531", "Intelbras SS 5531 MF W", "DRWL3903457HU", "192.168.15.5",
                "Portaria", DeviceOperationType.ENTRY_EXIT, DeviceStatus.ONLINE, area);
        ReflectionTestUtils.setField(device, "id", UUID.randomUUID());
        return device;
    }
}
