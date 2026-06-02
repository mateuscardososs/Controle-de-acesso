package br.com.sport.accesscontrol.employees;

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

public interface EmployeeRepository extends JpaRepository<Employee, UUID> {
    Optional<Employee> findByCpf(String cpf);
    boolean existsByCpf(String cpf);
    Optional<Employee> findByCardNo(String cardNo);
    Optional<Employee> findFirstByFullNameIgnoreCase(String fullName);

    @Query("SELECT DISTINCT e FROM Employee e LEFT JOIN FETCH e.allowedAreas WHERE e.id = :id")
    Optional<Employee> findByIdWithAllowedAreas(@Param("id") UUID id);

    /** ACTIVE employees whose sync is pending or failed — bulk re-sync candidates. */
    List<Employee> findByStatusAndSyncStatusIn(EmployeeStatus status, Collection<SyncStatus> syncStatuses);

    /**
     * Reaper candidates: rows in {@code status} not touched since {@code threshold}. Locks each row
     * with {@code FOR UPDATE SKIP LOCKED} (Hibernate hint -2) so concurrent reapers / multiple
     * instances never claim the same person twice. Must run inside a transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("SELECT e FROM Employee e WHERE e.syncStatus = :status AND e.updatedAt < :threshold ORDER BY e.updatedAt ASC")
    List<Employee> findReapableBySyncStatus(@Param("status") SyncStatus status,
                                            @Param("threshold") Instant threshold,
                                            Pageable pageable);

    List<Employee> findByStatus(EmployeeStatus status);

    boolean existsByIntelbrasCardNo(String intelbrasCardNo);
}
