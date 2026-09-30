package com.catchtable.member.service;


import com.catchtable.global.exception.BusinessException;
import com.catchtable.global.exception.ErrorCode;
import com.catchtable.member.dto.MemberResponse;
import com.catchtable.member.dto.MemberUpdateRequest;
import com.catchtable.member.entity.Member;
import com.catchtable.member.entity.UserType;
import com.catchtable.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemberService {

    private final MemberRepository memberRepository;

    public MemberResponse getMe(Long memberId)
    {
        return MemberResponse.from(findCustomer(memberId));
    }

    @Transactional
    public MemberResponse updateMe(Long memberId, MemberUpdateRequest request)
    {
        Member member=findCustomer(memberId);
        member.changeProfile(request.name(), request.phone());
        return MemberResponse.from(member);
    }


    private Member findCustomer(Long memberId)
    {
        Member member=memberRepository.findById(memberId)
                .orElseThrow(()->new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
        if(member.getUserType()!= UserType.CUSTOMER)
        {
            throw new BusinessException(ErrorCode.FORBIDDEN_MEMBER_TYPE);
        }
        return member;
    }

}
