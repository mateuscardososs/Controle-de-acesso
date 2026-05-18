package br.com.sport.accesscontrol.auth;

import br.com.sport.accesscontrol.users.User;
import br.com.sport.accesscontrol.users.UserRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public final class AuthDtos {

    private AuthDtos() {
    }

    public record LoginRequest(
            @NotBlank @Email String email,
            @NotBlank String password
    ) {
    }

    public record LoginResponse(
            String accessToken,
            String tokenType,
            long expiresInSeconds,
            MeResponse user
    ) {
    }

    public record RegisterRequest(
            @NotBlank String name,
            @NotBlank @Email String email,
            @NotBlank @Size(min = 8) String password,
            @NotNull UserRole role
    ) {
    }

    public record MeResponse(
            UUID id,
            String name,
            String email,
            UserRole role
    ) {
        public static MeResponse from(User user) {
            return new MeResponse(user.getId(), user.getName(), user.getEmail(), user.getRole());
        }
    }
}
