package com.catchtable.member.controller;


import com.catchtable.global.auth.LoginMember;
import com.catchtable.member.dto.MemberResponse;
import com.catchtable.member.dto.MemberUpdateRequest;
import com.catchtable.member.service.MemberService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/user/members/me")
public class UserMemberController {

    private final MemberService memberService;

    @GetMapping
    public ResponseEntity<MemberResponse> getMe(@LoginMember Long memberId)
    {
        return ResponseEntity.ok(memberService.getMe(memberId));
    }


    @PatchMapping
    public ResponseEntity<MemberResponse> updateMe(
            @LoginMember Long id,
            @Valid @RequestBody MemberUpdateRequest request)
    {
        return ResponseEntity.ok(memberService.updateMe(id,request));
    }

}
