package com.catchtable.payment.dto;

import org.springframework.data.domain.Slice;

import java.util.List;

public record PaymentListResponse(
        List<PaymentResponse> payments,
        int page,
        int size,
        boolean hasNext
) {
    public static PaymentListResponse of(Slice<PaymentResponse> slice) {
        return new PaymentListResponse(slice.getContent(), slice.getNumber(), slice.getSize(), slice.hasNext());
    }
}
