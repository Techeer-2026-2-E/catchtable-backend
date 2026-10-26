# Codex·Claude Code 협업 안내

공통 규칙은 [AGENTS.md](../AGENTS.md) 한곳에서 관리한다. 모델·개인 스킬·에디터가 같을 필요는 없다. 작업의 범위와 검증 근거를 같은 방식으로 남긴다.

## 처음 준비

1. [프로젝트 기준](PROJECT.md)에서 실행 환경과 Notion 정본 위치를 확인한다.
2. 저장소 루트에서 도구를 시작한다. Codex는 `AGENTS.md`를 읽고, Claude Code는 [CLAUDE.md](../CLAUDE.md)의 `@AGENTS.md`를 통해 같은 규칙을 가져온다.
3. 첫 세션에서 읽은 공통 지침과 테스트 DB 기준을 설명하게 해 로딩을 확인한다. Claude Code에서는 `/context`의 Memory files도 확인할 수 있다.
4. 관련 Notion 페이지를 열 수 있는지 확인한다. 연결 도구·MCP를 쓴다면 각자 사용하는 도구에서 본인 계정으로 연결한다. 한 도구의 로그인·권한이 다른 도구로 전달된다고 가정하지 않는다.

Notion 접근이 안 되면 필요한 본문을 제공받고 출처·확인일을 남긴다. 명세가 필요한 변경은 접근하지 못한 내용을 추측해 진행하지 않는다. 관련 없는 로컬 조사·문서 확인은 계속할 수 있다.

Claude Code의 직접 `AGENTS.md` 지원은 버전·설정에 따라 달라진다. `@AGENTS.md`는 별도 공통 규칙 사본을 만들지 않는 import다. 일반 Claude 채팅 등 파일 자동 로딩을 제공하지 않는 환경에서는 공통 지침과 관련 파일을 직접 전달한다. 상세 동작은 [OpenAI 공식 문서](https://learn.chatgpt.com/docs/agent-configuration/agents-md), [Claude Code 공식 문서](https://code.claude.com/docs/en/memory#share-one-file-with-other-coding-tools)를 참고한다.

## 작업 순서

| 시점 | 할 일 | 남길 위치 |
| --- | --- | --- |
| 시작 | Git 변경 상태, 관련 Notion 명세·이슈·코드·기록 확인 | 이번 요청 또는 이슈 |
| 범위 정리 | 목표·포함/제외 범위·완료 조건 확인 | [이슈 양식](../.github/ISSUE_TEMPLATE/task.md), 필요하면 [AI 작업 지시 양식](templates/task-brief.md) |
| 구현 | 기존 패턴으로 변경하고 영향받는 호출부·계약·테스트 확인 | 코드·관련 현재 문서 |
| 검증 | 변경을 확인할 검사를 수행하고 미수행 범위 구분 | PR, 중요한 변경은 work-log |
| 리뷰 준비 | 문제·결과·판단·검증과 관련 링크 작성 | [PR 양식](../.github/pull_request_template.md) |
| 후속 작업 | 미결정·명세 차이를 원본에서 추적 | [확인할 사항](OPEN_ITEMS.md), 이슈·Notion |

작업 지시 양식을 별도 파일로 매번 만들 필요는 없다. 이슈나 채팅 요청에 복사하면 된다. AI가 제시한 대안과 팀이 결정한 내용, 실행한 검사와 제안한 검사를 구분한다. push·외부 문서 갱신 등은 해당 요청에서 허용한 범위에 따른다.

## 기록의 크기

API·이벤트 계약, 상태 흐름, 데이터·마이그레이션, 동시성 처리, 중요한 설계 선택에는 [work-log](../work-log/README.md)를 남긴다. 이미 Notion ERD에 근거가 있으면 링크하고 실제 코드 변경·검증만 보충한다.

오탈자·서식·작은 동작 보존 수정은 PR 본문의 문제·변경·검증으로 충분하다. 별도 work-log가 없으면 이유 한 줄을 적는다. 작업 기록의 유무보다 판단과 검증 근거가 리뷰 가능한지가 기준이다. 수동 인덱스와 이슈·PR 상태 복제는 요구하지 않는다.

## 선택 항목

| 항목 | 준비 위치 | 현재 적용 상태·도입 조건 |
| --- | --- | --- |
| 미결정·불일치 안내 | [OPEN_ITEMS.md](OPEN_ITEMS.md) | 문서 차이와 원본 링크만 관리. 별도 진행 상태표를 만들지 않음 |
| work-log 누락 검사 | [워크플로 예제](templates/work-log-check.yml.example) | `.github/workflows/`에 없는 비활성 예제. 기록 대상 PR을 구분하는 방식이 맞는지 운영 후 도입 |
| 개인 SSoT | [SSOT](templates/local-ssot/SSOT.md), [SESSION](templates/local-ssot/SESSION.md), [CHANGELOG](templates/local-ssot/CHANGELOG.md) | 양식만 제공. 필요할 때 저장소 밖 개인 폴더에 복사 |

CI를 도입할 때는 예제를 `.github/workflows/work-log-check.yml`로 복사하고 `needs-work-log` 라벨을 준비한다. 이 라벨이 있는 PR만 `work-log/`의 실제 기록 파일 변경을 검사한다. 라벨이 없는 PR은 검사를 생략한다. 파일 존재만 검사하며 내용의 정확성·충분함은 리뷰에서 확인한다. GitHub Actions 실행과 브랜치 보호의 필수 검사 지정은 별도 확인 사항이다.

개인 SSoT는 직전 작업을 이어받는 메모다. 팀 결정·요구사항의 유일한 사본을 두지 않는다. 자동 로딩·자동 동기화를 가정하지 말고 기준 커밋과 원본 링크를 다시 확인한다.

개인 `CLAUDE.local.md`와 `.claude/settings.local.json`은 Git에서 제외한다. Codex 개인 설정은 사용자 설정 위치에서 관리한다. 공통 지침에 개인 경로·권한 우회·비밀 값·필수 개인 스킬을 넣지 않는다.
