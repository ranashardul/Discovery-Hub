package com.stown.exportaudit.api;

import com.stown.exportaudit.domain.ExportJobDocument;
import com.stown.exportaudit.domain.ExportScope;
import com.stown.exportaudit.service.ExportJobNotFoundException;
import com.stown.exportaudit.service.ExportJobService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/exports")
@RequiredArgsConstructor
public class ExportController {

    private final ExportJobService exportJobService;

    /**
     * Accepts an export request, creates a {@code QUEUED} job, publishes an
     * {@code export.requested} event and returns immediately. The package is
     * assembled asynchronously by the worker.
     */
    @PostMapping
    public ResponseEntity<ExportJobResponse> requestExport(
            @Valid @RequestBody ExportRequest request
    ) {
        ExportScope scope = parseScope(request.getScope());

        ExportJobDocument job = exportJobService.createJob(
                request.getCaseId(),
                request.getHoldId(),
                scope,
                request.getRequestedBy(),
                request.getCommunicationType(),
                request.getSender(),
                request.getThreadId(),
                request.getFromTimestamp(),
                request.getToTimestamp()
        );

        return ResponseEntity.accepted().body(ExportJobResponse.from(job));
    }

    @GetMapping("/{exportId}")
    public ResponseEntity<ExportJobResponse> getExport(
            @PathVariable String exportId
    ) {
        return exportJobService.findById(exportId)
                .map(ExportJobResponse::from)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new ExportJobNotFoundException(exportId));
    }

    @GetMapping
    public ResponseEntity<List<ExportJobResponse>> getExportsForCase(
            @RequestParam String caseId
    ) {
        List<ExportJobResponse> jobs = exportJobService.findByCaseId(caseId).stream()
                .map(ExportJobResponse::from)
                .toList();

        return ResponseEntity.ok(jobs);
    }

    @PostMapping("/{exportId}/retry")
    public ResponseEntity<ExportJobResponse> retryExport(
            @PathVariable String exportId
    ) {
        ExportJobDocument job = exportJobService.retry(exportId);

        return ResponseEntity.accepted().body(ExportJobResponse.from(job));
    }

    /**
     * Returns an expiring presigned download URL for a completed export
     * package.
     */
    @GetMapping("/{exportId}/download")
    public ResponseEntity<DownloadUrlResponse> getDownloadUrl(
            @PathVariable String exportId
    ) {
        String url = exportJobService.getDownloadUrl(exportId);

        ExportJobDocument job = exportJobService.findById(exportId).orElseThrow();

        return ResponseEntity.ok(new DownloadUrlResponse(
                exportId,
                url,
                job.getS3Bucket(),
                job.getS3Key()
        ));
    }

    /**
     * Verifies a completed export by recomputing the checksum of every item
     * from the stored package and comparing each against the manifest, plus
     * recomputing the package-level checksum. Any tampering is reported per
     * item.
     */
    @PostMapping("/{exportId}/verify")
    public ResponseEntity<VerifyResponse> verifyExport(
            @PathVariable String exportId
    ) {
        com.stown.exportaudit.service.PackageVerification verification =
                exportJobService.verify(exportId);

        return ResponseEntity.ok(new VerifyResponse(
                verification.exportId(),
                verification.verified(),
                verification.packageChecksumMatches(),
                verification.recordedPackageSha256(),
                verification.recomputedPackageSha256(),
                verification.items()
        ));
    }

    private ExportScope parseScope(String scope) {
        if (scope == null || scope.isBlank()) {
            return ExportScope.CASE;
        }

        try {
            return ExportScope.valueOf(scope.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "Invalid scope '" + scope + "'; expected CASE or LEGAL_HOLD"
            );
        }
    }
}
