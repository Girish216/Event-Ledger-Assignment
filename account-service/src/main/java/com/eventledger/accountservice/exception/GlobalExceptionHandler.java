package com.eventledger.accountservice.exception;

import com.eventledger.accountservice.dto.ErrorResponse;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(AccountNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(AccountNotFoundException ex, HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, ex.getMessage(), request);
    }

    @ExceptionHandler(EventIdConflictException.class)
    public ResponseEntity<ErrorResponse> handleConflict(EventIdConflictException ex, HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, ex.getMessage(), request);
    }

    @ExceptionHandler(ConflictingDuplicateException.class)
    public ResponseEntity<ErrorResponse> handleConflictingDuplicate(ConflictingDuplicateException ex, HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, ex.getMessage(), request);
    }

    @ExceptionHandler(CurrencyMismatchException.class)
    public ResponseEntity<ErrorResponse> handleCurrencyMismatch(CurrencyMismatchException ex, HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, ex.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return error(HttpStatus.BAD_REQUEST, message, request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex, HttpServletRequest request) {
        // getMostSpecificCause() walks past InvalidFormatException down to the raw parser
        // exception (e.g. DateTimeParseException), losing the field name it carries - so look
        // for InvalidFormatException specifically instead of taking the deepest cause.
        InvalidFormatException formatEx = findCause(ex, InvalidFormatException.class);
        if (formatEx != null && !formatEx.getPath().isEmpty()) {
            String field = formatEx.getPath().get(formatEx.getPath().size() - 1).getFieldName();
            String message = field + ": invalid value '" + formatEx.getValue() + "', expected " + expectedTypeHint(formatEx.getTargetType());
            return error(HttpStatus.BAD_REQUEST, message, request);
        }
        return error(HttpStatus.BAD_REQUEST, "Malformed request body: " + ex.getMostSpecificCause().getMessage(), request);
    }

    private static <T extends Throwable> T findCause(Throwable throwable, Class<T> type) {
        for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) {
                return type.cast(cause);
            }
        }
        return null;
    }

    private static String expectedTypeHint(Class<?> targetType) {
        if (targetType == Instant.class) {
            return "an ISO-8601 timestamp, e.g. 2026-05-15T10:00:00Z";
        }
        if (targetType != null && targetType.isEnum()) {
            return "one of " + java.util.Arrays.toString(targetType.getEnumConstants());
        }
        return "a valid " + (targetType == null ? "value" : targetType.getSimpleName());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error", request);
    }

    private ResponseEntity<ErrorResponse> error(HttpStatus status, String message, HttpServletRequest request) {
        ErrorResponse body = new ErrorResponse(Instant.now(), status.value(), status.getReasonPhrase(), message, request.getRequestURI());
        return ResponseEntity.status(status).body(body);
    }
}
