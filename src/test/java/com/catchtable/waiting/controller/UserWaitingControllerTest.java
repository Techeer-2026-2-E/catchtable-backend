package com.catchtable.waiting.controller;

import com.catchtable.member.entity.Member;
import com.catchtable.member.entity.UserType;
import com.catchtable.member.repository.MemberRepository;
import com.catchtable.store.entity.Store;
import com.catchtable.store.entity.StoreCategory;
import com.catchtable.store.repository.StoreRepository;
import com.catchtable.waiting.entity.Waiting;
import com.catchtable.waiting.entity.WaitingStatus;
import com.catchtable.waiting.repository.WaitingRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 현재 대기순번 조회 테스트 (앞 팀 수는 쿼리 결과가 핵심이라 실제 DB로 확인)
@SpringBootTest
@AutoConfigureMockMvc
class UserWaitingControllerTest {

    private static final LocalDate TODAY = LocalDate.of(2030, 1, 10);

    @Autowired MockMvc mockMvc;
    @Autowired WaitingRepository waitingRepository;
    @Autowired StoreRepository storeRepository;
    @Autowired MemberRepository memberRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    private final List<Member> createdMembers = new ArrayList<>(); // 정리 대상 회원
    private final List<Store> createdStores = new ArrayList<>();   // 정리 대상 매장
    private Store store;                                           // 테스트 매장
    private Member me;                                             // 조회하는 고객

    @BeforeEach
    void setUp() {
        store = saveStore("테스트 매장");
        me = saveMember("나");
    }

    // 데이터 정리 (웨이팅 → 매장 → 회원 순, FK 때문)
    @AfterEach
    void tearDown() {
        waitingRepository.deleteAllInBatch();
        storeRepository.deleteAll(createdStores);
        memberRepository.deleteAll(createdMembers);
        createdMembers.clear();
        createdStores.clear();
    }

    @Test
    @DisplayName("앞에 진행 중인 팀만 세어 현재 순번을 계산한다")
    void countsOnlyActiveTeamsAhead() throws Exception {
        saveWaiting(saveMember("앞1"), store, TODAY, 1, WaitingStatus.WAITING);
        saveWaiting(saveMember("앞2"), store, TODAY, 2, WaitingStatus.CANCELED); // 취소 → 제외
        saveWaiting(saveMember("앞3"), store, TODAY, 3, WaitingStatus.CALLED);
        Waiting mine = saveWaiting(me, store, TODAY, 4, WaitingStatus.WAITING);
        saveWaiting(saveMember("뒤"), store, TODAY, 5, WaitingStatus.WAITING);   // 뒤 → 제외

        getWaiting(me, mine.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.waitingId").value(mine.getId()))
                .andExpect(jsonPath("$.storeName").value("테스트 매장"))
                .andExpect(jsonPath("$.waitNumber").value(4))
                .andExpect(jsonPath("$.teamsAhead").value(2))
                .andExpect(jsonPath("$.currentPosition").value(3))
                .andExpect(jsonPath("$.status").value("WAITING"));
    }

    @Test
    @DisplayName("앞 팀이 취소되면 다시 조회했을 때 순번이 줄어든다 (번호표는 그대로)")
    void positionDecreasesWhenTeamAheadCancels() throws Exception {
        Waiting ahead = saveWaiting(saveMember("앞"), store, TODAY, 1, WaitingStatus.WAITING);
        Waiting mine = saveWaiting(me, store, TODAY, 2, WaitingStatus.WAITING);

        getWaiting(me, mine.getId())
                .andExpect(jsonPath("$.currentPosition").value(2));

        changeStatus(ahead, WaitingStatus.CANCELED);

        getWaiting(me, mine.getId())
                .andExpect(jsonPath("$.waitNumber").value(2))
                .andExpect(jsonPath("$.teamsAhead").value(0))
                .andExpect(jsonPath("$.currentPosition").value(1));
    }

    @Test
    @DisplayName("다른 날짜·다른 매장의 웨이팅은 앞 팀으로 세지 않는다")
    void ignoresOtherDateAndOtherStore() throws Exception {
        Store otherStore = saveStore("다른 매장");
        saveWaiting(saveMember("어제"), store, TODAY.minusDays(1), 1, WaitingStatus.WAITING);
        saveWaiting(saveMember("다른 매장"), otherStore, TODAY, 1, WaitingStatus.WAITING);
        Waiting mine = saveWaiting(me, store, TODAY, 1, WaitingStatus.WAITING);

        getWaiting(me, mine.getId())
                .andExpect(jsonPath("$.teamsAhead").value(0))
                .andExpect(jsonPath("$.currentPosition").value(1));
    }

    @Test
    @DisplayName("종료된 웨이팅은 순번·앞 팀 수를 null로 돌려준다")
    void endedWaitingHasNoPosition() throws Exception {
        saveWaiting(saveMember("앞"), store, TODAY, 1, WaitingStatus.WAITING);
        Waiting mine = saveWaiting(me, store, TODAY, 2, WaitingStatus.SEATED);

        getWaiting(me, mine.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SEATED"))
                .andExpect(jsonPath("$.waitNumber").value(2))
                .andExpect(jsonPath("$.teamsAhead").doesNotExist())
                .andExpect(jsonPath("$.currentPosition").doesNotExist());
    }

    @Test
    @DisplayName("다른 회원의 웨이팅은 404")
    void othersWaitingIsNotFound() throws Exception {
        Waiting others = saveWaiting(saveMember("남"), store, TODAY, 1, WaitingStatus.WAITING);

        getWaiting(me, others.getId())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WAITING_NOT_FOUND"));
    }

    @Test
    @DisplayName("없는 웨이팅은 404")
    void unknownWaitingIsNotFound() throws Exception {
        getWaiting(me, -1L)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WAITING_NOT_FOUND"));
    }

    @Test
    @DisplayName("회원 식별 헤더가 없으면 401")
    void missingMemberHeaderIsUnauthorized() throws Exception {
        Waiting mine = saveWaiting(me, store, TODAY, 1, WaitingStatus.WAITING);

        mockMvc.perform(get("/api/user/waitings/{waitingId}", mine.getId()))
                .andExpect(status().isUnauthorized());
    }

    private ResultActions getWaiting(Member member, Long waitingId) throws Exception {
        return mockMvc.perform(get("/api/user/waitings/{waitingId}", waitingId)
                .header("X-Member-Id", member.getId()));
    }

    // 상태 변경 메서드가 아직 없어서(취소·착석 등 미구현) DB 값을 직접 바꾼다
    private Waiting saveWaiting(Member member, Store target, LocalDate date, int number, WaitingStatus status) {
        Waiting waiting = waitingRepository.save(Waiting.create(member, target, date, 2, number));
        if (status != WaitingStatus.WAITING) {
            changeStatus(waiting, status);
        }
        return waiting;
    }

    private void changeStatus(Waiting waiting, WaitingStatus status) {
        jdbcTemplate.update("update waiting set status = ? where id = ?", status.name(), waiting.getId());
    }

    private Store saveStore(String name) {
        Store saved = storeRepository.save(Store.builder()
                .owner(saveMember(name + " 점주"))
                .name(name)
                .category(StoreCategory.values()[0])
                .address("서울시 테스트구")
                .reservationDurationMinutes(90)
                .reservationSlotMinutes(30)
                .arrivalGraceMinutes(10)
                .build());
        createdStores.add(saved);
        return saved;
    }

    private Member saveMember(String name) {
        Member member = memberRepository.save(Member.builder()
                .name(name)
                .phone("010-0000-0000")
                .userType(UserType.CUSTOMER)
                .build());
        createdMembers.add(member);
        return member;
    }
}
