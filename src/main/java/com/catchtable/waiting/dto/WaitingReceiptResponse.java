package com.catchtable.waiting.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDate;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record WaitingReceiptResponse(UUID receiptId, long storeId, LocalDate receivedDate,
                                     long intakeSequence, int partyCount, String status,
                                     Long waitingId, Integer waitNumber, Long teamsAhead,
                                     String waitingStatus, String cancelOutcome, @com.fasterxml.jackson.annotation.JsonIgnore long queuedAhead) {
    public WaitingReceiptResponse(UUID id,long store,LocalDate day,long sequence,int party,String status) {
        this(id,store,day,sequence,party,status,null,null,null,null,null,0);
    }

    public WaitingReceiptResponse withAhead(long count) {
        return new WaitingReceiptResponse(receiptId,storeId,receivedDate,intakeSequence,partyCount,status,
                waitingId,waitNumber,teamsAhead,waitingStatus,cancelOutcome,count);
    }

    public boolean unfinished() {
        return "PENDING".equals(status) || "PROCESSING".equals(status) || "CANCEL_REQUESTED".equals(status);
    }
}
