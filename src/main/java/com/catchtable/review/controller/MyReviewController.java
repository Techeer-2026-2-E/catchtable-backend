package com.catchtable.review.controller;

import com.catchtable.global.auth.LoginMember;
import com.catchtable.review.dto.MyReviewListResponse;
import com.catchtable.review.dto.ReviewListCondition;
import com.catchtable.review.service.ReviewService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/user/members/me/reviews")
public class MyReviewController {

    private final ReviewService reviewService;

    @GetMapping
    public ResponseEntity<MyReviewListResponse> getMyReviews(
            @LoginMember Long memberId,
            @Valid @ModelAttribute ReviewListCondition condition
            )
    {
        return ResponseEntity.ok(reviewService.getMyReviews(memberId, condition));
    }
}
