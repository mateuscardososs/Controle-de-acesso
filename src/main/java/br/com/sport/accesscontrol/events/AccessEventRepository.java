package br.com.sport.accesscontrol.events;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.UUID;

public interface AccessEventRepository extends JpaRepository<AccessEvent, UUID> {
    long countByEventTimeBetween(Instant startInclusive, Instant endExclusive);

    long countByAccessResult(AccessResult accessResult);
}
