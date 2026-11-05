package com.ray.banking.web;

import com.ray.banking.exception.AccountNotFoundException;
import com.ray.banking.exception.IdempotencyKeyInProgressException;
import com.ray.banking.exception.IdempotencyKeyReusedException;
import com.ray.banking.exception.InsufficientFundsException;
import com.ray.banking.exception.InvalidTransactionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.time.Instant;

/**
 * Every error the API returns has the same JSON body, {@link ApiError}.
 *
 * <p>Domain exceptions map to their own codes below. Extending
 * {@link ResponseEntityExceptionHandler} brings in Spring MVC's own errors
 * (unknown path, wrong method, unsupported media type, a path variable that
 * isn't a number, ...); they are funnelled through
 * {@link #handleExceptionInternal} so they get the same body instead of an
 * empty one. Anything else is a bug or an outage: it is logged in full and
 * answered with a generic 500 that leaks no internals.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    public record ApiError(int status, String code, String message, Instant timestamp) {

        static ApiError of(HttpStatus status, String code, String message) {
            return new ApiError(status.value(), code, message, Instant.now());
        }
    }

    // ---------- domain errors ----------

    @ExceptionHandler(AccountNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(AccountNotFoundException ex) {
        return error(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(InsufficientFundsException.class)
    public ResponseEntity<ApiError> handleInsufficientFunds(InsufficientFundsException ex) {
        return error(HttpStatus.CONFLICT, "INSUFFICIENT_FUNDS", ex.getMessage());
    }

    @ExceptionHandler(IdempotencyKeyReusedException.class)
    public ResponseEntity<ApiError> handleKeyReused(IdempotencyKeyReusedException ex) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "IDEMPOTENCY_KEY_REUSED", ex.getMessage());
    }

    @ExceptionHandler(IdempotencyKeyInProgressException.class)
    public ResponseEntity<ApiError> handleKeyInProgress(IdempotencyKeyInProgressException ex) {
        return error(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_IN_PROGRESS", ex.getMessage());
    }

    @ExceptionHandler(InvalidTransactionException.class)
    public ResponseEntity<ApiError> handleInvalidTransaction(InvalidTransactionException ex) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_TRANSACTION", ex.getMessage());
    }

    // ---------- request errors raised by Spring MVC ----------

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getDefaultMessage())
                .findFirst()
                .orElse("Validation failed");
        return body(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message, headers);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return body(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Request body is missing or malformed", headers);
    }

    /** A path variable or parameter of the wrong type, such as {@code /api/accounts/abc}. */
    @Override
    protected ResponseEntity<Object> handleTypeMismatch(
            TypeMismatchException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String message = "'%s' is not a valid value for %s".formatted(ex.getValue(), ex.getPropertyName());
        return body(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER", message, headers);
    }

    /**
     * Every other Spring MVC error ends here: 404 for an unknown path, 405 for
     * a wrong method, 415 for a wrong content type, and so on. The code is the
     * status name, the message Spring's own description. Headers such as
     * {@code Allow} on a 405 are kept.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        HttpStatus status = HttpStatus.valueOf(statusCode.value());
        String message = body instanceof ProblemDetail problem && problem.getDetail() != null
                ? problem.getDetail()
                : status.getReasonPhrase();
        return body(status, status.name(), message, headers);
    }

    // ---------- failures on our side ----------

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiError> handleDatabaseError(DataAccessException ex) {
        log.error("Database error", ex);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "DATABASE_ERROR",
                "The request could not be completed; please try again later");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex) {
        log.error("Unexpected error", ex);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Something went wrong on our side");
    }

    private static ResponseEntity<ApiError> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(ApiError.of(status, code, message));
    }

    private static ResponseEntity<Object> body(HttpStatus status, String code, String message, HttpHeaders headers) {
        return ResponseEntity.status(status).headers(headers).body(ApiError.of(status, code, message));
    }
}
