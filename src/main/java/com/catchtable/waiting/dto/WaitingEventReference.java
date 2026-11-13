package com.catchtable.waiting.dto;

// Internal correlation only; not part of the SSE payload or HTTP response.
public record WaitingEventReference(Long waitingId, Integer callCount, Integer recallCount, String eventKey) {
}
