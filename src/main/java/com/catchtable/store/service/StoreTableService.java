package com.catchtable.store.service;


import com.catchtable.global.exception.BusinessException;
import com.catchtable.global.exception.ErrorCode;
import com.catchtable.reservation.repository.ReservationRepository;
import com.catchtable.store.dto.StoreTableCreateRequest;
import com.catchtable.store.dto.StoreTableResponse;
import com.catchtable.store.dto.StoreTableUpdateRequest;
import com.catchtable.store.entity.Store;
import com.catchtable.store.entity.StoreTable;
import com.catchtable.store.entity.TableStatus;
import com.catchtable.store.repository.StoreRepository;
import com.catchtable.store.repository.StoreTableRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StoreTableService {

    private final StoreRepository storeRepository;
    private final StoreTableRepository storeTableRepository;
    private final ReservationRepository reservationRepository;

    // TODO: 점주 본인 매장인지 확인 (로그인 기능 만든 후 작업)

    public List<StoreTableResponse> getTables(Long storeId) {
        if (!storeRepository.existsById(storeId)) {
            throw new BusinessException(ErrorCode.STORE_NOT_FOUND);
        }
        return storeTableRepository.findAllByStoreIdOrderByTableNumberAsc(storeId).stream()
                .map(StoreTableResponse::from)
                .toList();
    }

    @Transactional
    public StoreTableResponse createTable(Long storeId, StoreTableCreateRequest request) {
        Store store = storeRepository.findById(storeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STORE_NOT_FOUND));

        if (storeTableRepository.existsByStoreIdAndTableNumber(storeId, request.tableNumber())) {
            throw new BusinessException(ErrorCode.DUPLICATE_TABLE_NUMBER);
        }

        StoreTable storeTable = StoreTable.builder()
                .store(store)
                .tableNumber(request.tableNumber())
                .minCapacity(request.minCapacity())
                .capacity(request.capacity())
                .build();
        return StoreTableResponse.from(storeTableRepository.save(storeTable));
    }

    @Transactional
    public StoreTableResponse updateTable(Long storeId, Long tableId, StoreTableUpdateRequest request) {
        StoreTable storeTable = storeTableRepository.findByIdAndStoreId(tableId, storeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TABLE_NOT_FOUND));
        if (request.tableNumber() != null && request.tableNumber() != storeTable.getTableNumber()) {
            if (storeTableRepository.existsByStoreIdAndTableNumber(storeId, request.tableNumber())) {
                throw new BusinessException(ErrorCode.DUPLICATE_TABLE_NUMBER);
            }
            storeTable.changeTableNumber(request.tableNumber());
        }

        if (request.minCapacity() != null || request.capacity() != null) {
            int minCapacity = request.minCapacity() != null ? request.minCapacity() : storeTable.getMinCapacity();
            int capacity = request.capacity() != null ? request.capacity() : storeTable.getCapacity();
            storeTable.changeCapacityRange(minCapacity, capacity);
        }
        if (request.status() != null) {
            storeTable.changeStatus(request.status());
        }
        // 이용 중인 예약도 포함한다. 새 범위 안에 들어가는 예약은 설정 변경을 막지 않는다.
        if ((request.minCapacity() != null || request.capacity() != null || request.status() != null)
                && reservationRepository.existsConflictingTableReservation(
                        tableId, OffsetDateTime.now(), storeTable.getStatus() == TableStatus.INACTIVE,
                        storeTable.getMinCapacity(), storeTable.getCapacity())) {
            throw new BusinessException(ErrorCode.TABLE_HAS_RESERVATIONS);
        }
        return StoreTableResponse.from(storeTable);
    }
}
