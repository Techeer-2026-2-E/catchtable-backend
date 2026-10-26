# catchtable-backend

Techeer 2026-2 E팀 — 캐치테이블 클론 백엔드

현재 `main`에는 공통 문서와 협업 양식만 반영되어 있다. 실행 코드·설정·기존 CI는 [dev 브랜치](https://github.com/Techeer-2026-2-E/catchtable-backend/tree/dev)에 있으며, 이번 문서 반영에 기존 기능 통합은 포함하지 않았다.

## 처음 읽을 문서

| 목적 | 문서 |
| --- | --- |
| 구성·실행·검증·Notion 정본·브랜치 기준 | [프로젝트 기준](docs/PROJECT.md) |
| Codex·Claude Code 공통 작업 규칙 | [AGENTS.md](AGENTS.md) |
| 팀원의 도구 준비와 작업 순서 | [AI 협업 안내](docs/ai-workflow.md) |
| 변경 이유와 검증 근거 | [작업 기록](work-log/README.md) |
| 문서 차이와 결정·확인 경로 | [확인할 사항](docs/OPEN_ITEMS.md) |

## 문서 운영

1. Notion 명세와 이슈에서 문제·범위·완료 조건을 확인한다. AI 요청에는 [작업 지시 양식](docs/templates/task-brief.md)을 사용할 수 있다.
2. 관련 코드·테스트·기록을 읽고 변경한다. 앱 실행과 테스트는 구현 코드가 있는 `dev` 체크아웃에서 [프로젝트 기준](docs/PROJECT.md#검증)을 따른다.
3. [PR 양식](.github/pull_request_template.md)에 결과와 검증을 적는다. 중요한 변경은 [work-log](work-log/README.md)도 연결하고, 작은 수정은 PR 본문으로 마친다.

요구사항은 Notion, 구현은 해당 작업 브랜치의 코드, 진행 상태는 이슈·PR에서 확인한다. 선택 CI와 개인 메모 양식은 [선택 항목](docs/ai-workflow.md#선택-항목)에 있다. 이 문서 묶음은 `dev`에 자동으로 반영되지 않으므로 구현 작업에서 사용할 지침을 명시적으로 전달한다.

## 기술 스택

`dev` 구현과 설정에서 확인한 기준이다.

- Java 21 / Spring Boot 3.3.4
- PostgreSQL 16 / Spring Data JPA / Flyway
- Gradle Wrapper

## 실행 방법

실행 코드가 있는 `dev` 체크아웃에서 [로컬 실행·검증 안내](docs/PROJECT.md#로컬-실행)를 따른다. 현재 문서 전용 `main`에서는 `./gradlew`나 Compose를 실행할 수 없다.

## 브랜치 전략

```
main          배포용 최종본. 직접 push 금지.
 └── dev      개발 통합 브랜치 (default)
      └── feature/12-hold-api
```

- 모든 작업은 `dev`에서 브랜치를 따서 시작하고, PR은 `dev`로 보냅니다.
- `main`은 발표/배포 시점에만 `dev → main` PR로 올립니다.

### 브랜치 이름

`<타입>/<이슈번호>-<짧은-설명>`

| 타입 | 용도 |
|---|---|
| `feature` | 새 기능·API |
| `fix` | 버그 수정 |
| `refactor` | 동작 변화 없는 구조 개선 |
| `test` | 테스트 추가 |
| `chore` | 설정·CI·의존성 |
| `docs` | 문서 |
| `hotfix` | main 긴급 수정 |

예: `feature/12-hold-create-api`, `fix/23-hold-expire-race`

### 커밋 / PR 제목 컨벤션

```
feat: 홀드 생성 API 추가
fix: 홀드 만료 시 좌석 미반환 수정
refactor: 테이블 배정 인터페이스 분리
test: 동시성 통합 테스트 추가
chore: CI 워크플로 추가
docs: README 실행법 보강
```

squash merge를 쓰기 때문에 **PR 제목이 곧 커밋 메시지**가 됩니다. PR 제목도 위 규칙을 따라주세요.

### 머지 규칙

- `feature → dev` : **Squash and merge**
- `dev → main` : **Merge commit**
- Rebase merge는 사용하지 않습니다.

## 작업 흐름

```bash
git switch dev
git pull origin dev
git switch -c feature/12-hold-api
# 작업 & 커밋
git push -u origin feature/12-hold-api
# GitHub에서 PR 생성 (base: dev)
```
