package com.catchtable.payment.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

// 결제 내역 페이징 (리뷰 목록과 같은 page·size)
public record PaymentListCondition(
        @Min(0) Integer page,
        @Min(1) @Max(50) Integer size
) {
    public PaymentListCondition {
        if (page == null) page = 0;
        if (size == null) size = 10;
    }

    // 최신순
    public Pageable toPageable() {
        return PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id"));
    }
}
