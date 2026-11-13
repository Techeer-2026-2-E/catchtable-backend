# EC2 배포

EC2 한 대에서 백엔드와 PostgreSQL 16을 Docker Compose로 띄운다. 프런트(Vercel)는 `/api` 요청을 이 서버의 80번 포트로 넘긴다.

> 로그인이 없다. `X-Member-Id` 헤더 값만으로 회원을 구분하므로 공개 주소에서는 누구나 다른 회원으로 요청할 수 있다. 시연용 데이터만 넣는다.

## 1. EC2 준비

| 항목 | 값 |
| --- | --- |
| AMI | Ubuntu 24.04 LTS |
| 유형 | `t3.small`(2GB) 권장. `t3.micro`(1GB)는 아래 스왑을 꼭 추가 |
| 디스크 | gp3 20GB |
| 보안 그룹 | 22(SSH): 내 IP만, 80(HTTP): 0.0.0.0/0. 5432는 열지 않는다 |
| 탄력적 IP | 할당해서 연결. Vercel 설정에 이 주소를 쓰므로 바뀌면 안 된다 |

백엔드 컨테이너는 실행 중 약 500MB, PostgreSQL은 약 60MB를 쓴다.

```bash
# Docker 설치
sudo apt-get update && sudo apt-get install -y docker.io docker-compose-v2 git
sudo usermod -aG docker $USER   # 다시 접속하면 sudo 없이 docker 사용

# t3.micro라면 스왑 2GB 추가
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

PC에서 `http://<탄력적IP>/actuator/health`도 열리는지 확인한다.

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
