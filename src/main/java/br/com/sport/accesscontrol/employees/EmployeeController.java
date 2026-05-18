package br.com.sport.accesscontrol.employees;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/employees")
public class EmployeeController {

    private final EmployeeService employeeService;

    public EmployeeController(EmployeeService employeeService) {
        this.employeeService = employeeService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    EmployeeResponse create(@Valid @RequestBody EmployeeRequest request) {
        return employeeService.create(request);
    }

    @GetMapping
    List<EmployeeResponse> findAll() {
        return employeeService.findAll();
    }

    @GetMapping("/{id}")
    EmployeeResponse findById(@PathVariable UUID id) {
        return employeeService.findById(id);
    }

    @PutMapping("/{id}")
    EmployeeResponse update(@PathVariable UUID id, @Valid @RequestBody EmployeeRequest request) {
        return employeeService.update(id, request);
    }

    @PatchMapping("/{id}/deactivate")
    EmployeeResponse deactivate(@PathVariable UUID id) {
        return employeeService.deactivate(id);
    }
}
