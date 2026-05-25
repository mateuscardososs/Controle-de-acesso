package br.com.sport.accesscontrol.common;

import br.com.sport.accesscontrol.auth.UserPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Component
public class RequestContext {

    public String correlationId() {
        var value = MDC.get(CorrelationId.MDC_KEY);
        return value == null ? "system" : value;
    }

    public String actorIp() {
        var attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletRequestAttributes) {
            HttpServletRequest request = servletRequestAttributes.getRequest();
            return request.getRemoteAddr();
        }
        return "system";
    }

    public Optional<UUID> actorUserId() {
        return principal().map(UserPrincipal::id);
    }

    public Map<String, Object> actorMetadata() {
        return principal()
                .<Map<String, Object>>map(principal -> Map.of(
                        "actorUserId", principal.id(),
                        "actorEmail", principal.email(),
                        "actorName", principal.name(),
                        "actorRoles", principal.getAuthorities().stream()
                                .map(Object::toString)
                                .toList()
                ))
                .orElseGet(Map::of);
    }

    private Optional<UserPrincipal> principal() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof UserPrincipal principal)) {
            return Optional.empty();
        }
        return Optional.of(principal);
    }
}
