package com.catchtable.waiting.controller;

import com.catchtable.global.auth.LoginMember;
import com.catchtable.waiting.dto.WaitingCreateRequest;
import com.catchtable.waiting.dto.WaitingCreateResult;
import com.catchtable.waiting.dto.WaitingReceiptResponse;
import com.catchtable.waiting.dto.WaitingSubmission;
import com.catchtable.waiting.service.WaitingIntakeService;
import com.catchtable.waiting.service.ReceiptRegistration;
import com.catchtable.waiting.service.WaitingDispatch;
import org.springframework.beans.factory.annotation.Value;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/user/waitings")
public class WaitingController {
    private final WaitingIntakeService intake;
    private final ReceiptRegistration registration;
    private final WaitingDispatch dispatch;
    @Value("${waiting.dispatch.poll-hint-seconds:2}") private int pollHint;
    @Value("${waiting.dispatch.enabled:false}") private boolean deliveryEnabled;
    @Value("${waiting.dispatch.max-poll-seconds:10}") private int maxPoll;
    @Value("${waiting.dispatch.estimated-per-second:100}") private int estimatedRate;


    @PostMapping
    public ResponseEntity<?> createWaiting(@LoginMember Long memberId, @Valid @RequestBody WaitingCreateRequest request,
                                         @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return submit(memberId,request,key);
    }

    private ResponseEntity<?> submit(long memberId,WaitingCreateRequest request,String key) {
        return switch (intake.submit(memberId, request, key)) {
            case WaitingCreateResult result -> ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                    .body(result.response());
            case WaitingSubmission.Receipt receipt -> {
                var result=dispatch.afterCommit(memberId,receipt);
                var status=receipt.created() ? (result.waitingId()==null ? HttpStatus.ACCEPTED : HttpStatus.CREATED) : HttpStatus.OK;
                yield response(result,status);
            }
        };
    }

    private ResponseEntity<WaitingReceiptResponse> response(WaitingReceiptResponse result,HttpStatus status) {
        var builder=ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .location(URI.create("/api/user/waitings/receipts/"+result.receiptId()));
        if(result.unfinished()) {
            long extra=deliveryEnabled && "PENDING".equals(result.status()) ? result.queuedAhead()/Math.max(1,estimatedRate) : 0;
            long seconds=Math.min(Math.max(pollHint,maxPoll),Math.max(1,pollHint)+extra);
            builder.header(HttpHeaders.RETRY_AFTER,Long.toString(Math.max(1,seconds)));
        }
        return builder.body(result);
    }

    @ExceptionHandler({org.springframework.transaction.CannotCreateTransactionException.class,
            org.springframework.transaction.TransactionSystemException.class, org.springframework.dao.TransientDataAccessException.class,
            org.springframework.dao.DataAccessResourceFailureException.class})
    public ResponseEntity<com.catchtable.global.exception.ErrorResponse> storageUnavailable(RuntimeException error) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header(HttpHeaders.RETRY_AFTER,"2")
                .body(new com.catchtable.global.exception.ErrorResponse("WAITING_RECEIPT_UNCONFIRMED",
                        "현재 접수 상태를 확인할 수 없습니다. 같은 요청 키로 다시 확인해 주세요."));
    }

    @GetMapping("/receipts/{receiptId}")
    public ResponseEntity<WaitingReceiptResponse> receipt(@LoginMember Long memberId, @PathVariable UUID receiptId) {
        return response(registration.view(memberId,receiptId),HttpStatus.OK);
    }

    @DeleteMapping("/receipts/{receiptId}")
    public ResponseEntity<WaitingReceiptResponse> withdraw(@LoginMember Long memberId, @PathVariable UUID receiptId) {
        var lane=registration.cancelIntent(memberId,receiptId);
        registration.settleOneCancel(lane);
        var view=registration.view(memberId,receiptId);
        var status="REJECTED".equals(view.cancelOutcome()) ? HttpStatus.CONFLICT :
                ("CANCEL_REQUESTED".equals(view.status()) ? HttpStatus.ACCEPTED : HttpStatus.OK);
        return response(view,status);
    }
}
