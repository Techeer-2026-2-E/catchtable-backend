package com.catchtable.review.controller;


import com.catchtable.global.auth.LoginMember;
import com.catchtable.review.dto.ReviewCreateRequest;
import com.catchtable.review.dto.ReviewResponse;
import com.catchtable.review.service.ReviewService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/user/reviews")
public class UserReviewController {

    private final ReviewService reviewService;

    @PostMapping
    public ResponseEntity<ReviewResponse> createReview(
            @LoginMember Long memberId,
            @Valid @RequestBody ReviewCreateRequest request
            )
    {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(reviewService.createReview(memberId, request));
    }
}
