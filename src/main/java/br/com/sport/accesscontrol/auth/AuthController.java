package br.com.sport.accesscontrol.auth;

import br.com.sport.accesscontrol.auth.AuthDtos.LoginRequest;
import br.com.sport.accesscontrol.auth.AuthDtos.LoginResponse;
import br.com.sport.accesscontrol.auth.AuthDtos.MeResponse;
import br.com.sport.accesscontrol.auth.AuthDtos.RegisterRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    MeResponse register(@Valid @RequestBody RegisterRequest request) {
        return authService.register(request);
    }

    @GetMapping("/me")
    MeResponse me(Authentication authentication) {
        return authService.me(authentication);
    }
}
