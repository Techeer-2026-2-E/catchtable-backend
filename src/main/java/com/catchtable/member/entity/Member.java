package com.catchtable.member.entity;

import com.catchtable.global.common.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name="member")
@Getter
@NoArgsConstructor(access= AccessLevel.PROTECTED)
public class Member extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 100)
    private String email;

    @Column(length = 255)
    private String password;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(nullable = false, length = 20)
    private String phone;

    @Enumerated(EnumType.STRING)
    @Column(name="user_type", nullable = false,length = 20)
    private UserType userType;

    @Builder
    private Member(String email, String password, String name, String phone, UserType userType) {
        this.email = email;
        this.password = password;
        this.name = name;
        this.phone = phone;
        this.userType = userType;
    }

}
