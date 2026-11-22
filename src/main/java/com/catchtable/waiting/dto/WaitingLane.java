package com.catchtable.waiting.dto;

import java.time.LocalDate;

public record WaitingLane(long storeId, LocalDate day) {
    public String key() { return storeId + "/" + day; }
    public static WaitingLane parse(String key) {
        int slash=key.indexOf('/');
        return new WaitingLane(Long.parseLong(key.substring(0,slash)),LocalDate.parse(key.substring(slash+1)));
    }
}
