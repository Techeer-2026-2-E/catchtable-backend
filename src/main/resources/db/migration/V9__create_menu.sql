-- 매장 메뉴 (기능 #30)
--   - 점주가 등록·수정한다. 삭제·판매 중지는 기능 명세에 없어 제외
--   - 같은 매장 안에서 메뉴명 중복 허용 (사이즈별 메뉴 등)
--   - price: 원 단위, 0원(서비스 메뉴) 허용
--   - description: 선택값. PATCH에서 빈 문자열은 NULL로 저장한다
CREATE TABLE menu (
                      id           BIGINT        GENERATED ALWAYS AS IDENTITY,
                      store_id     BIGINT        NOT NULL,
                      name         VARCHAR(100)  NOT NULL,
                      price        INT           NOT NULL,
                      description  VARCHAR(1000),
                      created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
                      updated_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),

                      CONSTRAINT pk_menu       PRIMARY KEY (id),
                      CONSTRAINT fk_menu_store FOREIGN KEY (store_id) REFERENCES store (id),
                      CONSTRAINT chk_menu_price CHECK (price >= 0)
);

CREATE INDEX idx_menu_store_id ON menu (store_id);