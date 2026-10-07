package com.catchtable.review.dto;

import org.springframework.data.domain.Sort;

public enum ReviewSort {
    Latest(Sort.by(Sort.Order.desc("createdAt"),Sort.Order.desc("id"))),
    RATING(Sort.by(Sort.Order.desc("rating"), Sort.Order.desc("createdAt"),
            Sort.Order.desc("id")));


    private final Sort sort;

    ReviewSort(Sort sort)
    {
        this.sort=sort;
    }
    public Sort toSort()
    {
        return sort;
    }
}
