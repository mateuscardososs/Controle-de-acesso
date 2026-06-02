package br.com.sport.accesscontrol.employees;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
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

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    EmployeeResponse createMultipart(@RequestParam String fullName,
                                     @RequestParam String cpf,
                                     @RequestParam String email,
                                     @RequestParam(required = false) String phone,
                                     @RequestParam(required = false) String registrationNumber,
                                     @RequestParam(required = false) String cardNo,
                                     @RequestParam String password,
                                     @RequestParam br.com.sport.accesscontrol.users.UserRole role,
                                     @RequestParam(required = false) EmployeeStatus status,
                                     @RequestParam(required = false) Instant accessValidFrom,
                                     @RequestParam(required = false) Instant accessValidUntil,
                                     @RequestPart(required = false) MultipartFile facePhoto) {
        return employeeService.create(new EmployeeRequest(
                fullName,
                cpf,
                email,
                phone,
                registrationNumber,
                cardNo,
                null,
                password,
                role,
                status,
                accessValidFrom,
                accessValidUntil
        ), facePhoto);
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

    @PatchMapping("/{id}")
    EmployeeResponse patch(@PathVariable UUID id, @Valid @RequestBody EmployeePatchRequest request) {
        return employeeService.patch(id, request);
    }

    @PatchMapping("/{id}/deactivate")
    EmployeeResponse deactivate(@PathVariable UUID id) {
        return employeeService.deactivate(id);
    }

    @PostMapping("/{id}/sync")
    EmployeeResponse sync(@PathVariable UUID id) {
        return employeeService.requestSync(id);
    }
}
