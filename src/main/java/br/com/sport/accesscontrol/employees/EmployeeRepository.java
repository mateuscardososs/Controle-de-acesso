package br.com.sport.accesscontrol.employees;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface EmployeeRepository extends JpaRepository<Employee, UUID> {
    Optional<Employee> findByCpf(String cpf);
    Optional<Employee> findByCardNo(String cardNo);
    Optional<Employee> findFirstByFullNameIgnoreCase(String fullName);
}
