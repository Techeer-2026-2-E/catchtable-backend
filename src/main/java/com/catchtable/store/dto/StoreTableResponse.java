package com.catchtable.store.dto;

import com.catchtable.store.entity.StoreTable;
import com.catchtable.store.entity.TableStatus;

public record StoreTableResponse
        (
                Long id,
                int tableNumber,
                int capacity,
                TableStatus status
        ){
    public static StoreTableResponse from(StoreTable table)
    {
        return new StoreTableResponse(
                table.getId(),
                table.getTableNumber(),
                table.getCapacity(),
                table.getStatus()
        );
    }
}
