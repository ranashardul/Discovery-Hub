package com.stown.exportaudit.api;

import com.stown.exportaudit.service.ExportJobNotFoundException;
import com.stown.exportaudit.service.ExportStorageException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.List;

@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(
            MethodArgumentNotValidException exception,
            HttpServletRequest request
    ) {
        List<ApiErrorResponse.FieldViolation> violations = exception
                .getBindingResult()
                .getFieldErrors()
                .stream()
                .map(error -> new ApiErrorResponse.FieldViolation(
                        error.getField(),
                        error.getDefaultMessage()
                ))
                .toList();

        return build(
                HttpStatus.BAD_REQUEST,
                "Request validation failed",
                request,
                violations
        );
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleUnreadableBody(
            HttpMessageNotReadableException exception,
            HttpServletRequest request
    ) {
        return build(
                HttpStatus.BAD_REQUEST,
                "Request body is missing or malformed",
                request,
                List.of()
        );
    }

    @ExceptionHandler(ExportJobNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNotFound(
            ExportJobNotFoundException exception,
            HttpServletRequest request
    ) {
        return build(HttpStatus.NOT_FOUND, exception.getMessage(), request, List.of());
    }

    @ExceptionHandler(ExportStorageException.class)
    public ResponseEntity<ApiErrorResponse> handleStorageError(
            ExportStorageException exception,
            HttpServletRequest request
    ) {
        return build(HttpStatus.BAD_GATEWAY, exception.getMessage(), request, List.of());
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<ApiErrorResponse> handleBadRequest(
            RuntimeException exception,
            HttpServletRequest request
    ) {
        return build(HttpStatus.BAD_REQUEST, exception.getMessage(), request, List.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(
            Exception exception,
            HttpServletRequest request
    ) {
        log.error("Unhandled error on {}", request.getRequestURI(), exception);

        return build(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Unexpected error while processing the request",
                request,
                List.of()
        );
    }

    private ResponseEntity<ApiErrorResponse> build(
            HttpStatus status,
            String message,
            HttpServletRequest request,
            List<ApiErrorResponse.FieldViolation> violations
    ) {
        return ResponseEntity.status(status).body(new ApiErrorResponse(
                Instant.now(),
                status.value(),
                status.getReasonPhrase(),
                message,
                request.getRequestURI(),
                violations
        ));
    }
}
