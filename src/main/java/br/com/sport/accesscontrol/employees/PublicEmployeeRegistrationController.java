package br.com.sport.accesscontrol.employees;

import br.com.sport.accesscontrol.employees.PublicEmployeeRegistrationDtos.CpfCheckResponse;
import br.com.sport.accesscontrol.employees.PublicEmployeeRegistrationDtos.PublicEmployeeRegistrationResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping({"/api/public/employees", "/public/employees"})
public class PublicEmployeeRegistrationController {

    private final EmployeeService employeeService;

    public PublicEmployeeRegistrationController(EmployeeService employeeService) {
        this.employeeService = employeeService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    PublicEmployeeRegistrationResponse register(
            @RequestParam(name = "full_name", required = false) String fullNameSnake,
            @RequestParam(name = "fullName", required = false) String fullNameCamel,
            @RequestParam String cpf,
            @RequestParam String phone,
            @RequestParam String email,
            @RequestPart(name = "face_photo", required = false) MultipartFile facePhotoSnake,
            @RequestPart(name = "facePhoto", required = false) MultipartFile facePhotoCamel,
            @RequestParam(name = "face_photo_base64", required = false) String facePhotoBase64Snake,
            @RequestParam(name = "facePhotoBase64", required = false) String facePhotoBase64Camel
    ) {
        return employeeService.publicRegister(
                firstText(fullNameSnake, fullNameCamel),
                cpf,
                phone,
                email,
                facePhotoSnake == null || facePhotoSnake.isEmpty() ? facePhotoCamel : facePhotoSnake,
                firstText(facePhotoBase64Snake, facePhotoBase64Camel)
        );
    }

    @GetMapping("/check-cpf")
    CpfCheckResponse checkCpf(@RequestParam String cpf) {
        return employeeService.checkPublicCpf(cpf);
    }

    private String firstText(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second;
    }
}
