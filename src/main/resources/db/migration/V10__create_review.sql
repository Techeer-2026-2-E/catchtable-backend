-- 방문 완료 매장 리뷰 (기능 #23)
--   - 이용 완료(COMPLETED)된 본인 예약에만 작성한다. 작성 가능 여부·기한(완료 후 30일)은 서비스에서 검증한다
--   - reservation_id UNIQUE: 방문 1건당 리뷰 1개. 삭제(soft delete)한 리뷰도 행이 남아 있어 같은 예약에 다시 쓸 수 없다
--   - reservation_id NULL 허용: 웨이팅 착석 리뷰를 위해 waiting_id를 추가할 예정
--     (추가 시 reservation_id·waiting_id 중 정확히 하나만 값이 있도록 CHECK 제약을 함께 건다)
--   - 회원·매장은 별도 컬럼 없이 예약(reservation)을 통해 조회한다. 웨이팅 확장 시 조회가 복잡해지면 member_id·store_id 추가를 검토한다
--   - rating: 1~5 정수
--   - image_url: 선택값. 업로드 인프라 없이 URL만 저장한다 (store.image_url과 같은 길이)
--   - deleted_at: 고객 리뷰 삭제(#27)용 soft delete
CREATE TABLE review (
                        id              BIGINT         GENERATED ALWAYS AS IDENTITY,
                        reservation_id  BIGINT,
                        rating          INT            NOT NULL,
                        content         VARCHAR(1000)  NOT NULL,
                        image_url       VARCHAR(2048),
                        created_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),
                        updated_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),
                        deleted_at      TIMESTAMPTZ,

                        CONSTRAINT pk_review             PRIMARY KEY (id),
                        CONSTRAINT fk_review_reservation FOREIGN KEY (reservation_id) REFERENCES reservation (id),
                        CONSTRAINT uk_review_reservation UNIQUE (reservation_id),
                        CONSTRAINT chk_review_rating     CHECK (rating BETWEEN 1 AND 5)
);
