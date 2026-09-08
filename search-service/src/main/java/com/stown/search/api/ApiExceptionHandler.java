package com.stown.search.api;

import com.stown.search.service.InvalidSearchRequestException;
import com.stown.search.service.SearchExecutionException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Instant;
import java.util.List;

@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(InvalidSearchRequestException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidSearch(
            InvalidSearchRequestException exception,
            HttpServletRequest request
    ) {
        return build(
                HttpStatus.BAD_REQUEST,
                "Request validation failed",
                request,
                List.of(new ApiErrorResponse.FieldViolation(
                        exception.getField(),
                        exception.getMessage()
                ))
        );
    }

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

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiErrorResponse> handleMissingParameter(
            MissingServletRequestParameterException exception,
            HttpServletRequest request
    ) {
        return build(
                HttpStatus.BAD_REQUEST,
                "Request validation failed",
                request,
                List.of(new ApiErrorResponse.FieldViolation(
                        exception.getParameterName(),
                        "Parameter is required"
                ))
        );
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiErrorResponse> handleTypeMismatch(
            MethodArgumentTypeMismatchException exception,
            HttpServletRequest request
    ) {
        return build(
                HttpStatus.BAD_REQUEST,
                "Request validation failed",
                request,
                List.of(new ApiErrorResponse.FieldViolation(
                        exception.getName(),
                        "Parameter value is not valid"
                ))
        );
    }

    @ExceptionHandler(SearchExecutionException.class)
    public ResponseEntity<ApiErrorResponse> handleSearchExecution(
            SearchExecutionException exception,
            HttpServletRequest request
    ) {
        log.error("Search backend error on {}", request.getRequestURI(), exception);

        return build(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Search backend is unavailable",
                request,
                List.of()
        );
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
