package com.catchtable.store.dto;

import com.catchtable.store.entity.Menu;

public record MenuResponse(
        Long id,
        String name,
        int price,
        String description
) {
    public static MenuResponse from(Menu menu)
    {
        return new MenuResponse(
                menu.getId(),
                menu.getName(),
                menu.getPrice(),
                menu.getDescription()
        );
    }
}
