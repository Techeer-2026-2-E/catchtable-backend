package com.catchtable.review.entity;


import com.catchtable.global.common.BaseTimeEntity;
import com.catchtable.reservation.entity.Reservation;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;

@Entity
@Table(name="review")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Review extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name="reservation_id", unique = true)
    private Reservation reservation;


    @Column(nullable = false)
    private int rating;

    @Column(nullable = false, length = 1000)
    private String content;

    @Column(name = "image_url", length = 2048)
    private String imageUrl;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    private Review(Reservation reservation, int rating, String content, String imageUrl) {
        this.reservation = reservation;
        this.rating = rating;
        this.content = content;
        this.imageUrl = StringUtils.hasText(imageUrl) ? imageUrl : null;
    }

    public static Review ofReservation(Reservation reservation, int rating, String content, String imageUrl)
    {
        return new Review(reservation,rating,content,imageUrl);
    }

    // 보낸 값만 바꾼다. imageUrl은 null이면 유지, 빈 문자열이면 사진 삭제
    public void update(Integer rating, String content, String imageUrl) {
        if (rating != null) {
            this.rating = rating;
        }
        if (content != null) {
            this.content = content;
        }
        if (imageUrl != null) {
            this.imageUrl = StringUtils.hasText(imageUrl) ? imageUrl : null;
        }
    }

    // 이미 삭제된 리뷰는 처음 삭제 시각을 유지한다
    public void delete(OffsetDateTime now) {
        if (isDeleted()) {
            return;
        }
        this.deletedAt = now;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }
}
