package com.catchtable.waiting.dto;

/** An actual restaurant registration or a durable receipt awaiting registration. */
public sealed interface WaitingSubmission permits WaitingCreateResult, WaitingSubmission.Receipt {
    record Receipt(WaitingReceiptResponse response, boolean created, boolean idleHead) implements WaitingSubmission {
        public Receipt(WaitingReceiptResponse response, boolean created) { this(response,created,false); }
    }
}
