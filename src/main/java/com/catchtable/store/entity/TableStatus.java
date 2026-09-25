package com.catchtable.store.entity;


import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum TableStatus {

    ACTIVE("운영"),
    INACTIVE("운영 안 함");

    private final String description;
}
