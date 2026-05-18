package br.com.sport.accesscontrol.auth;

import br.com.sport.accesscontrol.auth.AuthDtos.LoginRequest;
import br.com.sport.accesscontrol.auth.AuthDtos.LoginResponse;
import br.com.sport.accesscontrol.auth.AuthDtos.MeResponse;
import br.com.sport.accesscontrol.auth.AuthDtos.RegisterRequest;
import br.com.sport.accesscontrol.users.User;
import br.com.sport.accesscontrol.users.UserRepository;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final DatabaseUserDetailsService userDetailsService;
    private final JwtService jwtService;

    public AuthService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            DatabaseUserDetailsService userDetailsService,
            JwtService jwtService
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.userDetailsService = userDetailsService;
        this.jwtService = jwtService;
    }

    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        var principal = (UserPrincipal) userDetailsService.loadUserByUsername(request.email());
        if (!passwordEncoder.matches(request.password(), principal.getPassword())) {
            throw new BadCredentialsException("Invalid credentials");
        }
        var user = userRepository.findByEmailIgnoreCase(principal.email())
                .orElseThrow(() -> new BadCredentialsException("Invalid credentials"));
        var token = jwtService.generateToken(principal);
        return new LoginResponse(token, "Bearer", jwtService.expirationSeconds(), MeResponse.from(user));
    }

    @Transactional
    public MeResponse register(RegisterRequest request) {
        if (userRepository.existsByEmailIgnoreCase(request.email())) {
            throw new IllegalArgumentException("Email already registered");
        }
        var user = new User(
                request.name(),
                request.email().toLowerCase(),
                passwordEncoder.encode(request.password()),
                request.role(),
                true
        );
        return MeResponse.from(userRepository.save(user));
    }

    @Transactional(readOnly = true)
    public MeResponse me(Authentication authentication) {
        var principal = (UserPrincipal) authentication.getPrincipal();
        var user = userRepository.findByEmailIgnoreCase(principal.email())
                .orElseThrow(() -> new BadCredentialsException("Invalid credentials"));
        return MeResponse.from(user);
    }
}
