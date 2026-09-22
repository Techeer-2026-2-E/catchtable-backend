# catchtable-backend

Techeer 2026-2 E팀 — 캐치테이블 클론 백엔드

## 기술 스택

- Java 21 / Spring Boot 3.3
- MySQL 8.0 (InnoDB)
- Gradle

## 실행 방법

> 초기 프로젝트 세팅 후 업데이트 예정

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
