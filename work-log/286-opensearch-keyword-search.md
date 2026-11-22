# 매장 키워드 검색 OpenSearch 도입

- 작업일: 2026-11-22
- 관련 이슈·PR·Notion: #286
- 기준 브랜치·커밋·환경: `feat/286-opensearch-keyword-search` (dev 1f51158), 로컬 macOS(M3 Pro), OpenSearch 2.19.1 + analysis-nori

## 배경과 목표

2026-11-13 실측에서 DB 키워드 검색(`name ILIKE '%키워드%'`)은 빠르지만(0페이지 9~15ms) 품질 문제가 있었다. 표기가 다르면 결과가 갈리고(돈까스 585 / 돈가스 119 / 돈카츠 58, 초밥 345 / 스시 969), 띄어쓰기·여러 단어 검색은 0건(`스타 벅스`, `강남 파스타`)이었으며, 정렬이 id 순이었다.

범위: `GET /api/user/stores`에 `keyword`가 있고 `date`·`time`·`partySize`가 없을 때(카테고리는 함께 가능)만 OpenSearch로 검색한다. 그 외 검색과 다른 API, 인기순, 변경 자동 동기화, 배포 구성은 제외한다.

## 판단과 근거

- 켜고 끄기: `catchtable.search.engine=postgres|opensearch`(기본 postgres, 환경변수 `SEARCH_ENGINE`). opensearch가 아니고 재색인도 끈 상태면 OpenSearch 연결을 만들지 않는다. 그래서 배포 서버는 지금과 같다.
- 장애 대체: OpenSearch 호출의 `RestClientException`(연결 실패·타임아웃·5xx·색인 없음 404)은 경고 로그 후 기존 DB 키워드 검색으로 대체한다.
- 클라이언트: 새 라이브러리 없이 Spring `RestClient`로 JSON을 보낸다(연결 1초·응답 2초).
- 분석기: nori `decompound_mode`를 `discard`로 했다. `mixed`는 원래 단어와 분해된 조각이 같은 위치에 겹쳐 나와 동의어 규칙을 만들 수 없었다(`돈가스 → 돈 (position increment 0)`).
- 동의어: 검색 시 `synonym_graph`를 적용한다. 사전은 `src/main/resources/opensearch/synonyms.txt`이고 재색인할 때 색인 설정에 넣는다.
- 띄어쓰기: `name.compact`는 공백을 지우고 2글자 조각(bigram)으로 나누며, 모든 조각이 맞아야 한다.
- 표기 통일: nori는 이름 안의 `스시`를 앞뒤 글자에 따라 `츠스시`, `복스 씨`, `스 시조`처럼 다르게 쪼갠다. 그래서 동의어 경로가 이런 이름을 놓치고, compact 경로는 입력한 표기로만 찾았다. 1차 측정에서 초밥 825 / 스시 1,280, 돈까스 753 / 돈가스 731 / 돈카츠 730으로 결과가 갈렸다. 이를 고치려고 compact 분석기에 `mapping` 문자 필터를 추가했다. 동의어 사전에서 자동으로 만들며, 같은 뜻 단어를 첫 단어로 통일한다(`돈가스=>돈까스`, `스시=>초밥`).
- 검색식: `cross_fields` + `operator=and`(name^3, menus^2, address, area, categoryName)와 `name.compact` 중 하나만 맞으면 된다. 단어마다 어느 필드에든 맞아야 하므로, `강남 파스타`는 주소(강남구)와 이름·메뉴(파스타)가 함께 맞아야 나온다.
- 정렬: 입점 매장(`reservationEnabled`)은 관련도에 ×1.1을 곱한 뒤 `_score` 내림차순 → `id` 오름차순이다. 배수는 `catchtable.search.opensearch.entered-store-boost`다.
- 페이지: OpenSearch에서 `size+1`건을 받아 `hasNext`를 계산한다. 키워드 검색은 `engine=opensearch`일 때 page 0~49만 허용하고, 넘으면 `400 INVALID_INPUT`이다. 대체 경로에서도 같다.
- 응답: OpenSearch는 id 순서만 주고, 매장·영업 상태·별점은 기존처럼 DB에서 읽는다. `StoreListResponse`는 바뀌지 않는다.

## 실제 변경

- `store/search`: `OpenSearchConfig`(조건부 연결), `StoreSearchIndex`(검색·색인·별칭), `StoreReindexService`(전체 재색인), `StoreReindexRunner`(`catchtable.search.reindex=true`일 때만 실행)
- `StoreService.searchStores`: 키워드만 있는 검색이고 opensearch를 켰을 때 `searchByKeyword`로 분기한다. 기존 메서드는 바꾸지 않았다.
- `src/main/resources/opensearch/stores-index.json`, `synonyms.txt`, `application.yml`의 `catchtable.search` 설정
- `docker/opensearch/Dockerfile`(2.19.1 + nori), `docker-compose.yml`의 `opensearch` 서비스(profile `search`, 단일 노드, 보안 끔, 힙 512MB, 9200)
- `build.gradle`: `testImplementation 'org.testcontainers:junit-jupiter'`

재색인 순서: `stores_v{n}`(기존 최대 n+1) 생성 → id 순 1,000건 단위로 매장·메뉴 bulk 색인 → refresh → 별칭 `stores` 교체 → 새 색인 외의 `stores_v*` 삭제

## 검증

| 확인한 내용 | 실제 명령 또는 방법·환경 | 결과·근거 |
| --- | --- | --- |
| 동의어·띄어쓰기·지역+메뉴·입점 우선·카테고리·페이지 제한·hasNext·재색인 별칭 교체 | `StoreOpenSearchKeywordSearchTest`, Testcontainers(`docker/opensearch` 이미지), 테스트 DB `catchtable_test` | 9개 통과. nori가 다르게 쪼개는 `키세츠스시`는 표기 통일 필터를 빼면 실패하는 것을 확인 |
| 기본 postgres 동작, OpenSearch 장애 시 DB 대체 | `StoreKeywordSearchEngineTest` | 3개 통과 |
| 전체 빌드 | `DB_NAME=catchtable_test ./gradlew build` | 테스트 1,223개 통과, BUILD SUCCESSFUL |
| 공공데이터 전체 재색인 | perf DB(118,200 매장) + 로컬 OpenSearch, `--catchtable.search.reindex=true` | 118,200건 10.7초(v1), 12.2초(v2, 이전 색인 삭제 포함) |
| 품질 비교 | `~/catchtable-perf/search/quality-baseline.py`, 같은 커밋·같은 데이터에서 `engine=postgres`/`opensearch` | 아래 표 |

| 키워드 | DB 결과 수 | OpenSearch 결과 수 | DB 응답 ms | OpenSearch 응답 ms |
| --- | --- | --- | --- | --- |
| 초밥 | 345 | 1,312 | 120.2* | 35.0* |
| 스시 | 969 | 1,312 | 11.2 | 19.4 |
| 돈까스 | 585 | 762 | 14.3 | 19.6 |
| 돈가스 | 119 | 762 | 21.5 | 19.5 |
| 돈카츠 | 58 | 762 | 11.7 | 19.4 |
| 강남 파스타 | 0 | 36 | 8.6 | 20.5 |
| 스타 벅스 | 0 | 18 | 7.9 | 18.9 |
| 스타벅스 | 18 | 18 | 9.7 | 28.3 |
| 카페 | 2,025 | 2,500 이상** | 11.0 | 24.7 |

\* 첫 키워드라 캐시가 차기 전 값이다. \*\* 50페이지 제한(size 50)까지만 셀 수 있다. 카테고리 이름 `카페`도 검색 대상이라 카페 업종이 모두 포함된다. 응답 시간은 5회 중앙값 1회 측정이고, 앱·DB·OpenSearch를 한 PC에서 실행했다.

- 돈까스 계열 762는 DB 검색 세 표기 결과의 합(585+119+58)과 같다.
- `강남 파스타` 36건 중 32건은 강남구, 4건은 서초구다. 서초구 4건은 이름이나 주소에 `강남`이 들어 있다(`강남역 파스타`, `강남대로`, `신세계 강남점`).
- 미실행: CI, 프런트 연동, 배포 서버 확인

## 남은 점과 후속 작업

- 매장·메뉴 변경은 자동으로 동기화되지 않는다. 바뀐 내용은 전체 재색인해야 반영된다(Outbox 동기화는 범위 밖).
- 동의어를 추가하려면 `synonyms.txt`를 수정하고 재색인한다. 초기 목록 외의 추가는 팀 결정이 필요하다.
- OpenSearch 경로의 응답 시간이 DB보다 약 8~15ms 길다(OpenSearch 호출 + DB에서 id로 매장 조회).
- 배포 서버 구성(OpenSearch 운영 여부·자원)은 팀 논의가 필요하다. 기본값 postgres라 지금 배포에는 영향이 없다.
- 키워드 검색 50페이지 초과 400은 새 규칙이라 프런트에 알려야 한다.
