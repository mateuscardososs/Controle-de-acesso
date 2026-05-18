package br.com.sport.accesscontrol.employees;

import br.com.sport.accesscontrol.audit.AuditService;
import br.com.sport.accesscontrol.common.ResourceNotFoundException;
import br.com.sport.accesscontrol.common.events.EmployeeCreatedEvent;
import br.com.sport.accesscontrol.common.events.EmployeeDeactivatedEvent;
import br.com.sport.accesscontrol.common.events.EmployeeUpdatedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class EmployeeService {

    private final EmployeeRepository employeeRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final AuditService auditService;

    public EmployeeService(EmployeeRepository employeeRepository, ApplicationEventPublisher eventPublisher,
                           AuditService auditService) {
        this.employeeRepository = employeeRepository;
        this.eventPublisher = eventPublisher;
        this.auditService = auditService;
    }

    @Transactional
    public EmployeeResponse create(EmployeeRequest request) {
        var employee = new Employee(
                request.fullName(),
                request.cpf(),
                request.email(),
                request.phone(),
                request.registrationNumber(),
                request.facePhotoUrl(),
                request.status(),
                request.accessValidFrom(),
                request.accessValidUntil()
        );
        var saved = employeeRepository.save(employee);
        auditService.record("EMPLOYEE_CREATED", "Employee", saved.getId(), Map.of("cpf", saved.getCpf()),
                Map.of(), employeeSnapshot(saved));
        eventPublisher.publishEvent(new EmployeeCreatedEvent(saved.getId()));
        return EmployeeResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public List<EmployeeResponse> findAll() {
        return employeeRepository.findAll().stream().map(EmployeeResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public EmployeeResponse findById(UUID id) {
        return EmployeeResponse.from(getEmployee(id));
    }

    @Transactional
    public EmployeeResponse update(UUID id, EmployeeRequest request) {
        var employee = getEmployee(id);
        var oldData = employeeSnapshot(employee);
        employee.setFullName(request.fullName());
        employee.setCpf(request.cpf());
        employee.setEmail(request.email());
        employee.setPhone(request.phone());
        employee.setRegistrationNumber(request.registrationNumber());
        employee.setFacePhotoUrl(request.facePhotoUrl());
        employee.setStatus(request.status() == null ? employee.getStatus() : request.status());
        employee.setAccessValidFrom(request.accessValidFrom());
        employee.setAccessValidUntil(request.accessValidUntil());
        auditService.record("EMPLOYEE_UPDATED", "Employee", employee.getId(), Map.of("cpf", employee.getCpf()),
                oldData, employeeSnapshot(employee));
        eventPublisher.publishEvent(new EmployeeUpdatedEvent(employee.getId()));
        return EmployeeResponse.from(employee);
    }

    @Transactional
    public EmployeeResponse deactivate(UUID id) {
        var employee = getEmployee(id);
        var oldData = employeeSnapshot(employee);
        employee.setStatus(EmployeeStatus.INACTIVE);
        auditService.record("EMPLOYEE_DEACTIVATED", "Employee", employee.getId(), Map.of("cpf", employee.getCpf()),
                oldData, employeeSnapshot(employee));
        eventPublisher.publishEvent(new EmployeeDeactivatedEvent(employee.getId()));
        return EmployeeResponse.from(employee);
    }

    private Employee getEmployee(UUID id) {
        return employeeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Employee not found: " + id));
    }

    private Map<String, Object> employeeSnapshot(Employee employee) {
        return Map.of(
                "id", employee.getId(),
                "fullName", employee.getFullName(),
                "cpf", employee.getCpf(),
                "status", employee.getStatus()
        );
    }
}
