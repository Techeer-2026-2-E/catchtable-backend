package com.catchtable.member.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record MemberUpdateRequest(
        @Size(max=50)
        @Pattern(regexp=".*\\S.*")
        String name,

        @Pattern(regexp = "^01[016789]-\\d{3,4}-\\d{4}$")
        String phone
) {
}
