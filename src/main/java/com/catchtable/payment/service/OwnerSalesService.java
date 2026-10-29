package com.catchtable.payment.service;

import com.catchtable.global.exception.BusinessException;
import com.catchtable.global.exception.ErrorCode;
import com.catchtable.payment.dto.SalesResponse;
import com.catchtable.payment.entity.PaymentStatus;
import com.catchtable.payment.repository.PaymentSalesRepository;
import com.catchtable.payment.repository.PaymentSalesRepository.SalesTotals;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OwnerSalesService {

    private static final ZoneId STORE_ZONE = ZoneId.of("Asia/Seoul");

    // 승인 이후 상태만 매출에 넣는다. READY·FAILED·CANCELED·TIMEOUT은 제외된다
    private static final List<PaymentStatus> APPROVED_STATUSES = Arrays.stream(PaymentStatus.values())
            .filter(PaymentStatus::isApproved)
            .toList();

    private final PaymentSalesRepository paymentSalesRepository;

    /** from·to는 서울 기준 날짜이고 to 날짜까지 포함한다. */
    public SalesResponse getSales(Long ownerId, Long storeId, LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        // 없는 매장과 남의 매장·점주 없는 공공 매장을 구분하지 않는다
        paymentSalesRepository.findStoreIdByIdAndOwnerId(storeId, ownerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STORE_NOT_FOUND));

        OffsetDateTime start = from.atStartOfDay(STORE_ZONE).toOffsetDateTime();
        OffsetDateTime end = to.plusDays(1).atStartOfDay(STORE_ZONE).toOffsetDateTime();
        SalesTotals totals = paymentSalesRepository.sumApproved(storeId, APPROVED_STATUSES, start, end);

        return SalesResponse.of(from, to, totals.getApprovedAmount(), totals.getRefundedAmount());
    }
}
