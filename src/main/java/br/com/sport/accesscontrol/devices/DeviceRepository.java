package br.com.sport.accesscontrol.devices;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DeviceRepository extends JpaRepository<Device, UUID> {
    List<Device> findByOnlineStatus(DeviceStatus onlineStatus);
}
