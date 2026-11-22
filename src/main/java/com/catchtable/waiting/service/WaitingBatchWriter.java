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

    // One autocommitted statement on a scheduler thread. If it cannot prove every
    // precondition it makes no writes and releases its locks before the general path.
    public Optional<List<Decision>> regular(long store,List<WaitingBatchItem> items) {
        if (items.size()<2) return Optional.empty();
        var at=OffsetDateTime.now(clock);
        var ids=items.stream().map(i->UUID.randomUUID()).toList();
        return batches.issueRegular(store,at,items,ids).map(allocation -> accepted(store,at.toLocalDate(),items,ids,allocation));
    }

    private List<Decision> accepted(long store,LocalDate day,List<WaitingBatchItem> items,
                                    List<UUID> ids,WaitingBatchRepository.Allocation allocation) {
        var result=new ArrayList<Decision>(items.size());
        for (int i=0;i<items.size();i++) {
            var receipt=new WaitingReceiptResponse(ids.get(i),store,day,allocation.previousSequence()+i+1,
                    items.get(i).partyCount(),"PENDING").withAhead(allocation.previousPending()+(long)i);
            result.add(Decision.accepted(receipt,true));
        }
        return result;
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
        if (openingDay ? routes.batchRoute(storeId,hint)==WaitingReceiptRepository.DayRoute.LEGACY : !routes.enabled(storeId,hint)) {
            return Optional.empty();
        }
        boolean freshBatchCandidate = !openingDay && items.size()>1 && members.size()==items.size();
        Optional<ErrorCode> closed = freshBatchCandidate ? waitings.intakeClosedReason(store.get(),at.toLocalDateTime()) : Optional.empty();
        if (freshBatchCandidate && closed.isEmpty()) {
            var receiptIds=items.stream().map(i->UUID.randomUUID()).toList();
            var allocation=batches.issueFresh(storeId,hint,at,items,receiptIds);
            if(allocation.isPresent()) return Optional.of(accepted(storeId,hint,items,receiptIds,allocation.get()));
        }
        var keys = items.stream().map(WaitingBatchItem::key).distinct().toList();
        var known = batches.byKeys(keys);
        var pending = batches.pending(storeId, hint, ids);
        var registered = batches.registered(storeId, hint, ids);

        boolean needsAdmission = items.stream().anyMatch(item -> !known.containsKey(item.key())
                && !pending.containsKey(item.key().memberId()) && !registered.contains(item.key().memberId()));
        if (!freshBatchCandidate && needsAdmission) closed=waitings.intakeClosedReason(store.get(),at.toLocalDateTime());
        boolean needsRoom = needsAdmission && closed.isEmpty() && items.stream().anyMatch(item ->
                members.contains(item.key().memberId()) && !known.containsKey(item.key())
                && !pending.containsKey(item.key().memberId()) && !registered.contains(item.key().memberId()));
        if (needsRoom && openingDay && !routes.openForLockedStore(storeId,hint)) return Optional.empty();
        var day = needsRoom ? batches.lockDay(storeId,hint) : Optional.<WaitingBatchRepository.Day>empty();
        if (needsRoom && day.isEmpty()) return Optional.empty();

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
                decisions.add(new Decision(new WaitingSubmission.Receipt(receipt, true, items.size()==1 && allocation.pendingCount()==0 && issued.size()==1), null));
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
