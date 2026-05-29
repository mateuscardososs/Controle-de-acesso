package br.com.sport.accesscontrol.employees;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface EmployeeRepository extends JpaRepository<Employee, UUID> {
    Optional<Employee> findByCpf(String cpf);
    Optional<Employee> findByCardNo(String cardNo);
    Optional<Employee> findFirstByFullNameIgnoreCase(String fullName);

    @Query("SELECT e FROM Employee e LEFT JOIN FETCH e.allowedAreas WHERE e.id = :id")
    Optional<Employee> findByIdWithAllowedAreas(@Param("id") UUID id);
}
