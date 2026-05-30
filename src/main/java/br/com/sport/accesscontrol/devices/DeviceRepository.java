package br.com.sport.accesscontrol.devices;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeviceRepository extends JpaRepository<Device, UUID> {
    List<Device> findByOnlineStatus(DeviceStatus onlineStatus);

    @Query("SELECT d FROM Device d LEFT JOIN FETCH d.area")
    List<Device> findAllWithArea();

    @Query("SELECT d FROM Device d LEFT JOIN FETCH d.area WHERE d.id = :id")
    Optional<Device> findByIdWithArea(@Param("id") UUID id);
}
