# 확인할 사항

이 문서는 미결정·불일치의 원본으로 가는 안내다. 이슈·Notion에 있는 진행 상태나 기능 목록을 복제하지 않는다. 이슈가 없는 중요한 차이를 발견했을 때만 확인일·근거·다음 행동을 남긴다.

## Git·코드컨벤션 문서 차이 — 2026-10-27 확인

- [팀 Git 규칙](https://app.notion.com/p/bf748d76fa32835d8c0001bcac5895ea)은 `dev`와 `<type>: <제목>`을 기준으로 한다. 원격 기본 브랜치와 [구현 브랜치 CI](https://github.com/Techeer-2026-2-E/catchtable-backend/blob/e6c668bbb6be920cb2ff47042e82edfe832c0a18/.github/workflows/ci.yml)도 `dev`를 사용한다. 원격 갱신 결과 `main`은 초기 설정 상태이며 이번에 문서만 반영한다.
- 오래된 [코드컨벤션](https://app.notion.com/p/00048d76fa32825bbe8981f039abbca6)에는 `develop`, `snake_case`, `except` 등 현재 Java 저장소와 맞지 않는 설명이 남아 있다.
- 이번 로컬 지침은 팀 Git 규칙과 기존 Java 코드의 이름·구조를 안내한다. 외부 Notion 원문은 변경하지 않았다. 팀에서 코드컨벤션을 Java와 현재 브랜치 기준으로 정리할 때 이 항목을 해결한다.

## 기존에 논의·연결할 항목

| 주제 | 원본·근거 | 다음 확인 |
| --- | --- | --- |
| 예약 시작 시각 이후 변경 정책 | [기존 의사결정 기록](https://github.com/Techeer-2026-2-E/catchtable-backend/blob/e6c668bbb6be920cb2ff47042e82edfe832c0a18/docs/development-decisions.md#예약-시각이-지난-뒤의-예약-변경--팀-논의-대기) | 현재 Notion 정책과 팀 결정을 확인. 과거 기록만으로 이번에 다시 미결정이라고 확정하지 않음 |
| 결제 승인·결과 불명·환불과 선점 해제/만료 연결 | [선점 구현의 경계](https://github.com/Techeer-2026-2-E/catchtable-backend/blob/e6c668bbb6be920cb2ff47042e82edfe832c0a18/docs/development-decisions.md#선점-구현의-경계), [Notion API](https://app.notion.com/p/5cb48d76fa3283e7b711010662ec4e75) | 관련 작업에서 공통 결제 계약·코드·테스트 확인 |
| 프런트 계약·화면 연동 | [기존 의사결정 기록](https://github.com/Techeer-2026-2-E/catchtable-backend/blob/e6c668bbb6be920cb2ff47042e82edfe832c0a18/docs/development-decisions.md), [API 정본](https://app.notion.com/p/5cb48d76fa3283e7b711010662ec4e75) | 변경한 API·이벤트의 실제 호출부와 화면 확인. 백엔드 테스트 통과와 구분 |
| GitHub 보호 규칙·필수 CI | [프로젝트 협업 기준](PROJECT.md#협업-기준) | 2026-10-27 `main`·`dev` 보호 미설정 확인. 팀의 리뷰·CI 기준을 GitHub에서 강제할지는 후속 팀 결정 |

새 항목에는 확인일·대상 브랜치/커밋, 확인한 사실과 근거, 모르는 점, 영향받는 작업, 다음 행동·이슈 링크를 적는다. 해결되면 결정은 정본에, 실제 변경·검증은 PR 또는 work-log에 남기고 이 문서의 임시 항목은 정리한다. 기존 기록을 과거 사실과 다르게 덮어쓰지 않는다.
