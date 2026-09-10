package com.stown.ingestion.api;

import com.stown.ingestion.service.DispositionDisabledException;
import com.stown.ingestion.service.DispositionInProgressException;
import com.stown.ingestion.service.HeldMessageDeletionException;
import com.stown.ingestion.service.InvalidAttachmentException;
import com.stown.ingestion.service.MessageNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import software.amazon.awssdk.core.exception.SdkException;

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

    /**
     * Two distinct MessageNotFoundException types exist: the read API throws
     * the one in this package, the disposition path throws the one in
     * {@code service}. Both are the same answer to the caller, so both map
     * here. The api one is fully qualified because the simple name is taken by
     * the import above.
     */
    @ExceptionHandler({
            IngestionRequestNotFoundException.class,
            MessageNotFoundException.class,
            com.stown.ingestion.api.MessageNotFoundException.class
    })
    public ResponseEntity<ApiErrorResponse> handleNotFound(
            RuntimeException exception,
            HttpServletRequest request
    ) {
        return build(HttpStatus.NOT_FOUND, exception.getMessage(), request, List.of());
    }

    /**
     * A legal hold blocks deletion. Answering 409 rather than 403 says the
     * request is valid but conflicts with the current state of the resource,
     * and the hold ids let the caller see what is protecting it.
     */
    @ExceptionHandler(HeldMessageDeletionException.class)
    public ResponseEntity<ApiErrorResponse> handleHeldMessage(
            HeldMessageDeletionException exception,
            HttpServletRequest request
    ) {
        log.warn(
                "Refused deletion of held message messageId={} holdCount={} holdIds={}",
                exception.getMessageId(),
                exception.getHoldCount(),
                exception.getHoldIds()
        );

        String detail = exception.getHoldIds().isEmpty()
                ? exception.getMessage()
                : exception.getMessage() + "; holds: " + String.join(", ", exception.getHoldIds());

        return build(HttpStatus.CONFLICT, detail, request, List.of());

    }

    /**
     * A disposition run was requested but cannot start. Both cases are a
     * conflict with current state rather than a bad request: the pass is
     * already running, or the environment has disposition switched off.
     */
    @ExceptionHandler({
            DispositionInProgressException.class,
            DispositionDisabledException.class
    })
    public ResponseEntity<ApiErrorResponse> handleDispositionUnavailable(
            RuntimeException exception,
            HttpServletRequest request
    ) {
        log.warn("Refused disposition request: {}", exception.getMessage());

        return build(HttpStatus.CONFLICT, exception.getMessage(), request, List.of());
    }

    @ExceptionHandler({
            InvalidAttachmentException.class,
            IllegalArgumentException.class
    })
    public ResponseEntity<ApiErrorResponse> handleBadRequest(
            RuntimeException exception,
            HttpServletRequest request
    ) {
        return build(HttpStatus.BAD_REQUEST, exception.getMessage(), request, List.of());
    }

    /**
     * The object store is a hard dependency for attachments. Surfacing it as
     * 503 tells the caller the request is retryable, rather than hiding an
     * infrastructure or credentials problem behind a generic 500.
     */
    @ExceptionHandler(SdkException.class)
    public ResponseEntity<ApiErrorResponse> handleStorageUnavailable(
            SdkException exception,
            HttpServletRequest request
    ) {
        log.error("Attachment storage failure on {}", request.getRequestURI(), exception);

        return build(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Attachment storage is unavailable: " + rootMessage(exception),
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

    /** First line of the underlying message, without the SDK request IDs. */
    private String rootMessage(Throwable throwable) {
        String message = throwable.getMessage();

        if (message == null || message.isBlank()) {
            return throwable.getClass().getSimpleName();
        }

        int marker = message.indexOf(" (Service:");

        return marker > 0 ? message.substring(0, marker) : message;
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
