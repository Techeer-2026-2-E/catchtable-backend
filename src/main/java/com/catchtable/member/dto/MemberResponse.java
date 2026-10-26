package com.catchtable.member.dto;

import com.catchtable.member.entity.Member;

public record MemberResponse (
        Long id,
        String name,
        String phone
){
    public static MemberResponse from(Member member)
    {
        return new MemberResponse(
                member.getId(),
                member.getName(),
                member.getPhone()
        );
    }
}
