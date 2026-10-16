package com.catchtable.review.controller;

import com.catchtable.review.dto.ReviewListCondition;
import com.catchtable.review.dto.StoreReviewListResponse;
import com.catchtable.review.service.ReviewService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/user/stores/{storeId}/reviews")
public class UserStoreReviewController {

    private final ReviewService reviewService;

    @GetMapping
    public ResponseEntity<StoreReviewListResponse> getStoreReviews(
            @PathVariable Long storeId,
            @Valid @ModelAttribute ReviewListCondition condition
            )
    {
        return ResponseEntity.ok(reviewService.getStoreReviews(storeId, condition));
    }
}
