package com.catchtable.waiting.controller;

import com.catchtable.member.entity.Member;
import com.catchtable.member.entity.UserType;
import com.catchtable.member.repository.MemberRepository;
import com.catchtable.store.entity.Store;
import com.catchtable.store.entity.StoreCategory;
import com.catchtable.store.repository.StoreRepository;
import com.catchtable.waiting.entity.Waiting;
import com.catchtable.waiting.entity.WaitingStatus;
import com.catchtable.waiting.repository.WaitingEndHistoryRepository;
import com.catchtable.waiting.repository.WaitingRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 현재 대기순번 조회 테스트 (앞 팀 수는 쿼리 결과가 핵심이라 실제 DB로 확인)
@SpringBootTest
@AutoConfigureMockMvc
class UserWaitingControllerTest {

    private static final LocalDate TODAY = LocalDate.of(2030, 1, 10);

    @Autowired MockMvc mockMvc;
    @Autowired WaitingRepository waitingRepository;
    @Autowired WaitingEndHistoryRepository historyRepository;
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
        historyRepository.deleteAllInBatch();
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
    @DisplayName("호출된 웨이팅은 호출 시각과 만료 시각을 함께 돌려준다 (재접속 복구용)")
    void calledWaitingExposesCallTimes() throws Exception {
        Waiting mine = saveCalledWaiting(me, 1);

        String body = getWaiting(me, mine.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CALLED"))
                .andReturn().getResponse().getContentAsString();

        assertThat(instantAt(body, "calledAt")).isEqualTo(OffsetDateTime.parse("2030-01-10T12:00:00+09:00").toInstant());
        assertThat(instantAt(body, "expiresAt")).isEqualTo(OffsetDateTime.parse("2030-01-10T12:10:00+09:00").toInstant());
    }

    @Test
    @DisplayName("도착 의사를 응답한 웨이팅도 호출 시각과 만료 시각을 돌려준다")
    void confirmedWaitingExposesCallTimes() throws Exception {
        Waiting mine = saveCalledWaiting(me, 1);
        changeStatus(mine, WaitingStatus.CONFIRMED);

        getWaiting(me, mine.getId())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.calledAt").isNotEmpty())
                .andExpect(jsonPath("$.expiresAt").isNotEmpty());
    }

    @Test
    @DisplayName("호출 전 웨이팅은 호출 시각과 만료 시각이 없다")
    void waitingBeforeCallHasNoCallTimes() throws Exception {
        Waiting mine = saveWaiting(me, store, TODAY, 1, WaitingStatus.WAITING);

        getWaiting(me, mine.getId())
                .andExpect(jsonPath("$.status").value("WAITING"))
                .andExpect(jsonPath("$.calledAt").doesNotExist())
                .andExpect(jsonPath("$.expiresAt").doesNotExist())
                .andExpect(jsonPath("$.respondedAt").doesNotExist())
                .andExpect(jsonPath("$.arrivalIntent").doesNotExist());
    }

    @Test
    @DisplayName("호출 뒤 종료된 웨이팅은 호출 시각과 만료 시각을 숨긴다")
    void endedWaitingHasNoCallTimes() throws Exception {
        Waiting mine = saveCalledWaiting(me, 1);
        respond(me, mine.getId()).andExpect(status().isOk());
        changeStatus(mine, WaitingStatus.NO_SHOW);

        getWaiting(me, mine.getId())
                .andExpect(jsonPath("$.status").value("NO_SHOW"))
                .andExpect(jsonPath("$.calledAt").doesNotExist())
                .andExpect(jsonPath("$.expiresAt").doesNotExist())
                .andExpect(jsonPath("$.respondedAt").doesNotExist())
                .andExpect(jsonPath("$.arrivalIntent").doesNotExist());
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

    @Test
    @DisplayName("호출된 내 웨이팅에 응답하면 CALLED 그대로 방문 의사를 기록한다")
    void respondToCallRecordsArrivalIntent() throws Exception {
        Waiting mine = saveCalledWaiting(me, 1);

        String first = respond(me, mine.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.waitingId").value(mine.getId()))
                .andExpect(jsonPath("$.status").value("CALLED"))
                .andExpect(jsonPath("$.arrivalIntent").value("COMING"))
                .andExpect(jsonPath("$.respondedAt").isNotEmpty())
                .andExpect(jsonPath("$.expiresAt").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String retried = respond(me, mine.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CALLED"))
                .andExpect(jsonPath("$.arrivalIntent").value("COMING"))
                .andReturn().getResponse().getContentAsString();
        String detail = getWaiting(me, mine.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CALLED"))
                .andExpect(jsonPath("$.arrivalIntent").value("COMING"))
                .andExpect(jsonPath("$.respondedAt").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        assertThat(instantAt(retried, "respondedAt")).isEqualTo(instantAt(first, "respondedAt"));
        assertThat(instantAt(detail, "respondedAt")).isEqualTo(instantAt(first, "respondedAt"));
        assertThat(instantAt(detail, "expiresAt")).isEqualTo(instantAt(first, "expiresAt"));
    }

    @Test
    @DisplayName("다른 회원의 웨이팅·없는 웨이팅 응답은 404, 헤더가 없으면 401")
    void respondRequiresOwnWaiting() throws Exception {
        Waiting others = saveCalledWaiting(saveMember("남"), 1);

        respond(me, others.getId())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WAITING_NOT_FOUND"));
        respond(me, -1L)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WAITING_NOT_FOUND"));
        mockMvc.perform(post("/api/user/waitings/{waitingId}/accept", others.getId()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("명세 경로로 신청하면 201, 재시도는 200, 예전 경로는 404")
    void createWaitingWithStoreIdInBody() throws Exception {
        Store saved = storeRepository.findById(store.getId()).orElseThrow();
        saved.openWaiting();
        storeRepository.save(saved);
        String body = "{\"storeId\":" + store.getId() + ",\"partyCount\":2}";

        mockMvc.perform(post("/api/user/waitings").header("X-Member-Id", me.getId())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/user/waitings").header("X-Member-Id", me.getId())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/stores/{storeId}/waitings", store.getId()).header("X-Member-Id", me.getId())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"partyCount\":2}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("내 웨이팅을 취소하면 200과 최신 종료 상태를 돌려주고, 반복해도 200")
    void cancelWaitingReturnsLatestState() throws Exception {
        Waiting mine = saveCalledWaiting(me, 1);

        mockMvc.perform(delete("/api/user/waitings/{waitingId}", mine.getId()).header("X-Member-Id", me.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.waitingId").value(mine.getId()))
                .andExpect(jsonPath("$.status").value("CANCELED"))
                .andExpect(jsonPath("$.reason").value("CUSTOMER_CANCELED"))
                .andExpect(jsonPath("$.detail").value("고객 취소"))
                .andExpect(jsonPath("$.endedAt").isNotEmpty());
        mockMvc.perform(delete("/api/user/waitings/{waitingId}", mine.getId()).header("X-Member-Id", me.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELED"));
    }

    @Test
    @DisplayName("다른 회원의 웨이팅·없는 웨이팅 취소는 404, 헤더가 없으면 401")
    void cancelRequiresOwnWaiting() throws Exception {
        Waiting others = saveWaiting(saveMember("남"), store, TODAY, 1, WaitingStatus.WAITING);

        mockMvc.perform(delete("/api/user/waitings/{waitingId}", others.getId()).header("X-Member-Id", me.getId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WAITING_NOT_FOUND"));
        mockMvc.perform(delete("/api/user/waitings/{waitingId}", -1L).header("X-Member-Id", me.getId()))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/user/waitings/{waitingId}", others.getId()))
                .andExpect(status().isUnauthorized());
    }

    private ResultActions respond(Member member, Long waitingId) throws Exception {
        return mockMvc.perform(post("/api/user/waitings/{waitingId}/accept", waitingId)
                .header("X-Member-Id", member.getId()));
    }

    // 호출된 웨이팅
    private Waiting saveCalledWaiting(Member member, int number) {
        Waiting waiting = saveWaiting(member, store, TODAY, number, WaitingStatus.CALLED);
        jdbcTemplate.update("update waiting set called_at = ?, call_expires_at = ?, call_count = 1 where id = ?",
                OffsetDateTime.parse("2030-01-10T12:00:00+09:00"),
                OffsetDateTime.parse("2030-01-10T12:10:00+09:00"), waiting.getId());
        return waiting;
    }

    private ResultActions getWaiting(Member member, Long waitingId) throws Exception {
        return mockMvc.perform(get("/api/user/waitings/{waitingId}", waitingId)
                .header("X-Member-Id", member.getId()));
    }

    // 응답의 시각 필드를 시간대와 무관하게 같은 시점으로 비교하려고 Instant로 변환
    private Instant instantAt(String body, String field) {
        String value = JsonPath.read(body, "$." + field);
        return OffsetDateTime.parse(value).toInstant();
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
