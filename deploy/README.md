# EC2 배포

EC2 한 대에서 백엔드·PostgreSQL 16·Redis 7을 Docker Compose로 띄운다. 프런트(Vercel)는 `/api` 요청을 이 서버의 80번 포트로 넘긴다.

> 로그인이 없다. `X-Member-Id` 헤더 값만으로 회원을 구분하므로 공개 주소에서는 누구나 다른 회원으로 요청할 수 있다. 시연용 데이터만 넣는다.

## 1. EC2 준비

| 항목 | 값 |
| --- | --- |
| AMI | Ubuntu 24.04 LTS |
| 유형 | 메모리 4GB 이상(예: `t3.medium`). 2GB 장비는 `.env`의 `JAVA_OPTS`로 힙을 줄인다 |
| 디스크 | gp3 20GB |
| 보안 그룹 | 22(SSH): 내 IP만, 80(HTTP): 0.0.0.0/0. 5432는 열지 않는다 |
| 탄력적 IP | 할당해서 연결. Vercel 설정에 이 주소를 쓰므로 바뀌면 안 된다 |

기본 실행 설정은 개점 부하 측정에서 목표를 통과한 값이다(힙 768MiB, 코드 캐시·직접 메모리 제한, Tomcat 연결 12,000·대기열 4,096, DB 연결 12). 측정에서는 1만 명이 1초에 신청해도 접수 응답 p95 0.5초 이내, 식당 번호 p95 30초 이내였다. 앱 3대로 나눈 측정에서 앱과 PostgreSQL의 메모리 합계는 최고 약 1.8GB였다. 이 compose로 3,000명이 1초에 신청했을 때는 앱 약 0.95GB, PostgreSQL 약 0.1GB, Redis 약 16MB였다. 앱 컨테이너는 2GB로 제한한다. 측정은 앱에 CPU 3개, PostgreSQL에 2개를 줬다. vCPU가 적은 장비에서는 접수 지연이 늘 수 있으니 실제 장비에서 다시 확인한다. [Redis 제품 경로](../docs/redis-product.md) · [측정 기록](../work-log/2026-11-23-redis-pr-fixes.md)

```bash
# Docker 설치
sudo apt-get update && sudo apt-get install -y docker.io docker-compose-v2 git
docker compose version   # 2.20 이상이어야 한다 (Redis를 선택 의존성으로 두는 required 옵션)
sudo usermod -aG docker $USER   # 다시 접속하면 sudo 없이 docker 사용

# 메모리가 4GB보다 작다면 스왑 2GB 추가
sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile && sudo mkswap /swapfile && sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
```

## 2. 설정과 실행

```bash
git clone https://github.com/Techeer-2026-2-E/catchtable-backend.git
cd catchtable-backend/deploy
cp .env.example .env
nano .env    # DB_PASSWORD를 강한 값으로, Toss 키를 받았으면 TOSS_SECRET_KEY도
```

### 데이터 넣기 (로컬 DB 그대로 옮기기)

공공데이터 매장·목 계정·데모 매장이 들어 있는 로컬 DB를 덤프해서 옮긴다. CSV 적재를 서버에서 다시 돌리지 않아도 된다.

```bash
# 로컬(PC)에서
docker exec catchtable-postgres pg_dump -U catchtable -d catchtable -Fc --no-owner --no-acl > catchtable.dump
scp -i 키.pem catchtable.dump ubuntu@<탄력적IP>:~/
```

```bash
# EC2에서 (deploy 폴더)
docker compose up -d postgres
docker compose exec -T postgres pg_restore -U catchtable -d catchtable --no-owner --no-acl < ~/catchtable.dump
docker compose up -d --build app
```

덤프의 스키마가 최신보다 낮으면 앱이 시작할 때 Flyway가 남은 마이그레이션을 적용한다. 빈 DB로 시작하려면 복원을 건너뛰고 `docker compose up -d --build`만 실행한다.

### 확인

```bash
curl http://localhost/actuator/health          # {"status":"UP"}
docker compose logs -f app                      # 로그
```

PC에서 `http://<탄력적IP>/actuator/health`도 열리는지 확인한다. `DEGRADED`는 Redis에 연결할 수 없다는 뜻이다. 앱은 PostgreSQL만으로 계속 동작하므로 `docker compose logs redis`를 확인한다.

### 웨이팅 장부 접수 켜기

Redis와 워커는 함께 뜨지만, 매장을 전환하기 전까지는 기존 웨이팅 흐름 그대로다. 전환한 매장은 신청에 `Idempotency-Key` 헤더가 필요하고 202 대기표를 돌려준다. 그래서 프런트가 [대기표 흐름](../docs/frontend/waiting-receipt-lifecycle.md)을 지원한 뒤에 켠다. 오늘 이미 기존 방식으로 웨이팅을 받은 매장은 다음 날부터 켤 수 있다.

```bash
# 매장 1을 오늘부터 미완료 1,000건 한도로 전환 (ENABLED / ALREADY_ENABLED / BLOCKED)
docker compose exec -T postgres psql -U catchtable -d catchtable -c "SELECT enable_waiting_intake(1, current_date, 1000)"
```

되돌리기는 [Redis 제품 경로](../docs/redis-product.md#접수와-복구)의 `retire_waiting_intake`를 따른다.

## 3. 업데이트·설정 변경

```bash
cd catchtable-backend && git pull
cd deploy && docker compose up -d --build app
```

`.env`만 바꿨다면 `docker compose up -d app`으로 다시 띄운다. DB 데이터는 `postgres-data` 볼륨에 남는다. `docker compose down -v`는 DB까지 지우므로 쓰지 않는다.

## 4. 백업

```bash
docker compose exec -T postgres pg_dump -U catchtable -d catchtable -Fc > ~/backup-$(date +%F).dump
```
