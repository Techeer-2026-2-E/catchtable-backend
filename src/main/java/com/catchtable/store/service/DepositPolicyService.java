package com.catchtable.store.service;

import com.catchtable.global.exception.BusinessException;
import com.catchtable.global.exception.ErrorCode;
import com.catchtable.store.dto.DepositPolicyRequest;
import com.catchtable.store.dto.DepositPolicyResponse;
import com.catchtable.store.entity.Store;
import com.catchtable.store.repository.StoreRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class DepositPolicyService {

    private final StoreRepository storeRepository;

    @Transactional(readOnly = true)
    public DepositPolicyResponse getPolicy(Long storeId)
    {
        Store store = storeRepository.findById(storeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STORE_NOT_FOUND));
        store.validateManaged();
        return DepositPolicyResponse.from(store);
    }

    // 예약 생성은 매장을 FOR SHARE로 읽는다. 쓰기 잠금으로 진행 중인 예약과 순서를 맞춘다.
    public DepositPolicyResponse replacePolicy(Long storeId, DepositPolicyRequest request)
    {
        Store store = storeRepository.findByIdForUpdate(storeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STORE_NOT_FOUND));
        store.validateManaged();
        store.changeDepositPolicy(request.depositEnabled(), request.depositAmount(), request.refundPolicy());
        return DepositPolicyResponse.from(store);
    }
}
