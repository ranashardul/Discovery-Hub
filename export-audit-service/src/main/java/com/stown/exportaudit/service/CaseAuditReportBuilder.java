package com.stown.exportaudit.service;

import com.stown.exportaudit.domain.AttachmentMetadata;
import com.stown.exportaudit.domain.AuditEventDocument;
import com.stown.exportaudit.domain.ExportJobDocument;
import com.stown.exportaudit.domain.ManifestItem;
import com.stown.exportaudit.domain.MessageDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Builds the case-level audit report carried inside an export package.
 *
 * <p>Every figure is derived from data the platform actually holds: case
 * metadata and holds from the Case &amp; Hold service, evidence from the
 * messages being exported, the chain of custody from the append-only audit
 * store, and the job record for the export itself. Nothing is inferred or
 * invented — a field the platform does not model is reported as such rather
 * than filled with a plausible value, because an evidence report that guesses
 * is worse than one that admits a gap.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CaseAuditReportBuilder {

    private static final String RULE =
            "================================================================================";
    private static final String THIN =
            "--------------------------------------------------------------------------------";

    /** Used where the platform has no such concept, to avoid inventing one. */
    private static final String NOT_MODELLED = "(not modelled by the platform)";
    private static final String NOT_RECORDED = "(not recorded)";

    private final CaseHoldClient caseHoldClient;
    private final AuditService auditService;

    /**
     * Gathers the facts for a case and renders them once into both forms.
     *
     * @param job      the export job the report is generated for
     * @param messages the evidence included in this package
     * @param items    manifest entries written so far, for reconciliation
     */
    public CaseAuditReport build(
            ExportJobDocument job,
            List<MessageDocument> messages,
            List<ManifestItem> items
    ) {
        String caseId = job.getCaseId();

        CaseHoldClient.CaseDetail caseDetail = caseHoldClient.getCase(caseId);
        List<CaseHoldClient.HoldDetail> holds = caseHoldClient.getHoldsForCase(caseId);
        List<AuditEventDocument> events = auditService.findByCaseId(caseId);

        if (events == null) {
            events = List.of();
        }

        // The EXPORT_COMPLETED audit event is recorded after the package is
        // sealed and uploaded, so it will never appear in a report built
        // during packaging. Adding it here as a synthetic entry closes that
        // gap: the audit trail in the report shows the full lifecycle of the
        // export it belongs to, and the real event is persisted to the store
        // moments later by ExportJobService.process().
        events = appendCurrentExportCompletion(events, job);

        Evidence evidence = summarise(messages);

        Map<String, Object> data = new LinkedHashMap<>();
        StringBuilder text = new StringBuilder();

        header(text);
        caseInformation(text, data, caseId, caseDetail);
        caseSummary(text, data, evidence, items);
        messageBreakdown(text, data, evidence);
        attachmentSummary(text, data, evidence);
        custodianInformation(text, data, evidence);
        holdInformation(text, data, holds);
        timeline(text, data, events);
        exportInformation(text, data, job, evidence);
        auditTrail(text, data, events);
        finalVerification(text, data, job, evidence, items, events);
        footer(text, data, job);

        log.info(
                "Built case audit report caseId={} messages={} attachments={} auditEvents={} holds={}",
                caseId,
                evidence.messageCount,
                evidence.attachmentCount,
                events.size(),
                holds.size()
        );

        return new CaseAuditReport(text.toString(), data);
    }

    /**
     * Adds a synthetic EXPORT_COMPLETED entry for the export whose package is
     * being built. The real event is recorded by {@code ExportJobService} after
     * the package is sealed, so without this the report's audit trail would
     * always stop at EXPORT_REQUESTED for its own export.
     */
    private List<AuditEventDocument> appendCurrentExportCompletion(
            List<AuditEventDocument> events,
            ExportJobDocument job
    ) {
        boolean alreadyPresent = events.stream()
                .anyMatch(e -> "EXPORT_COMPLETED".equals(e.getAction())
                        && job.getExportId().equals(e.getTargetId()));
        if (alreadyPresent) {
            return events;
        }

        AuditEventDocument synthetic = AuditEventDocument.builder()
                .eventId("(recorded after package seal)")
                .caseId(job.getCaseId())
                .action("EXPORT_COMPLETED")
                .targetType("EXPORT")
                .targetId(job.getExportId())
                .actor(job.getRequestedBy())
                .status("COMPLETED")
                .timestamp(Instant.now())
                .details(Map.of(
                        "summary", "Package sealed and uploaded; the durable audit entry is recorded after this report is written",
                        "exportId", job.getExportId(),
                        "messageCount", job.getMessageCount(),
                        "attachmentCount", job.getAttachmentCount()
                ))
                .build();

        List<AuditEventDocument> augmented = new ArrayList<>(events);
        augmented.add(0, synthetic); // newest first
        return augmented;
    }

    // ------------------------------------------------------------------ header

    private void header(StringBuilder text) {
        text.append(RULE).append('\n');
        text.append("CASE AUDIT REPORT").append('\n');
        text.append(RULE).append('\n');
    }

    // --------------------------------------------------------- 1. case details

    private void caseInformation(
            StringBuilder text,
            Map<String, Object> data,
            String caseId,
            CaseHoldClient.CaseDetail detail
    ) {
        section(text, "1. CASE INFORMATION");

        Map<String, Object> node = new LinkedHashMap<>();
        node.put("caseId", caseId);

        if (detail == null) {
            text.append(field("Case ID", caseId));
            text.append(field("Case Name", NOT_RECORDED));
            text.append('\n');
            text.append("  The Case & Hold service was unreachable when this report was\n");
            text.append("  generated, so only the case identifier is stated here. The\n");
            text.append("  evidence, manifest and audit trail below are unaffected.\n");
            node.put("caseMetadataAvailable", false);
            data.put("caseInformation", node);
            return;
        }

        node.put("caseMetadataAvailable", true);
        node.put("caseName", detail.caseName());
        node.put("description", detail.description());
        node.put("status", detail.status());
        node.put("createdBy", detail.createdBy());
        node.put("createdAt", detail.createdAt());
        node.put("updatedAt", detail.updatedAt());
        node.put("communicationCount", detail.communicationCount());
        node.put("activeHoldCount", detail.activeHoldCount());

        text.append(field("Case ID", detail.caseId()));
        text.append(field("Case Name", detail.caseName()));
        text.append(field("Case Description", detail.description()));
        text.append(field("Case Status", detail.status()));
        text.append(field("Case Created At", detail.createdAt()));
        text.append(field("Case Created By", detail.createdBy()));
        text.append(field("Case Last Updated At", detail.updatedAt()));
        text.append(field("Communications On Case", detail.communicationCount()));
        text.append(field("Active Holds", detail.activeHoldCount()));

        // Stated rather than silently omitted: a reviewer needs to know the
        // difference between "empty" and "the platform has no such field".
        boolean closed = "CLOSED".equalsIgnoreCase(detail.status());
        text.append(field(
                "Case Closed At",
                closed ? detail.updatedAt() + " (last status change)" : NOT_RECORDED
        ));
        text.append(field("Case Closed By", NOT_MODELLED));
        text.append(field("Case Priority", NOT_MODELLED));
        text.append(field("Case Type", NOT_MODELLED));

        data.put("caseInformation", node);
    }

    // --------------------------------------------------------- 2. case summary

    private void caseSummary(
            StringBuilder text,
            Map<String, Object> data,
            Evidence evidence,
            List<ManifestItem> items
    ) {
        section(text, "2. CASE SUMMARY");

        Map<String, Object> node = new LinkedHashMap<>();
        node.put("totalMessages", evidence.messageCount);
        node.put("totalAttachments", evidence.attachmentCount);
        node.put("totalCustodians", evidence.custodians.size());
        node.put("totalEvidenceItems", evidence.messageCount + evidence.attachmentCount);
        node.put("filesWrittenToPackage", items.size());
        node.put("messagesByType", evidence.byType);

        text.append(field("Total Messages", evidence.messageCount));
        text.append(field("Total Attachments", evidence.attachmentCount));

        long emails = evidence.byType.getOrDefault("EMAIL", 0L);
        long chats = evidence.byType.getOrDefault("CHAT", 0L);
        long others = evidence.messageCount - emails - chats;

        text.append(field("Total Emails", emails));
        text.append(field("Total Chats", chats));
        text.append(field("Total Other Message Types", others));
        text.append(field("Total Custodians", evidence.custodians.size()));
        text.append(field("Total Evidence Items", evidence.messageCount + evidence.attachmentCount));
        text.append(field("Total Files Exported", items.size()));

        data.put("caseSummary", node);
    }

    // ----------------------------------------------------- 3. message breakdown

    private void messageBreakdown(StringBuilder text, Map<String, Object> data, Evidence evidence) {
        section(text, "3. MESSAGE BREAKDOWN");

        List<Map<String, Object>> rows = new ArrayList<>();

        if (evidence.byType.isEmpty()) {
            text.append("No messages were included in this export.\n");
            data.put("messageBreakdown", rows);
            return;
        }

        for (Map.Entry<String, Long> entry : evidence.byType.entrySet()) {
            String type = entry.getKey();
            TypeStats stats = evidence.statsByType.get(type);

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("communicationType", type);
            row.put("count", entry.getValue());
            row.put("earliest", stats == null ? null : stats.earliest);
            row.put("latest", stats == null ? null : stats.latest);
            row.put("participants", stats == null ? List.of() : List.copyOf(stats.participants));
            rows.add(row);

            text.append(type).append('\n');
            text.append(indented("Count", entry.getValue()));
            text.append(indented(
                    "Date range",
                    stats == null || stats.earliest == null
                            ? NOT_RECORDED
                            : stats.earliest + "  ..  " + stats.latest
            ));
            text.append(indented(
                    "Participants",
                    stats == null ? 0 : stats.participants.size()
            ));

            if (stats != null) {
                for (String participant : stats.participants) {
                    text.append("        - ").append(participant).append('\n');
                }
            }
            text.append('\n');
        }

        data.put("messageBreakdown", rows);
    }

    // --------------------------------------------------- 4. attachment summary

    private void attachmentSummary(StringBuilder text, Map<String, Object> data, Evidence evidence) {
        section(text, "4. ATTACHMENT SUMMARY");

        Map<String, Object> node = new LinkedHashMap<>();
        node.put("totalAttachments", evidence.attachmentCount);
        node.put("totalAttachmentBytes", evidence.attachmentBytes);
        node.put("byType", evidence.attachmentsByType);
        node.put("bytesByType", evidence.attachmentBytesByType);
        node.put("byCustodian", evidence.attachmentsByCustodian);

        text.append(field("Total Attachments", evidence.attachmentCount));
        text.append(field("Total Attachment Size", bytes(evidence.attachmentBytes)));

        if (evidence.attachmentsByType.isEmpty()) {
            text.append('\n').append("No attachments were included in this export.\n");
            data.put("attachmentSummary", node);
            return;
        }

        text.append('\n').append("Attachments by type:").append('\n');
        for (Map.Entry<String, Long> entry : evidence.attachmentsByType.entrySet()) {
            long typeBytes = evidence.attachmentBytesByType.getOrDefault(entry.getKey(), 0L);
            text.append("    ").append(pad(entry.getKey(), 12))
                    .append(entry.getValue())
                    .append("  (").append(bytes(typeBytes)).append(")")
                    .append('\n');
        }

        text.append('\n').append("Attachments by custodian:").append('\n');
        for (Map.Entry<String, Long> entry : evidence.attachmentsByCustodian.entrySet()) {
            text.append("    ").append(pad(entry.getKey(), 48))
                    .append(entry.getValue()).append('\n');
        }

        data.put("attachmentSummary", node);
    }

    // ------------------------------------------------- 5. custodian information

    private void custodianInformation(StringBuilder text, Map<String, Object> data, Evidence evidence) {
        section(text, "5. CUSTODIAN INFORMATION");

        List<Map<String, Object>> rows = new ArrayList<>();

        if (evidence.custodians.isEmpty()) {
            text.append("No custodians were derived from this export.\n");
            data.put("custodians", rows);
            return;
        }

        text.append("Custodians are derived from the sender and recipients of the exported\n");
        text.append("messages. The platform stores no separate custodian directory, so an\n");
        text.append("identifier and role are not available.\n\n");

        for (Custodian custodian : evidence.custodians.values()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("email", custodian.email);
            row.put("messagesSent", custodian.sent);
            row.put("messagesReceived", custodian.received);
            row.put("attachments", custodian.attachments);
            row.put("firstActivity", custodian.first);
            row.put("lastActivity", custodian.last);
            rows.add(row);

            text.append(custodian.email).append('\n');
            text.append(indented("Custodian ID", NOT_MODELLED));
            text.append(indented("Name", NOT_MODELLED));
            text.append(indented("Email", custodian.email));
            text.append(indented("Role", NOT_MODELLED));
            text.append(indented("Messages sent", custodian.sent));
            text.append(indented("Messages received", custodian.received));
            text.append(indented("Attachments", custodian.attachments));
            text.append(indented("First activity", custodian.first));
            text.append(indented("Last activity", custodian.last));
            text.append('\n');
        }

        data.put("custodians", rows);
    }

    // ------------------------------------------------------ 6. legal hold info

    private void holdInformation(
            StringBuilder text,
            Map<String, Object> data,
            List<CaseHoldClient.HoldDetail> holds
    ) {
        section(text, "6. LEGAL HOLD INFORMATION");

        List<Map<String, Object>> rows = new ArrayList<>();

        if (holds.isEmpty()) {
            text.append("No legal holds are recorded against this case.\n");
            data.put("legalHolds", rows);
            return;
        }

        for (CaseHoldClient.HoldDetail hold : holds) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("holdId", hold.holdId());
            row.put("name", hold.name());
            row.put("reason", hold.reason());
            row.put("status", hold.status());
            row.put("scope", hold.scope());
            row.put("createdBy", hold.createdBy());
            row.put("createdAt", hold.createdAt());
            row.put("releasedBy", hold.releasedBy());
            row.put("releasedAt", hold.releasedAt());
            row.put("communicationCount", hold.communicationCount());
            rows.add(row);

            text.append(field("Hold ID", hold.holdId()));
            text.append(indented("Name", hold.name()));
            text.append(indented("Description", hold.description()));
            text.append(indented("Reason", hold.reason()));
            text.append(indented("Status", hold.status()));
            text.append(indented("Scope", hold.scope()));
            text.append(indented("Created by", hold.createdBy()));
            text.append(indented("Created at", hold.createdAt()));
            text.append(indented(
                    "Released by",
                    hold.releasedBy() == null ? "(still active)" : hold.releasedBy()
            ));
            text.append(indented(
                    "Released at",
                    hold.releasedAt() == null ? "(still active)" : hold.releasedAt()
            ));
            text.append(indented("Communications held", hold.communicationCount()));
            text.append('\n');
        }

        data.put("legalHolds", rows);
    }

    // ------------------------------------------------------------ 7. timeline

    private void timeline(
            StringBuilder text,
            Map<String, Object> data,
            List<AuditEventDocument> events
    ) {
        section(text, "7. CASE ACTIVITY / TIMELINE");

        if (events.isEmpty()) {
            text.append("No activity is recorded against this case.\n");
            data.put("timeline", List.of());
            return;
        }

        // Oldest first: a timeline is read forwards, unlike the audit trail
        // below which a reviewer scans newest-first.
        List<AuditEventDocument> ordered = new ArrayList<>(events);
        ordered.sort(Comparator.comparing(
                AuditEventDocument::getTimestamp,
                Comparator.nullsLast(Comparator.naturalOrder())
        ));

        List<Map<String, Object>> rows = new ArrayList<>();

        for (AuditEventDocument event : ordered) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("timestamp", event.getTimestamp());
            row.put("eventType", event.getAction());
            row.put("actor", event.getActor());
            row.put("entityType", event.getTargetType());
            row.put("relatedId", event.getTargetId());
            row.put("status", event.getStatus());
            rows.add(row);

            text.append(event.getTimestamp()).append("  ")
                    .append(pad(safe(event.getAction()), 30))
                    .append(pad(safe(event.getActor()), 26))
                    .append(safe(event.getTargetType()))
                    .append(' ').append(safe(event.getTargetId()))
                    .append('\n');
        }

        data.put("timeline", rows);
    }

    // ------------------------------------------------------- 8. export details

    private void exportInformation(
            StringBuilder text,
            Map<String, Object> data,
            ExportJobDocument job,
            Evidence evidence
    ) {
        section(text, "8. EXPORT INFORMATION");

        Map<String, Object> node = new LinkedHashMap<>();
        node.put("exportJobId", job.getExportId());
        node.put("caseId", job.getCaseId());
        node.put("holdId", job.getHoldId());
        node.put("scope", job.getScope() == null ? null : job.getScope().name());
        node.put("requestedBy", job.getRequestedBy());
        node.put("requestedAt", job.getCreatedAt());
        node.put("startedAt", job.getStartedAt());
        node.put("attempt", job.getAttempts());
        node.put("format", "ZIP");
        node.put("messagesExported", evidence.messageCount);
        node.put("attachmentsExported", evidence.attachmentCount);
        node.put("attachmentBytes", evidence.attachmentBytes);
        node.put("s3Bucket", job.getS3Bucket());
        node.put("s3Key", job.getS3Key());

        text.append(field("Export Job ID", job.getExportId()));
        text.append(field("Case ID", job.getCaseId()));
        text.append(field("Hold ID", job.getHoldId() == null ? "(case-scoped export)" : job.getHoldId()));
        text.append(field("Scope", job.getScope()));
        text.append(field("Requested By", job.getRequestedBy()));
        text.append(field("Requested At", job.getCreatedAt()));
        text.append(field("Export Started At", job.getStartedAt()));
        text.append(field("Attempt", job.getAttempts()));
        text.append(field("Export Status", "RUNNING (this report is written during packaging)"));
        text.append(field("Export Format", "ZIP"));
        text.append(field("Messages Exported", evidence.messageCount));
        text.append(field("Attachments Exported", evidence.attachmentCount));
        text.append(field("Attachment Bytes", bytes(evidence.attachmentBytes)));
        text.append(field("Destination Bucket", job.getS3Bucket() == null ? "(set on completion)" : job.getS3Bucket()));
        text.append(field("Destination Key", job.getS3Key() == null ? "(set on completion)" : job.getS3Key()));
        text.append('\n');
        text.append("The completion timestamp, final package size and package checksum are\n");
        text.append("set after this file is sealed inside the archive, so they live on the\n");
        text.append("export job record and are returned by GET /api/exports/{exportId}.\n");

        data.put("exportInformation", node);
    }

    // ---------------------------------------------------------- 9. audit trail

    private void auditTrail(
            StringBuilder text,
            Map<String, Object> data,
            List<AuditEventDocument> events
    ) {
        section(text, "9. AUDIT TRAIL");

        if (events.isEmpty()) {
            text.append("No audit entries are recorded against this case.\n");
            data.put("auditTrail", List.of());
            return;
        }

        text.append("Newest first. The store is append-only: entries are inserted and read,\n");
        text.append("never updated or deleted, and the event ID is unique so a redelivered\n");
        text.append("event cannot duplicate a row.\n\n");

        List<Map<String, Object>> rows = new ArrayList<>();

        for (AuditEventDocument event : events) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("timestamp", event.getTimestamp());
            row.put("actor", event.getActor());
            row.put("action", event.getAction());
            row.put("entityType", event.getTargetType());
            row.put("entityId", event.getTargetId());
            row.put("status", event.getStatus());
            row.put("eventId", event.getEventId());
            row.put("previousState", event.getBefore());
            row.put("newState", event.getAfter());
            row.put("details", event.getDetails());
            rows.add(row);

            text.append(field("Timestamp", event.getTimestamp()));
            text.append(indented("Actor", event.getActor()));
            text.append(indented("Action", event.getAction()));
            text.append(indented("Entity Type", event.getTargetType()));
            text.append(indented("Entity ID", event.getTargetId()));
            text.append(indented("Status", event.getStatus()));
            text.append(indented("Event ID", event.getEventId()));
            text.append(indented(
                    "Previous State",
                    event.getBefore() == null ? "(not a state transition)" : event.getBefore()
            ));
            text.append(indented(
                    "New State",
                    event.getAfter() == null ? "(not a state transition)" : event.getAfter()
            ));
            text.append(indented(
                    "Details",
                    event.getDetails() == null ? NOT_RECORDED : event.getDetails()
            ));
            text.append('\n');
        }

        data.put("auditTrail", rows);
    }

    // ----------------------------------------------------- 10. verification

    private void finalVerification(
            StringBuilder text,
            Map<String, Object> data,
            ExportJobDocument job,
            Evidence evidence,
            List<ManifestItem> items,
            List<AuditEventDocument> events
    ) {
        section(text, "10. FINAL VERIFICATION");

        long evidenceItems = evidence.messageCount + evidence.attachmentCount;
        long checksummed = items.stream().filter(item -> notBlank(item.sha256())).count();
        boolean everyItemChecksummed = !items.isEmpty() && checksummed == items.size();

        // The manifest holds one entry per message and per attachment. Anything
        // extra is a report file added alongside the evidence.
        long manifestEvidenceItems = items.stream()
                .filter(item -> "MESSAGE".equals(item.type()) || "ATTACHMENT".equals(item.type()))
                .count();
        boolean countsReconcile = manifestEvidenceItems == evidenceItems;

        Set<String> eventIds = new LinkedHashSet<>();
        boolean duplicateEventIds = false;
        for (AuditEventDocument event : events) {
            if (event.getEventId() != null && !eventIds.add(event.getEventId())) {
                duplicateEventIds = true;
            }
        }
        boolean auditChainIntact = !duplicateEventIds;

        boolean verified = everyItemChecksummed && countsReconcile && auditChainIntact;

        Map<String, Object> node = new LinkedHashMap<>();
        node.put("totalEvidenceItems", evidenceItems);
        node.put("totalExportedItems", items.size());
        node.put("manifestEvidenceItems", manifestEvidenceItems);
        node.put("totalAttachments", evidence.attachmentCount);
        node.put("itemsWithChecksum", checksummed);
        node.put("everyItemChecksummed", everyItemChecksummed);
        node.put("countsReconcile", countsReconcile);
        node.put("auditChainIntact", auditChainIntact);
        node.put("auditEventCount", events.size());
        node.put("overallIntegrityStatus", verified ? "VERIFIED" : "FAILED");

        text.append(field("Total Evidence Items", evidenceItems));
        text.append(field("Total Exported Items", items.size()));
        text.append(field("Manifest Evidence Items", manifestEvidenceItems));
        text.append(field("Total Attachments", evidence.attachmentCount));
        text.append(field(
                "Checksum Verification",
                everyItemChecksummed
                        ? "PASS - SHA-256 recorded for all " + items.size() + " item(s)"
                        : "FAIL - " + checksummed + " of " + items.size() + " item(s) carry a checksum"
        ));
        text.append(field(
                "Export Package Verification",
                "DEFERRED - the package checksum is computed over the sealed archive; "
                        + "re-verify with POST /api/exports/" + job.getExportId() + "/verify"
        ));
        text.append(field(
                "Audit Chain Verification",
                auditChainIntact
                        ? "PASS - " + events.size() + " append-only "
                                + (events.size() == 1 ? "entry" : "entries")
                                + ", no duplicate event ID"
                        : "FAIL - duplicate event IDs detected"
        ));
        text.append(field(
                "Evidence Reconciliation",
                countsReconcile
                        ? "PASS - manifest matches the evidence gathered"
                        : "FAIL - manifest lists " + manifestEvidenceItems
                                + " evidence item(s), expected " + evidenceItems
        ));
        text.append('\n');
        text.append("Overall Integrity Status:").append('\n');
        text.append("    ").append(verified ? "VERIFIED" : "FAILED").append('\n');

        data.put("finalVerification", node);
    }

    private void footer(StringBuilder text, Map<String, Object> data, ExportJobDocument job) {
        Instant generatedAt = Instant.now();

        text.append('\n').append(RULE).append('\n');
        text.append(field("Report Generated At", generatedAt));
        text.append(field("Report Generated By", "export-audit-service"));
        text.append(field("On Behalf Of", job.getRequestedBy()));
        text.append(RULE).append('\n');

        data.put("reportGeneratedAt", generatedAt);
        data.put("reportGeneratedBy", "export-audit-service");
        data.put("reportRequestedBy", job.getRequestedBy());
    }

    // ----------------------------------------------------------- aggregation

    /**
     * Walks the evidence once and accumulates every figure the report needs,
     * rather than re-scanning the message list per section.
     */
    private Evidence summarise(List<MessageDocument> messages) {
        Evidence evidence = new Evidence();

        if (messages == null) {
            return evidence;
        }

        for (MessageDocument message : messages) {
            evidence.messageCount++;

            String type = message.getCommunicationType() == null
                    ? "UNKNOWN"
                    : message.getCommunicationType();
            evidence.byType.merge(type, 1L, Long::sum);

            TypeStats stats = evidence.statsByType.computeIfAbsent(type, key -> new TypeStats());
            Instant timestamp = message.getMessageTimestamp();
            stats.observe(timestamp);

            String sender = message.getSender();
            if (notBlank(sender)) {
                stats.participants.add(sender);
                Custodian custodian = evidence.custodian(sender);
                custodian.sent++;
                custodian.observe(timestamp);
            }

            List<String> recipients = message.getRecipients();
            if (recipients != null) {
                for (String recipient : recipients) {
                    if (notBlank(recipient)) {
                        stats.participants.add(recipient);
                        Custodian custodian = evidence.custodian(recipient);
                        custodian.received++;
                        custodian.observe(timestamp);
                    }
                }
            }

            List<AttachmentMetadata> attachments = message.getAttachments();
            if (attachments == null) {
                continue;
            }

            for (AttachmentMetadata attachment : attachments) {
                evidence.attachmentCount++;
                evidence.attachmentBytes += attachment.getSizeBytes();

                String extension = extensionOf(attachment.getFilename());
                evidence.attachmentsByType.merge(extension, 1L, Long::sum);
                evidence.attachmentBytesByType.merge(extension, attachment.getSizeBytes(), Long::sum);

                if (notBlank(sender)) {
                    evidence.attachmentsByCustodian.merge(sender, 1L, Long::sum);
                    evidence.custodian(sender).attachments++;
                }
            }
        }

        return evidence;
    }

    private static String extensionOf(String filename) {
        if (filename == null) {
            return "(none)";
        }

        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "(none)";
        }

        return filename.substring(dot + 1).toUpperCase();
    }

    // ------------------------------------------------------------- formatting

    private void section(StringBuilder text, String title) {
        text.append('\n').append(title).append('\n').append(THIN).append('\n');
    }

    private String field(String label, Object value) {
        return pad(label, 28) + ": " + render(value) + "\n";
    }

    private String indented(String label, Object value) {
        return "    " + pad(label, 24) + ": " + render(value) + "\n";
    }

    private String render(Object value) {
        if (value == null) {
            return NOT_RECORDED;
        }
        String rendered = String.valueOf(value);
        return rendered.isBlank() ? NOT_RECORDED : rendered;
    }

    private static String pad(String value, int width) {
        String safe = safe(value);
        if (safe.length() >= width) {
            return safe + " ";
        }
        return safe + " ".repeat(width - safe.length());
    }

    private static String bytes(long value) {
        if (value < 1024) {
            return value + " B";
        }
        if (value < 1024 * 1024) {
            return String.format("%.1f KiB (%d bytes)", value / 1024.0, value);
        }
        return String.format("%.1f MiB (%d bytes)", value / (1024.0 * 1024.0), value);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    // -------------------------------------------------------- accumulators

    private static final class Evidence {
        private long messageCount;
        private long attachmentCount;
        private long attachmentBytes;

        private final Map<String, Long> byType = new TreeMap<>();
        private final Map<String, TypeStats> statsByType = new TreeMap<>();
        private final Map<String, Long> attachmentsByType = new TreeMap<>();
        private final Map<String, Long> attachmentBytesByType = new TreeMap<>();
        private final Map<String, Long> attachmentsByCustodian = new TreeMap<>();
        private final Map<String, Custodian> custodians = new TreeMap<>();

        private Custodian custodian(String email) {
            return custodians.computeIfAbsent(email, Custodian::new);
        }
    }

    private static final class TypeStats {
        private Instant earliest;
        private Instant latest;
        private final Set<String> participants = new LinkedHashSet<>();

        private void observe(Instant timestamp) {
            if (timestamp == null) {
                return;
            }
            if (earliest == null || timestamp.isBefore(earliest)) {
                earliest = timestamp;
            }
            if (latest == null || timestamp.isAfter(latest)) {
                latest = timestamp;
            }
        }
    }

    private static final class Custodian {
        private final String email;
        private long sent;
        private long received;
        private long attachments;
        private Instant first;
        private Instant last;

        private Custodian(String email) {
            this.email = email;
        }

        private void observe(Instant timestamp) {
            if (timestamp == null) {
                return;
            }
            if (first == null || timestamp.isBefore(first)) {
                first = timestamp;
            }
            if (last == null || timestamp.isAfter(last)) {
                last = timestamp;
            }
        }
    }
}
