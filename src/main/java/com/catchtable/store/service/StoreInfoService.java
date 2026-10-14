package com.catchtable.store.service;


import com.catchtable.global.exception.BusinessException;
import com.catchtable.global.exception.ErrorCode;
import com.catchtable.store.dto.StoreInfoResponse;
import com.catchtable.store.dto.StoreInfoUpdateRequest;
import com.catchtable.store.entity.Store;
import com.catchtable.store.repository.StoreRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class StoreInfoService {

    private final StoreRepository storeRepository;

    public StoreInfoResponse updateInfo(Long storeId, StoreInfoUpdateRequest request)
    {
        Store store=storeRepository.findById(storeId)
                .orElseThrow(()->new BusinessException(ErrorCode.STORE_NOT_FOUND));
        store.changeInfo(request.name(),
                request.category(),
                request.address(),
                request.description(),
                request.phone(),
                request.imageUrl());

        return StoreInfoResponse.from(store);
    }
}
