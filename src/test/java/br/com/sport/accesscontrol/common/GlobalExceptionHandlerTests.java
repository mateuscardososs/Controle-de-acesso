package br.com.sport.accesscontrol.common;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTests {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void missingEndpointsReturnNotFoundInsteadOfUnexpectedServerError() {
        var response = handler.handleNoResourceFound(new NoResourceFoundException(
                HttpMethod.POST,
                "/api/simulator/access-event"
        ));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(404);
    }
}
