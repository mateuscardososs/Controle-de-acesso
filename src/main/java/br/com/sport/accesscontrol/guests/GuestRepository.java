package br.com.sport.accesscontrol.guests;

import br.com.sport.accesscontrol.integration.sync.SyncStatus;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GuestRepository extends JpaRepository<Guest, UUID> {
    List<Guest> findByVisitStartLessThanEqualAndVisitEndGreaterThanEqual(Instant end, Instant start);
    List<Guest> findByStatusNotAndVisitEndBefore(GuestStatus status, Instant now);
    Optional<Guest> findFirstByCpfOrderByVisitStartDesc(String cpf);
    Optional<Guest> findFirstByCpfAndStatusOrderByVisitStartDesc(String cpf, GuestStatus status);
    Optional<Guest> findFirstByFullNameIgnoreCaseOrderByVisitStartDesc(String fullName);

    @Query("SELECT DISTINCT g FROM Guest g LEFT JOIN FETCH g.allowedAreas WHERE g.id = :id")
    Optional<Guest> findByIdWithAllowedAreas(@Param("id") UUID id);

    /** COMPLETED guests whose sync is pending or failed — bulk re-sync candidates. */
    List<Guest> findByStatusAndSyncStatusIn(GuestStatus status, Collection<SyncStatus> syncStatuses);

    /**
     * Reaper candidates: rows in {@code status} not touched since {@code threshold}. Locks each row
     * with {@code FOR UPDATE SKIP LOCKED} (Hibernate hint -2) so concurrent reapers / multiple
     * instances never claim the same guest twice. Must run inside a transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("SELECT g FROM Guest g WHERE g.syncStatus = :status AND g.updatedAt < :threshold ORDER BY g.updatedAt ASC")
    List<Guest> findReapableBySyncStatus(@Param("status") SyncStatus status,
                                         @Param("threshold") Instant threshold,
                                         Pageable pageable);

    List<Guest> findByStatus(GuestStatus status);

    boolean existsByIntelbrasCardNo(String intelbrasCardNo);
}
