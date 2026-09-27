package com.catchtable.store.service;


import com.catchtable.global.exception.BusinessException;
import com.catchtable.global.exception.ErrorCode;
import com.catchtable.store.dto.ReservationPolicyRequest;
import com.catchtable.store.dto.ReservationPolicyResponse;
import com.catchtable.store.entity.Store;
import com.catchtable.store.repository.StoreRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class ReservationPolicyService {

    private final StoreRepository storeRepository;

    public ReservationPolicyResponse getPolicy(Long storeId)

    {
        return ReservationPolicyResponse.from(findStore(storeId));
    }

    @Transactional
    public ReservationPolicyResponse replacePolicy(Long storeId, ReservationPolicyRequest request)
    {
        Store store=findStore(storeId);
        store.changeReservationPolicy(
                request.reservationDurationMinutes(),
                request.reservationSlotMinutes(),
                request.arrivalGraceMinutes(),
                request.bookingOpenDays(),
                request.bookingDeadlineMinutes()
        );
        return ReservationPolicyResponse.from(store);
    }

    private Store findStore(Long storeId)
    {
        return storeRepository.findById(storeId)
                .orElseThrow(()-> new BusinessException(ErrorCode.STORE_NOT_FOUND));
    }
}
