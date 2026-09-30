package com.catchtable.member.controller;

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
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 실제 PostgreSQL(docker compose)에 Flyway 스키마로 실행. 각 테스트는 롤백된다
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class UserMemberControllerTest {

    private static final String MEMBER_ID_HEADER = "X-Member-Id";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long customerId;
    private Long ownerId;

    @BeforeEach
    void setUp() {
        customerId = insertMember("김고객", "010-1111-1111", "CUSTOMER");
        ownerId = insertMember("최점주", "010-4444-4444", "OWNER");
    }

    private Long insertMember(String name, String phone, String userType) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO member (name, phone, user_type) VALUES (?, ?, ?)
                RETURNING id""", Long.class, name, phone, userType);
    }

    private ResultActions patchMe(Object memberId, String body) throws Exception {
        return mockMvc.perform(patch("/api/user/members/me")
                .header(MEMBER_ID_HEADER, memberId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    @Test
    @DisplayName("헤더의 회원 id로 내 정보를 조회한다")
    void getMe() throws Exception {
        mockMvc.perform(get("/api/user/members/me").header(MEMBER_ID_HEADER, customerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(customerId))
                .andExpect(jsonPath("$.name").value("김고객"))
                .andExpect(jsonPath("$.phone").value("010-1111-1111"));
    }

    @Test
    @DisplayName("보낸 항목만 수정하고 나머지는 유지한다")
    void updateOnlySentField() throws Exception {
        patchMe(customerId, """
                {"phone": "010-9999-9999"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("김고객"))
                .andExpect(jsonPath("$.phone").value("010-9999-9999"));

        mockMvc.perform(get("/api/user/members/me").header(MEMBER_ID_HEADER, customerId))
                .andExpect(jsonPath("$.phone").value("010-9999-9999"));
    }

    @Test
    @DisplayName("헤더가 없으면 401")
    void missingHeader() throws Exception {
        mockMvc.perform(get("/api/user/members/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("헤더가 숫자가 아니면 401")
    void invalidHeader() throws Exception {
        mockMvc.perform(get("/api/user/members/me").header(MEMBER_ID_HEADER, "abc"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("없는 회원이면 404")
    void memberNotFound() throws Exception {
        mockMvc.perform(get("/api/user/members/me").header(MEMBER_ID_HEADER, 999_999_999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MEMBER_NOT_FOUND"));
    }

    @Test
    @DisplayName("점주는 고객 API를 쓸 수 없다 (403)")
    void ownerForbidden() throws Exception {
        mockMvc.perform(get("/api/user/members/me").header(MEMBER_ID_HEADER, ownerId))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_MEMBER_TYPE"));
    }

    @Test
    @DisplayName("연락처 형식이 틀리면 400")
    void invalidPhone() throws Exception {
        patchMe(customerId, """
                {"phone": "01099999999"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("이름이 공백뿐이면 400")
    void blankName() throws Exception {
        patchMe(customerId, """
                {"name": "   "}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }
}
