package br.com.sport.accesscontrol.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;
import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ResourceNotFoundException.class)
    ResponseEntity<ApiError> handleNotFound(ResourceNotFoundException exception) {
        return build(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> handleNoResourceFound(NoResourceFoundException exception) {
        return build(HttpStatus.NOT_FOUND, "Endpoint not found.");
    }

    @ExceptionHandler(BadCredentialsException.class)
    ResponseEntity<ApiError> handleBadCredentials() {
        return build(HttpStatus.UNAUTHORIZED, "Invalid credentials");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiError> handleIllegalArgument(IllegalArgumentException exception) {
        return build(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    @ExceptionHandler(UnprocessableEntityException.class)
    ResponseEntity<ApiError> handleUnprocessableEntity(UnprocessableEntityException exception) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, exception.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> handleUnreadablePayload() {
        return build(HttpStatus.BAD_REQUEST, "Request body is invalid or malformed.");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException exception) {
        return build(HttpStatus.BAD_REQUEST, "Invalid value for parameter: " + exception.getName());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiError> handleConflict(DataIntegrityViolationException exception) {
        return build(HttpStatus.CONFLICT, "Resource conflicts with existing data or database constraints.");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiError> handleMethodNotSupported(HttpRequestMethodNotSupportedException exception) {
        return build(HttpStatus.METHOD_NOT_ALLOWED, "HTTP method is not supported for this endpoint.");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> handleUnexpected(Exception exception) {
        // NUNCA engolir a causa real — sem isto o erro vira um 500 genérico sem rastro nos logs.
        log.error("unhandled_exception type={} message={}",
                exception.getClass().getName(), exception.getMessage(), exception);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected server error.");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException exception) {
        var details = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> "Field '" + error.getField() + "' " + error.getDefaultMessage())
                .toList();
        return ResponseEntity.badRequest()
                .body(new ApiError(Instant.now(), 400, "Validation failed", details));
    }

    private ResponseEntity<ApiError> build(HttpStatus status, String detail) {
        return ResponseEntity.status(status)
                .body(new ApiError(Instant.now(), status.value(), status.getReasonPhrase(), List.of(detail)));
    }
}
