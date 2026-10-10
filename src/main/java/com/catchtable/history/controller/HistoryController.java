package com.catchtable.history.controller;

import com.catchtable.global.auth.LoginMember;
import com.catchtable.history.dto.HistoryItemResponse;
import com.catchtable.history.dto.HistoryStatus;
import com.catchtable.history.dto.HistoryType;
import com.catchtable.history.service.HistoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// 예약·웨이팅 통합 내역 (API 명세상 경로는 reservations 아래)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/user/reservations")
public class HistoryController {

    private final HistoryService historyService;

    /** 유형(RESERVATION/WAITING)·상태(ACTIVE/ENDED)는 선택, 생략하면 전체를 조회한다. */
    @GetMapping
    public List<HistoryItemResponse> getHistories(
            @LoginMember Long memberId,
            @RequestParam(required = false) HistoryType type,
            @RequestParam(required = false) HistoryStatus status
    ) {
        return historyService.getHistories(memberId, type, status);
    }
}
