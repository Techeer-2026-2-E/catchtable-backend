# 웨이팅 접수 HTTP 측정

11월 22일 Redis OFF 실험과 같은 합성 fixture·열린 도착률 발생기·자원 제한을 사용한다. 제품 JAR의 `POST /api/user/waitings`로 A매장에서는 PG 장부 접수, B매장에서는 기존 등록과 조회를 실제로 실행한다. DB 직접 쓰기는 매장·일자 시험 설정과 결과 대조에만 사용한다. 실험 이미지와 원시 결과 위치, 202/200 계약은 [작업 기록](../../work-log/2026-11-22-waiting-intake-http.md)에 있다.

## 준비

Java 21, Go, Python 3.9 이상, 로컬 Docker Desktop의 `desktop-linux` 컨텍스트, 이전 기준선의 측정기 사본이 필요하다. `BASELINE_TOOLS`는 0차 측정 트리의 `tools/redis-baseline` 또는 0차 원시 아카이브의 `harness` 폴더로 지정한다. 아래 명령은 새 실험 트리 루트에서 실행한다. 이번 회차의 로컬 보관본에는 패치를 적용한 측정기와 사용한 JAR도 함께 저장한다.

```bash
set -e
mkdir -p build/intake-lab/tools
cp "$BASELINE_TOOLS"/{lab.py,run.py,analyze.py,fixture.sql,monitor.sql,client.go} build/intake-lab/tools/
(cd build/intake-lab/tools && patch -p0 < ../../../tools/waiting-intake-benchmark/baseline.patch)
./gradlew --offline bootJar
python3 build/intake-lab/tools/lab.py init
cp build/libs/catchtable-0.0.1-SNAPSHOT.jar build/intake-lab/raw/measured.jar
chmod 644 build/intake-lab/raw/measured.jar
python3 - <<'PYTHON'
import sys
sys.path.insert(0, 'tools/waiting-intake-benchmark')
from measure import L, product_source
(L.OUT / 'measured-source.sha256').write_text(product_source() + '\n')
PYTHON
```

측정기는 난수 이름의 별도 PG16 컨테이너와 네트워크를 만들고 루프백 DB 포트 55484, 앱 포트 18481~18483을 쓴다. 초기화는 기존 실험 폴더·DB를 덮어쓰지 않는다. 전용 fixture를 복제한 회차별 DB에서 Flyway를 적용한다. 이번 실측은 V40 템플릿 복제본에서 당시 V41·V42(현재 V43.1·V43.2)로 올렸다. 새 트리의 init에서 최종 JAR로 처음부터 이 스키마를 적용해도 빈 초기 장부를 사용한다. 같은 날짜에 중지한 실험은 전용 `lab.py start` 후 새 회차로 진행한다.

## 순차 실행

```bash
python3 tools/waiting-intake-benchmark/measure.py smoke
for c in w1k w5k w10k open; do
  python3 tools/waiting-intake-benchmark/measure.py "$c" --apps 3 --repeat 3 || exit 1
done
python3 tools/waiting-intake-benchmark/measure.py open --apps 1 --repeat 3
python3 tools/waiting-intake-benchmark/measure.py retry
python3 tools/waiting-intake-benchmark/measure.py report
python3 build/intake-lab/tools/lab.py stop
```

- 스모크에서는 A의 수용량이 1이다. 루프백 프록시가 앱 1의 실제 202를 받은 뒤 클라이언트 연결을 응답 없이 닫는다. 같은 키를 앱 2로, 같은 회원의 새 키를 앱 3으로 보내 원래 표·순번·Location을 확인한다. 본인 상태 조회·타인 404, 한도 초과 429, 인원·매장 충돌 409, B매장 기존 201/200과 별도 매장의 마지막 한 자리(두 앱 동시 요청)도 확인한다.
- A에는 서로 다른 실제 fixture 회원이 등록한다. 10초에 1천·5천·1만 건, 대표 오픈은 고정 1초 창의 첫 990ms에 1만 건을 균등 발사한다. 키를 첫 전송 전에 만들고 발생기의 POST 재전송은 끈다. B매장은 폭주 전 4초부터 초당 조회 9건·등록 3건을 보낸다. 폭주 후에는 일반 회차 20초, 오픈 회차 60초까지 유지한다.
- A의 회차별 수용량은 목표 신청 수와 같다. 웨이팅 번호 발급 워커는 아직 없으므로 고객 지연은 HTTP에 실제 대기표가 포함된 본문을 읽을 때까지다. B의 201은 기존 식당 번호 발급이다. 앱 합계 3 CPU, heap 768 MiB, Hikari 12개, Tomcat 스레드 120개, DB 2 CPU/768 MiB, HTTP 제한시간 12초, Hikari 취득 5초를 유지한다.
- 예열은 A/B 조회 36건, 제3매장 **장부** 접수 36건, 제3매장 선점·해제 각 6건이다. 실제 A·B에는 본 구간 전 당일 표가 없다. 측정 중 JAR·도구 해시와 입력을 고정한다.
- `client.jsonl`에 예정·호출·소켓 쓰기·본문 수신 시각과 회원·키에 대응하는 표 ID·순번을 남긴다. Tomcat 로그의 시작과 영속 커밋은 별도로 본다. `receipts.csv`, `keys.csv`, `waiting.csv`는 앱 종료 뒤의 PG 행이다. `statements.json`, `observations.jsonl`, `pool-before.json`, `pool-after.json`, `containers.log`에 SQL·차단 PID·풀·호스트와 컨테이너 표본이 남는다. 만 건이 창 안에서 전송되지 않은 회차는 입력 불충분으로 표시한다. 세 회차의 분위수를 평균내지 않는다.

`retry`는 대표 오픈 3대의 완료 DB를 회차마다 다시 복제한다. 첫 응답을 못 받은 회원 중 장부에 원래 표가 있는 **전원**이 재기동한 다른 앱으로 같은 키를 재전송한다. 동시 요청 12개 조건의 개별 지연·200·표·순번을 기록하고, 재시도 전후 장부·키·카운터가 같은지 대조한다. 첫 시험의 결과는 고치지 않는다. `report`가 회차별 표본 및 분위수·처리량 CSV와 지연·B매장 회복 후보를 포함한 로컬 비교 판정(intake-gate.json)을 생성한다. 이 판정은 실제 운영 매장을 활성화하는 작업과 분리돼 있다.


후속 로컬 단계 앞에 `python3 tools/waiting-intake-benchmark/measure.py gate`를 두면 결과를 재집계하고 후보 미달·회차 부족 시 종료코드 2를 반환한다. report는 실패 회차도 기록하도록 정상 종료한다. 보관본만으로 다시 집계할 때는 `final/`, `initial/`, `prepared-harness/`를 각각 `build/intake-lab/raw/`, `build/intake-lab/v1/`, `build/intake-lab/tools/`에 복원한다.
