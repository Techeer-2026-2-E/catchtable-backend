package com.catchtable.waiting.service;

import com.catchtable.global.exception.BusinessException;
import com.catchtable.global.exception.ErrorCode;
import com.catchtable.store.repository.StoreRepository;
import com.catchtable.waiting.dto.WaitingBatchItem;
import com.catchtable.waiting.dto.WaitingReceiptResponse;
import com.catchtable.waiting.dto.WaitingSubmission;
import com.catchtable.waiting.repository.WaitingBatchRepository;
import com.catchtable.waiting.repository.WaitingReceiptRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class WaitingBatchWriter {
    private final WaitingBatchRepository batches;
    private final WaitingReceiptRepository routes;
    private final StoreRepository stores;
    private final WaitingService waitings;
    private final Clock clock;

    public record Decision(WaitingSubmission.Receipt receipt, ErrorCode error) {
        static final Decision DIRECT = new Decision(null, null);

        static Decision accepted(WaitingReceiptResponse value, boolean created) {
            return new Decision(new WaitingSubmission.Receipt(value, created), null);
        }

        static Decision rejected(ErrorCode error) {
            return new Decision(null, error);
        }

        Optional<WaitingSubmission> resolve() {
            if (error != null) {
                throw new BusinessException(error);
            }
            return Optional.ofNullable(receipt);
        }
    }

    // Empty means that the caller should re-evaluate the ordinary route after releasing these locks.
    @Transactional
    public Optional<List<Decision>> write(long storeId, List<WaitingBatchItem> items) {
        LocalDate hint = LocalDate.now(clock);
        var route = routes.batchRoute(storeId, hint);
        if (route == WaitingReceiptRepository.DayRoute.LEGACY) {
            return Optional.empty();
        }
        var ids = items.stream().map(item -> item.key().memberId()).distinct().sorted().toList();
        var members = batches.lockMembers(ids);
        boolean openingDay = route == WaitingReceiptRepository.DayRoute.CONFIGURED;
        var store = openingDay ? stores.findByIdForUpdate(storeId) : stores.findByIdForShare(storeId);
        OffsetDateTime at = OffsetDateTime.now(clock);
        if (store.isEmpty() || !at.toLocalDate().equals(hint)) {
            return Optional.empty();
        }
        if (!(openingDay ? routes.openForLockedStore(storeId, hint) : routes.enabled(storeId, hint))) {
            return Optional.empty();
        }
        var keys = items.stream().map(WaitingBatchItem::key).distinct().toList();
        var known = batches.byKeys(keys);
        var pending = batches.pending(storeId, hint, ids);
        var registered = batches.registered(storeId, hint, ids);

        boolean needsAdmission = items.stream().anyMatch(item -> !known.containsKey(item.key())
                && !pending.containsKey(item.key().memberId()) && !registered.contains(item.key().memberId()));
        var closed = needsAdmission ? waitings.intakeClosedReason(store.get(), at.toLocalDateTime())
                                    : Optional.<ErrorCode>empty();
        var day = needsAdmission && closed.isEmpty() ? batches.lockDay(storeId, hint)
                                                     : Optional.<WaitingBatchRepository.Day>empty();
        if (needsAdmission && closed.isEmpty() && day.isEmpty()) {
            return Optional.empty();
        }

        var issued = new ArrayList<WaitingBatchRepository.NewReceipt>();
        Map<WaitingBatchItem.Key, UUID> aliases = new LinkedHashMap<>();
        var decisions = new ArrayList<Decision>();
        for (var item : items) {
            var key = item.key();
            var original = known.get(key);
            var active = pending.get(key.memberId());
            if (original != null) {
                decisions.add(original.storeId() == storeId && original.partyCount() == item.partyCount()
                        ? Decision.accepted(original, false) : Decision.rejected(ErrorCode.WAITING_RECEIPT_KEY_CONFLICT));
            } else if (active != null) {
                if (active.partyCount() != item.partyCount()) {
                    decisions.add(Decision.rejected(ErrorCode.DUPLICATE_ACTIVE_WAITING));
                } else {
                    known.put(key, active);
                    aliases.put(key, active.receiptId());
                    decisions.add(Decision.accepted(active, false));
                }
            } else if (registered.contains(key.memberId())) {
                decisions.add(Decision.DIRECT); // Existing restaurant registration uses its original 200/409 contract.
            } else if (closed.isPresent()) {
                decisions.add(Decision.rejected(closed.get()));
            } else if (!members.contains(key.memberId())) {
                decisions.add(Decision.rejected(ErrorCode.MEMBER_NOT_FOUND));
            } else {
                var allocation = day.orElseThrow();
                if (issued.size() == allocation.room()) {
                    decisions.add(Decision.rejected(ErrorCode.WAITING_INTAKE_FULL));
                    continue;
                }
                long sequence = allocation.lastSequence() + issued.size() + 1;
                var receipt = new WaitingReceiptResponse(UUID.randomUUID(), storeId, hint, sequence,
                        item.partyCount(), "PENDING").withAhead(allocation.pendingCount()+issued.size());
                issued.add(new WaitingBatchRepository.NewReceipt(key, receipt));
                known.put(key, receipt);
                pending.put(key.memberId(), receipt);
                decisions.add(new Decision(new WaitingSubmission.Receipt(receipt, true, allocation.pendingCount()==0 && issued.size()==1), null));
            }
        }
        if (!issued.isEmpty()) {
            batches.issue(storeId, hint, day.orElseThrow().lastSequence(), at, issued);
        }
        if (!aliases.isEmpty()) {
            batches.bindAliases(aliases);
        }
        return Optional.of(decisions);
    }
}
