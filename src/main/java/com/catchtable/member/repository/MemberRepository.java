package com.catchtable.member.repository;

import com.catchtable.member.entity.Member;
import org.springframework.data.jpa.repository.JpaRepository;

// 회원 Repository (findById, save 등 기본 메서드 제공)
public interface MemberRepository extends JpaRepository<Member, Long> {
}