package com.stown.casehold.api;

import com.stown.casehold.service.HoldService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class HoldController {

    private final HoldService holdService;

    @PostMapping("/cases/{caseId}/holds")
    public ResponseEntity<HoldResponse> createHold(
            @PathVariable UUID caseId,
            @Valid @RequestBody CreateHoldRequest request
    ) {
        return ResponseEntity.ok(holdService.createHold(caseId, request));
    }

    @GetMapping("/cases/{caseId}/holds")
    public ResponseEntity<List<HoldResponse>> listHoldsForCase(@PathVariable UUID caseId) {
        return ResponseEntity.ok(holdService.listHoldsForCase(caseId));
    }

    @GetMapping("/holds/{holdId}")
    public ResponseEntity<HoldResponse> getHold(@PathVariable UUID holdId) {
        return ResponseEntity.ok(holdService.getHold(holdId));
    }

    @GetMapping("/holds/{holdId}/communications")
    public ResponseEntity<HoldCommunicationsResponse> listHoldCommunications(
            @PathVariable UUID holdId
    ) {
        return ResponseEntity.ok(holdService.listHoldCommunications(holdId));
    }

    @PatchMapping("/holds/{holdId}/release")
    public ResponseEntity<HoldResponse> releaseHold(
            @PathVariable UUID holdId,
            @Valid @RequestBody ReleaseHoldRequest request
    ) {
        return ResponseEntity.ok(holdService.releaseHold(holdId, request));
    }
}
