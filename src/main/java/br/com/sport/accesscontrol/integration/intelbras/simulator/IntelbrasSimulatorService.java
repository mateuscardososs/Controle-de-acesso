package br.com.sport.accesscontrol.integration.intelbras.simulator;

import br.com.sport.accesscontrol.common.PersonType;
import br.com.sport.accesscontrol.common.ResourceNotFoundException;
import br.com.sport.accesscontrol.employees.EmployeeRepository;
import br.com.sport.accesscontrol.events.AccessEventResponse;
import br.com.sport.accesscontrol.events.AccessEventService;
import br.com.sport.accesscontrol.events.AccessEventSimulationRequest;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class IntelbrasSimulatorService {

    private final EmployeeRepository employeeRepository;
    private final AccessEventService accessEventService;

    public IntelbrasSimulatorService(EmployeeRepository employeeRepository, AccessEventService accessEventService) {
        this.employeeRepository = employeeRepository;
        this.accessEventService = accessEventService;
    }

    public AccessEventResponse simulate(IntelbrasAccessEventSimulatorRequest request) {
        var employee = employeeRepository.findByCpf(request.cpf())
                .orElseThrow(() -> new ResourceNotFoundException("Employee not found by cpf: " + request.cpf()));
        return accessEventService.simulate(new AccessEventSimulationRequest(
                PersonType.EMPLOYEE,
                employee.getId(),
                request.deviceId(),
                request.eventType(),
                request.result(),
                null,
                "INTELBRAS_SIMULATOR",
                Map.of("cpf", request.cpf(), "simulated", true)
        ));
    }
}
