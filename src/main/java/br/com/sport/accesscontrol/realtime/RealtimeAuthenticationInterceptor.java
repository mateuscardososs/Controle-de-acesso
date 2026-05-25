package br.com.sport.accesscontrol.realtime;

import br.com.sport.accesscontrol.auth.JwtService;
import br.com.sport.accesscontrol.config.WebSocketConfig;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.stereotype.Component;

import java.security.Principal;
import java.util.Set;

@Component
public class RealtimeAuthenticationInterceptor implements ChannelInterceptor {

    private static final Set<String> REALTIME_TOPICS = Set.of(
            WebSocketConfig.ACCESS_EVENTS_TOPIC,
            WebSocketConfig.DEVICE_STATUS_TOPIC,
            WebSocketConfig.SYSTEM_ALERTS_TOPIC,
            WebSocketConfig.INTEGRATION_SYNC_TOPIC
    );
    private static final Set<String> ALLOWED_AUTHORITIES = Set.of(
            "ROLE_ADMIN",
            "ROLE_HR",
            "ROLE_SECURITY_VIEWER"
    );

    private final JwtService jwtService;
    private final UserDetailsService userDetailsService;

    public RealtimeAuthenticationInterceptor(JwtService jwtService, UserDetailsService userDetailsService) {
        this.jwtService = jwtService;
        this.userDetailsService = userDetailsService;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        var accessor = StompHeaderAccessor.wrap(message);
        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            var principal = authenticate(accessor);
            if (principal == null) {
                return null;
            }
            accessor.setUser(principal);
        }
        if (StompCommand.SUBSCRIBE.equals(accessor.getCommand()) && isRealtimeTopic(accessor.getDestination())) {
            if (!hasRealtimeRole(accessor.getUser())) {
                return null;
            }
        }
        return MessageBuilder.createMessage(message.getPayload(), accessor.getMessageHeaders());
    }

    private Principal authenticate(StompHeaderAccessor accessor) {
        try {
            var authorization = authorizationHeader(accessor);
            if (authorization == null || !authorization.startsWith("Bearer ")) {
                throw new BadCredentialsException("Realtime JWT is required.");
            }

            var email = jwtService.subject(authorization.substring("Bearer ".length()));
            var userDetails = userDetailsService.loadUserByUsername(email);
            if (!userDetails.isEnabled() || !hasRealtimeRole(userDetails)) {
                return null;
            }
            return new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
        } catch (AuthenticationException | AccessDeniedException exception) {
            return null;
        }
    }

    private String authorizationHeader(StompHeaderAccessor accessor) {
        var authorization = accessor.getFirstNativeHeader("Authorization");
        return authorization != null ? authorization : accessor.getFirstNativeHeader("authorization");
    }

    private boolean isRealtimeTopic(String destination) {
        return destination != null && REALTIME_TOPICS.contains(destination);
    }

    private boolean hasRealtimeRole(Principal principal) {
        return principal instanceof Authentication authentication && hasRealtimeRole(authentication);
    }

    private boolean hasRealtimeRole(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .anyMatch(authority -> ALLOWED_AUTHORITIES.contains(authority.getAuthority()));
    }

    private boolean hasRealtimeRole(UserDetails userDetails) {
        return userDetails.getAuthorities().stream()
                .anyMatch(authority -> ALLOWED_AUTHORITIES.contains(authority.getAuthority()));
    }
}
