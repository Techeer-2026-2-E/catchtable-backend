package com.catchtable.waiting.service;

import com.catchtable.global.exception.BusinessException;
import com.catchtable.global.exception.ErrorCode;
import com.catchtable.store.entity.Store;
import com.catchtable.store.repository.StoreRepository;
import com.catchtable.waiting.dto.WaitingCreateRequest;
import com.catchtable.waiting.dto.WaitingBatchItem;
import com.catchtable.waiting.dto.WaitingReceiptResponse;
import com.catchtable.waiting.dto.WaitingSubmission;
import com.catchtable.waiting.repository.WaitingReceiptRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class WaitingIntakeService {
    private final WaitingReceiptRepository receipts;
    private final StoreRepository stores;
    private final WaitingService waitings;
    private final Clock clock;

    private final TransactionTemplate transactions;
    private final WaitingIntakeBatcher batcher;

    public WaitingSubmission submit(long memberId, WaitingCreateRequest request, String key) {
        if (batcher.enabled() && validKey(key) && !TransactionSynchronizationManager.isActualTransactionActive()) {
            var item = new WaitingBatchItem(new WaitingBatchItem.Key(memberId, key), request.partyCount());
            var grouped = batcher.submit(request.storeId(), item);
            if (grouped.isPresent()) return grouped.get();
        }
        return submitDirect(memberId,request,key);
    }

    private WaitingSubmission submitDirect(long memberId, WaitingCreateRequest request,String key) {
        WaitingSubmission result = transactions.execute(tx -> submitLocked(memberId, request, key, false));
        if (result != null) {
            return result;
        }
        // Midnight passed while acquiring a shared store lock. Release it before taking the
        // legacy write lock: two concurrent applicants must never upgrade shared locks together.
        return transactions.execute(tx -> submitLocked(memberId, request, key, true));
    }

    private WaitingSubmission submitLocked(long memberId, WaitingCreateRequest request, String key,
                                           boolean forceWriteLock) {
        boolean keyed = validKey(key);
        boolean memberFound = keyed && receipts.lockMember(memberId);
        if (memberFound) {
            var replay = receipts.byKey(memberId, key);
            if (replay.isPresent()) {
                var receipt = replay.get();
                if (receipt.storeId() != request.storeId() || receipt.partyCount() != request.partyCount()) {
                    throw new BusinessException(ErrorCode.WAITING_RECEIPT_KEY_CONFLICT);
                }
                return new WaitingSubmission.Receipt(receipt, false);
            }
        }

        long storeId = request.storeId();
        LocalDate hintDay = LocalDate.now(clock);
        boolean shared = !forceWriteLock && keyed && receipts.enabled(storeId, hintDay);
        Optional<Store> locked = shared ? stores.findByIdForShare(storeId) : stores.findByIdForUpdate(storeId);
        Store store = locked.orElseThrow(() -> new BusinessException(ErrorCode.STORE_NOT_FOUND));
        OffsetDateTime at = OffsetDateTime.now(clock);
        if (shared && (!hintDay.equals(at.toLocalDate()) || !receipts.enabled(storeId, at.toLocalDate()))) {
            return null; // No write in this transaction. Check the new day's route under an exclusive lock.
        }
        if (!shared && !receipts.openForLockedStore(storeId, at.toLocalDate())) {
            return waitings.registerLocked(memberId, store, request.partyCount(), at.toLocalDateTime());
        }
        if (!keyed) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        var existing = receipts.pending(memberId, storeId, at.toLocalDate());
        if (existing.isPresent()) {
            var receipt = existing.get();
            if (receipt.partyCount() != request.partyCount()) {
                throw new BusinessException(ErrorCode.DUPLICATE_ACTIVE_WAITING);
            }
            receipts.bind(memberId, key, receipt.receiptId());
            return new WaitingSubmission.Receipt(receipt, false);
        }
        var registered = waitings.existingRegistration(memberId, storeId, at.toLocalDate(), request.partyCount());
        if (registered.isPresent()) {
            return registered.get();
        }
        waitings.validateIntakeOpen(store, at.toLocalDateTime());
        if (!memberFound) {
            throw new BusinessException(ErrorCode.MEMBER_NOT_FOUND);
        }
        var receipt = receipts.issue(memberId, storeId, at.toLocalDate(), request.partyCount(), at, key)
                .orElseThrow(() -> new BusinessException(ErrorCode.WAITING_INTAKE_FULL));
        return new WaitingSubmission.Receipt(receipt.receipt(), true, receipt.idleHead());
    }

    @Transactional(readOnly = true)
    public WaitingReceiptResponse find(long memberId, UUID id) {
        return receipts.byOwner(memberId, id).orElseThrow(() -> new BusinessException(ErrorCode.WAITING_NOT_FOUND));
    }

    private boolean validKey(String key) {
        return key != null && !key.isEmpty() && key.length() <= 128
                && key.chars().allMatch(c -> c >= 33 && c <= 126);
    }
}
