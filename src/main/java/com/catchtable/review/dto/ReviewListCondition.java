package com.catchtable.review.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

public record ReviewListCondition
        (
                ReviewSort sort,
                @Min(0) Integer page,
                @Min(1) @Max(50) Integer size
        )
{
    public ReviewListCondition{
        if(sort==null) sort=ReviewSort.Latest;
        if(page==null) page=0;
        if(size==null) size=10;
    }

    public Pageable toPageable()
    {
        return PageRequest.of(page, size, sort.toSort());
    }
}
