package br.com.sport.accesscontrol.realtime;

import br.com.sport.accesscontrol.auth.JwtService;
import br.com.sport.accesscontrol.auth.UserPrincipal;
import br.com.sport.accesscontrol.config.WebSocketConfig;
import br.com.sport.accesscontrol.users.User;
import br.com.sport.accesscontrol.users.UserRole;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RealtimeAuthenticationInterceptorTests {

    private final JwtService jwtService = new JwtService(new ObjectMapper(), "test-secret-with-enough-length", 28800);
    private final UserDetailsService userDetailsService = mock(UserDetailsService.class);
    private final RealtimeAuthenticationInterceptor interceptor = new RealtimeAuthenticationInterceptor(jwtService, userDetailsService);

    @Test
    void connectWithoutTokenIsRejected() {
        assertThat(interceptor.preSend(message(StompCommand.CONNECT, null, null), mock(MessageChannel.class))).isNull();
    }

    @Test
    void connectWithInvalidTokenIsRejected() {
        assertThat(interceptor.preSend(message(StompCommand.CONNECT, null, "Bearer invalid"), mock(MessageChannel.class))).isNull();
    }

    @Test
    void connectWithValidRealtimeRoleIsAcceptedAndSetsPrincipal() {
        var principal = principal("admin@empresa.local", UserRole.ADMIN, true);
        when(userDetailsService.loadUserByUsername("admin@empresa.local")).thenReturn(principal);

        Message<?> result = interceptor.preSend(
                message(StompCommand.CONNECT, null, "Bearer " + jwtService.generateToken(principal)),
                mock(MessageChannel.class)
        );

        var accessor = StompHeaderAccessor.wrap(result);
        assertThat(accessor.getUser()).isInstanceOf(UsernamePasswordAuthenticationToken.class);
        assertThat(accessor.getUser().getName()).isEqualTo("admin@empresa.local");
    }

    @Test
    void disabledUserIsRejected() {
        var principal = principal("disabled@empresa.local", UserRole.HR, false);
        when(userDetailsService.loadUserByUsername("disabled@empresa.local")).thenReturn(principal);

        assertThat(interceptor.preSend(
                message(StompCommand.CONNECT, null, "Bearer " + jwtService.generateToken(principal)),
                mock(MessageChannel.class)
        )).isNull();
    }

    @Test
    void realtimeTopicSubscribeWithoutAllowedRoleIsRejected() {
        var accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination(WebSocketConfig.ACCESS_EVENTS_TOPIC);
        accessor.setUser(new UsernamePasswordAuthenticationToken("user", null, List.of()));

        assertThat(interceptor.preSend(MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders()), mock(MessageChannel.class))).isNull();
    }

    private Message<byte[]> message(StompCommand command, String destination, String authorization) {
        var accessor = StompHeaderAccessor.create(command);
        if (destination != null) {
            accessor.setDestination(destination);
        }
        if (authorization != null) {
            accessor.setNativeHeader("Authorization", authorization);
        }
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private UserPrincipal principal(String email, UserRole role, boolean active) {
        var user = new User("User", email, "{noop}secret", role, active);
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        return new UserPrincipal(user);
    }
}
