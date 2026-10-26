package com.catchtable.review.dto;

import jakarta.validation.constraints.*;

// 보낸 필드만 수정한다. null(키 없음)은 유지, imageUrl ""은 사진 삭제
public record ReviewUpdateRequest(

        @Min(1)
        @Max(5)
        Integer rating,

        // 보냈다면 공백만으로는 안 된다 (작성 시 @NotBlank와 같은 기준)
        @Size(max=1000)
        @Pattern(regexp = "(?s).*\\S.*")
        String content,

        @Size(max=2048)
        @Pattern(regexp = "^$|^https?://.+")
        String imageUrl

) {
}
