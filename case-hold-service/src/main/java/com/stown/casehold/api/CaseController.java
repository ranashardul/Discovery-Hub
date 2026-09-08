package com.stown.casehold.api;

import com.stown.casehold.service.CaseService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/cases")
@RequiredArgsConstructor
public class CaseController {

    private final CaseService caseService;

    @PostMapping
    public ResponseEntity<CaseResponse> createCase(@Valid @RequestBody CreateCaseRequest request) {
        return ResponseEntity.ok(caseService.createCase(request));
    }

    @GetMapping
    public ResponseEntity<List<CaseResponse>> listCases(
            @RequestParam(name = "status", required = false) String status
    ) {
        return ResponseEntity.ok(caseService.listCases(status));
    }

    @GetMapping("/{caseId}")
    public ResponseEntity<CaseResponse> getCase(@PathVariable UUID caseId) {
        return ResponseEntity.ok(caseService.getCase(caseId));
    }

    @PatchMapping("/{caseId}")
    public ResponseEntity<CaseResponse> updateCase(
            @PathVariable UUID caseId,
            @Valid @RequestBody UpdateCaseRequest request
    ) {
        return ResponseEntity.ok(caseService.updateCase(caseId, request));
    }

    @PostMapping("/{caseId}/communications")
    public ResponseEntity<CaseCommunicationsResponse> addCommunications(
            @PathVariable UUID caseId,
            @Valid @RequestBody AddCaseCommunicationsRequest request
    ) {
        return ResponseEntity.ok(caseService.addCommunications(caseId, request));
    }

    @GetMapping("/{caseId}/communications")
    public ResponseEntity<CaseCommunicationsResponse> listCommunications(
            @PathVariable UUID caseId
    ) {
        return ResponseEntity.ok(caseService.listCommunications(caseId));
    }
}
