package com.stown.ingestion.messaging;

import com.stown.ingestion.api.IngestionRequest;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IngestionRequestedEvent {

    private UUID eventId;
    private IngestionRequest message;
}