package com.catchtable.notification.service;

import com.catchtable.global.exception.BusinessException;
import com.catchtable.global.exception.ErrorCode;
import com.catchtable.member.entity.Member;
import com.catchtable.member.entity.UserType;
import com.catchtable.member.repository.MemberRepository;
import com.catchtable.notification.dto.NotificationSettingRequest;
import com.catchtable.notification.dto.NotificationSettingResponse;
import com.catchtable.notification.dto.NotificationType;
import com.catchtable.notification.entity.MemberNotificationSetting;
import com.catchtable.notification.repository.MemberNotificationSettingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NotificationSettingService {

    private final MemberNotificationSettingRepository settingRepository;
    private final MemberRepository memberRepository;
    private final Clock clock;

    public NotificationSettingResponse getSettings(Long memberId) {
        requireCustomer(memberId);
        return NotificationSettingResponse.from(find(memberId));
    }

    // 같은 값 재요청도 성공
    @Transactional
    public NotificationSettingResponse updateSettings(Long memberId, NotificationSettingRequest request) {
        requireCustomer(memberId);
        settingRepository.upsert(memberId, request.reservationEnabled(), request.waitingEnabled(),
                OffsetDateTime.now(clock));
        return NotificationSettingResponse.from(find(memberId));
    }

    // 발송 전 수신 여부 (필수는 조회 없이 통과, 설정 없는 회원은 기본값)
    // 트랜잭션 미시작 (DB 연결 선점 방지)
    @Transactional(propagation = Propagation.SUPPORTS)
    public boolean allows(Long memberId, NotificationType type) {
        return MemberNotificationSetting.isRequired(type) || find(memberId).allows(type);
    }

    // 여러 회원 수신 여부를 한 번에 (순번 알림처럼 대상이 많을 때, 조회 1번)
    @Transactional(propagation = Propagation.SUPPORTS)
    public Set<Long> allowedMembers(Collection<Long> memberIds, NotificationType type) {
        if (memberIds.isEmpty()) {
            return Set.of();
        }
        if (MemberNotificationSetting.isRequired(type)) {
            return Set.copyOf(memberIds);
        }
        Map<Long, MemberNotificationSetting> saved = settingRepository.findAllById(memberIds).stream()
                .collect(Collectors.toMap(MemberNotificationSetting::getMemberId, Function.identity()));
        return memberIds.stream()
                .filter(id -> saved.getOrDefault(id, MemberNotificationSetting.defaults(id)).allows(type))
                .collect(Collectors.toSet());
    }

    private MemberNotificationSetting find(Long memberId) {
        return settingRepository.findById(memberId)
                .orElseGet(() -> MemberNotificationSetting.defaults(memberId));
    }

    private void requireCustomer(Long memberId) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
        if (member.getUserType() != UserType.CUSTOMER) {
            throw new BusinessException(ErrorCode.FORBIDDEN_MEMBER_TYPE);
        }
    }
}
