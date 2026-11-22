package com.catchtable.waiting.dto;

/** Member-scoped retry key and validated body of one request in a store batch. */
public record WaitingBatchItem(Key key, int partyCount) {
    public record Key(long memberId, String token) {}
}
