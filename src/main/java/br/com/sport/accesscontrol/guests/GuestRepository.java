package br.com.sport.accesscontrol.guests;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GuestRepository extends JpaRepository<Guest, UUID> {
    List<Guest> findByVisitStartLessThanEqualAndVisitEndGreaterThanEqual(Instant end, Instant start);
    List<Guest> findByStatusNotAndVisitEndBefore(GuestStatus status, Instant now);
    Optional<Guest> findFirstByCpfOrderByVisitStartDesc(String cpf);
}
