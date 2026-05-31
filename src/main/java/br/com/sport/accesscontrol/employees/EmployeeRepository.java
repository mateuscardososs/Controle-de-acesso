package br.com.sport.accesscontrol.employees;

import br.com.sport.accesscontrol.integration.sync.SyncStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EmployeeRepository extends JpaRepository<Employee, UUID> {
    Optional<Employee> findByCpf(String cpf);
    Optional<Employee> findByCardNo(String cardNo);
    Optional<Employee> findFirstByFullNameIgnoreCase(String fullName);

    @Query("SELECT DISTINCT e FROM Employee e LEFT JOIN FETCH e.allowedAreas WHERE e.id = :id")
    Optional<Employee> findByIdWithAllowedAreas(@Param("id") UUID id);

    /** ACTIVE employees whose sync is pending or failed — bulk re-sync candidates. */
    List<Employee> findByStatusAndSyncStatusIn(EmployeeStatus status, Collection<SyncStatus> syncStatuses);
}
