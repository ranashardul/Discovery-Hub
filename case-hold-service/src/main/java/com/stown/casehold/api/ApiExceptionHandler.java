package com.stown.casehold.api;

import com.stown.casehold.service.CaseNotFoundException;
import com.stown.casehold.service.HoldNotFoundException;
import com.stown.casehold.service.IllegalHoldStateException;
import com.stown.casehold.service.SavedSearchNameTakenException;
import com.stown.casehold.service.SavedSearchNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientException;

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

    @ExceptionHandler({
            CaseNotFoundException.class,
            HoldNotFoundException.class,
            SavedSearchNotFoundException.class
    })
    public ResponseEntity<ApiErrorResponse> handleNotFound(
            RuntimeException exception,
            HttpServletRequest request
    ) {
        return build(HttpStatus.NOT_FOUND, exception.getMessage(), request, List.of());
    }

    @ExceptionHandler({
            IllegalHoldStateException.class,
            SavedSearchNameTakenException.class
    })
    public ResponseEntity<ApiErrorResponse> handleIllegalState(
            RuntimeException exception,
            HttpServletRequest request
    ) {
        return build(HttpStatus.CONFLICT, exception.getMessage(), request, List.of());
    }

    /**
     * The search service is unreachable or failed, so a hold scope preview
     * cannot be answered. Reported as 503 rather than degraded to zero: a
     * preview showing "0 messages" reads as "this rule matches nothing" and
     * would talk a reviewer out of a hold they needed.
     */
    @ExceptionHandler(RestClientException.class)
    public ResponseEntity<ApiErrorResponse> handleSearchUnavailable(
            RestClientException exception,
            HttpServletRequest request
    ) {
        log.warn("Search service unavailable for a hold scope preview: {}", exception.getMessage());

        return build(
                HttpStatus.SERVICE_UNAVAILABLE,
                "The search service could not be reached, so the hold scope cannot be counted",
                request,
                List.of()
        );
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> handleBadRequest(
            IllegalArgumentException exception,
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
