package com.catchtable.waiting.service;

import com.catchtable.global.exception.BusinessException;
import com.catchtable.global.exception.ErrorCode;
import com.catchtable.store.repository.StoreRepository;
import com.catchtable.waiting.dto.WaitingLane;
import com.catchtable.waiting.dto.WaitingReceiptResponse;
import com.catchtable.waiting.entity.WaitingStatus;
import com.catchtable.waiting.repository.ReceiptWorkRepository;
import com.catchtable.waiting.repository.WaitingRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import com.catchtable.waiting.entity.Waiting;
import java.util.UUID;

@Service
public class ReceiptRegistration {
    public record Claim(WaitingLane lane, List<ReceiptWorkRepository.Work> rows, UUID token) {
        Claim firstOnly() { return new Claim(lane,List.of(rows.getFirst()),token); }
    }
    private final ReceiptWorkRepository work;
    private final StoreRepository stores;
    private final WaitingRepository waitingRows;
    private final WaitingService waitings;
    private final Clock clock;
    private final long leaseMs;
    private final long admissionPriorityMs;

    public ReceiptRegistration(ReceiptWorkRepository work,StoreRepository stores,WaitingRepository waitingRows,
                               WaitingService waitings,Clock clock,@Value("${waiting.dispatch.lease-ms:5000}") long leaseMs,
            @Value("${waiting.dispatch.admission-priority-ms:1800}") long admissionPriorityMs) {
        this.admissionPriorityMs=admissionPriorityMs;
        if(leaseMs<1 || admissionPriorityMs<0 || admissionPriorityMs>3000) throw new IllegalArgumentException("Invalid registration lease");
        this.work=work;this.stores=stores;this.waitingRows=waitingRows;this.waitings=waitings;this.clock=clock;this.leaseMs=leaseMs;
    }

    @Transactional
    public Optional<Claim> claim(WaitingLane lane,UUID only) {
        return claimPrefix(lane,only,1);
    }

    @Transactional
    public Optional<Claim> claimPrefix(WaitingLane lane, UUID only, int limit) {
        if (!work.lockLane(lane)) return Optional.empty();
        if (only==null && limit>1 && work.receivingBurst(lane,admissionPriorityMs)) return Optional.empty();
        var rows = work.heads(lane,limit).stream()
                .takeWhile(r -> r.token()==null || r.expired()).toList();
        if (rows.isEmpty() || (only!=null && !only.equals(rows.getFirst().receipt()))) return Optional.empty();
        UUID token = UUID.randomUUID();
        work.claim(rows,token,leaseMs);
        return Optional.of(new Claim(lane,rows,token));
    }

    @Transactional
    public boolean complete(Claim claim) {
        var lane = claim.lane();
        if (!work.lockLane(lane)) return false;
        // The lane lock serializes number allocation. A shared store lock still fences cutover
        // and owner reordering, while other applications may commit durable admissions.
        var store = stores.findByIdForShare(lane.storeId()).orElseThrow();
        var heads = work.heads(lane,claim.rows().size());
        if (heads.size()!=claim.rows().size()) return false;
        for (int i=0; i<heads.size(); i++) {
            var head = heads.get(i);
            if (!claim.token().equals(head.token()) || !head.receipt().equals(claim.rows().get(i).receipt())) return false;
        }
        var cancellations = work.requestedCancellations(heads);
        var registering = new ArrayList<ReceiptWorkRepository.Work>();
        for (var head : heads) {
            if (cancellations.contains(head.receipt())) {
                if(!work.withdraw(head.member(),head.receipt(),lane,claim.token()))
                    throw new IllegalStateException("Withdrawal lease changed");
            }
            else registering.add(head);
        }
        if (registering.isEmpty()) return true;
        if (!work.permit()) {
            work.release(registering,claim.token());
            return false;
        }
        var members = registering.stream().map(ReceiptWorkRepository.Work::member).toList();
        var existing = new HashMap<Long,Waiting>();
        waitingRows.findByStoreIdAndWaitingDateAndMemberIdInAndStatusIn(lane.storeId(),lane.day(),members,WaitingStatus.ACTIVE)
                .forEach(w -> existing.put(w.getMember().getId(),w));
        int next = waitingRows.findMaxWaitNumber(lane.storeId(),lane.day());
        var ids = new ArrayList<Long>(registering.size());
        for (var row : registering) {
            var active = existing.get(row.member());
            if (active!=null && active.getPartyCount()!=row.party()) throw new BusinessException(ErrorCode.DUPLICATE_ACTIVE_WAITING);
            ids.add(active==null ? waitings.registerAcceptedNumber(row.member(),store,lane.day(),row.party(),++next) : active.getId());
        }
        work.bindRegistrations(registering,ids,claim.token());
        return true;
    }

    @Transactional
    public boolean completeFirstAndReleaseRemainder(Claim claim) {
        if (!complete(claim.firstOnly())) return false;
        if(claim.rows().size()>1) work.release(claim.rows().subList(1,claim.rows().size()),claim.token());
        return true;
    }

    @Transactional(readOnly=true)
    public WaitingReceiptResponse view(long member,UUID id) {
        return work.view(member,id).orElseThrow(()->new BusinessException(ErrorCode.WAITING_NOT_FOUND));
    }

    @Transactional
    public WaitingLane cancelIntent(long member,UUID id) {
        var r=view(member,id);
        var lane=new WaitingLane(r.storeId(),r.receivedDate());
        work.requestCancel(member,id,lane,OffsetDateTime.now(clock));
        if("WITHDRAWN".equals(r.status())) work.resolve(member,id,"WITHDRAWN");
        else if("CANCELED".equals(r.status())) work.resolve(member,id,"CANCELED");
        else work.withdraw(member,id,lane,null);
        return lane;
    }

    @Transactional
    public boolean settleOneCancel(WaitingLane lane) {
        if(!work.lockLane(lane)) return false;
        var abandoned=work.abandonedCancellation(lane);
        if(abandoned.isPresent()) {
            var r=abandoned.get();
            return work.withdraw(r.member(),r.receipt(),lane,r.token());
        }
        var next=work.nextRegisteredCancel(lane);
        if(next.isEmpty()) return false;
        var c=next.get();
        var row=waitingRows.findByIdAndMemberIdForUpdate(c.waitingId(),c.member()).orElseThrow();
        if(!WaitingStatus.ACTIVE.contains(row.getStatus()) && row.getStatus()!=WaitingStatus.CANCELED) {
            work.resolve(c.member(),c.receipt(),"REJECTED");
        } else {
            waitings.cancelWaiting(c.member(),c.waitingId());
            work.resolve(c.member(),c.receipt(),"CANCELED");
        }
        return true;
    }
}
