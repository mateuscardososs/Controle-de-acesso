package br.com.sport.accesscontrol.areas;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AreaRepository extends JpaRepository<Area, UUID> {
    Optional<Area> findByNameIgnoreCase(String name);
    List<Area> findAllByActiveTrue();
}
