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
import java.util.Optional;
import java.util.UUID;

@Service
public class ReceiptRegistration {
    public record Claim(ReceiptWorkRepository.Work work,UUID token) {}
    private final ReceiptWorkRepository work;
    private final StoreRepository stores;
    private final WaitingRepository waitingRows;
    private final WaitingService waitings;
    private final Clock clock;
    private final long leaseMs;

    public ReceiptRegistration(ReceiptWorkRepository work,StoreRepository stores,WaitingRepository waitingRows,
                               WaitingService waitings,Clock clock,@Value("${waiting.dispatch.lease-ms:5000}") long leaseMs) {
        if(leaseMs<1) throw new IllegalArgumentException("Invalid registration lease");
        this.work=work;this.stores=stores;this.waitingRows=waitingRows;this.waitings=waitings;this.clock=clock;this.leaseMs=leaseMs;
    }

    @Transactional
    public Optional<Claim> claim(WaitingLane lane,UUID only) {
        if(!work.lockLane(lane)) return Optional.empty();
        var found=work.head(lane);
        if(found.isEmpty()) return Optional.empty();
        var head=found.get();
        if(only!=null && !only.equals(head.receipt())) return Optional.empty();
        if(head.token()!=null && !head.expired()) return Optional.empty();
        var token=UUID.randomUUID();
        work.claim(head,token,leaseMs);
        return Optional.of(new Claim(head,token));
    }

    @Transactional
    public boolean complete(Claim claim) {
        var w=claim.work();
        if(!work.lockLane(w.lane())) return false;
        var head=work.head(w.lane());
        if(head.isEmpty() || !claim.token().equals(head.get().token())) return false;
        if(work.wantsCancel(w.member(),w.receipt())) return work.withdraw(w.member(),w.receipt(),w.lane(),claim.token());
        var store=stores.findByIdForUpdate(w.lane().storeId()).orElseThrow();
        if(!work.permit()) {
            work.release(w,claim.token());
            return false;
        }
        var registered=waitings.existingRegistration(w.member(),store.getId(),w.lane().day(),w.party())
                .orElseGet(()->waitings.registerAccepted(w.member(),store,w.party(),w.lane().day()));
        work.bindRegistration(w,claim.token(),registered.response().waitingId());
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
