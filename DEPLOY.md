# Deploy Nino API lên server Ubuntu (Docker)

Hướng dẫn này áp dụng cho môi trường **prod**: server Ubuntu chạy Docker, MySQL **đã được cài đặt và chạy sẵn**, truy cập được qua network hiện có (không phải deploy MySQL mới). Xem thêm bảng môi trường tổng quan ở [README.md](README.md#môi-trường-chạy).

## 1. Yêu cầu trước khi deploy

| Thành phần | Yêu cầu |
|---|---|
| Docker | Docker Engine + Docker Compose plugin (`docker compose version`) |
| MySQL | Đã cài sẵn, đang chạy, và **reachable** từ server Docker (đúng theo cấu hình hiện tại: `100.106.5.35:3306`, database `event_app`) |
| Redis | Container tên service `redis` cùng nằm trên network `shared-network` (app trỏ tới host `redis`, không phải IP) |
| Docker network | Network ngoài tên `shared-network` đã tồn tại (`docker-compose.yml` khai báo `external: true`, tức **không tự tạo** — phải có sẵn trước khi `docker compose up`) |
| Git | Quyền `git clone`/`git pull` repo trên server |
| Firebase | File `firebase-service-account.json` (service account để gửi FCM) |

Kiểm tra network đã tồn tại chưa:

```bash
docker network ls | grep shared-network
# Nếu chưa có:
docker network create shared-network
# Rồi đảm bảo container MySQL/Redis (nếu chạy dạng container) join network này:
docker network connect shared-network <ten_container_mysql_hoac_redis>
```

## 2. Lấy mã nguồn về server

```bash
git clone https://github.com/dautruongptit/nino_API.git nino-api
cd nino-api
# lần sau chỉ cần: git pull
```

## 3. Database schema (Flyway tự động chạy migration)

Project dùng **Flyway** (`flyway-core` + `flyway-mysql` trong `pom.xml`) — schema được tự động migrate **mỗi khi app start**, không cần chạy tay file SQL nữa. `spring.jpa.hibernate.ddl-auto=validate` — Hibernate chỉ kiểm tra entity khớp schema, không tự tạo/sửa bảng, mọi thay đổi schema đều đi qua Flyway.

- File migration: `src/main/resources/db/migration/V{n}__{mo_ta}.sql`, đặt tên đúng convention (`V<số>__<mô_tả>.sql`), Flyway tự áp dụng tuần tự theo số phiên bản.
- DB prod **đã tồn tại từ trước** (tạo thủ công tới `V22`, trước khi bật Flyway) nên `baseline-on-migrate=true` (mặc định, override qua env `FLYWAY_BASELINE` — `docker-compose.yml` đã set sẵn `FLYWAY_BASELINE=true`): Flyway "nhận" các version ≤ `baseline-version` (mặc định `22`, override qua `FLYWAY_BASELINE_VERSION`) là đã áp dụng, chỉ tự chạy các file `V23__...` trở lên.
- Với DB **rỗng** (server mới, chưa từng tạo schema thủ công), baseline không có tác dụng gì — Flyway tự chạy tuần tự từ `V1`.
- `validate-on-migrate=true`: nếu checksum của migration đã chạy bị đổi (sửa lại file cũ) hoặc migration mới lỗi, Flyway sẽ **chặn app khởi động** (fail-fast) thay vì chạy tiếp với schema sai — xem log container để biết migration nào lỗi.

> ⚠️ Không sửa lại file migration cũ đã chạy (sẽ làm sai checksum, app không start được) — luôn tạo file `V{n+1}__...sql` mới khi cần đổi schema. Chỉ cần `git pull` code mới về là đủ, **không cần** thao tác gì thêm trên MySQL trước khi deploy (xem mục 9).

## 4. Cấu hình `.env`

Tạo file `.env` ở thư mục gốc project (cùng cấp `docker-compose.yml`) — file này **không commit git** (đã có trong `.gitignore`). Copy nội dung mẫu từ `.env.dev` rồi chỉnh lại theo prod, tối thiểu các biến sau:

```bash
# Database — trỏ vào MySQL đã có sẵn trong network
DB_URL=jdbc:mysql://100.106.5.35:3306/event_app?allowPublicKeyRetrieval=true&useSSL=false
DB_USERNAME=root
DB_PASSWORD=<đổi_sang_password_thật>

# JWT — BẮT BUỘC đổi, không dùng giá trị mẫu trong repo
JWT_SECRET=<chuỗi_random_ít_nhất_32_ký_tự>

# Google OAuth client ID (verify idToken khi login Google)
GOOGLE_CLIENT_ID=<client_id>.apps.googleusercontent.com

# Redis — container "redis" trên cùng network shared-network
REDIS_HOST=redis
REDIS_PORT=6380
REDIS_PASSWORD=<password_redis>
```

> 🔒 **Bảo mật:** `JWT_SECRET` và `DB_PASSWORD` trong repo chỉ là giá trị mẫu cho dev — **phải đổi** trước khi go-live. Không commit `.env` thật lên git.

## 5. Firebase service account

Copy file `firebase-service-account.json` vào `src/main/resources/` trên server **trước khi build** (file này không có trong git, phải chuyển tay qua kênh an toàn — scp/secret manager, không gửi qua chat/email):

```bash
scp firebase-service-account.json user@server:~/nino-api/src/main/resources/
```

## 6. Build & chạy

```bash
docker compose up --build -d
```

`docker-compose.yml` sẽ:
- Build image theo `Dockerfile` (multi-stage: Maven build → JRE Alpine runtime, chạy bằng user non-root)
- Nạp biến từ `.env`, set `SPRING_PROFILES_ACTIVE=prod` → Spring Boot merge `application.yml` + `application-prod.yml` (tắt Swagger, thu hẹp actuator, giảm log level)
- Map cổng `8086:8086`, join network `shared-network`, `restart: unless-stopped` (tự khởi động lại khi Docker/server reboot)

## 7. Kiểm tra sau khi deploy

```bash
docker compose ps
docker compose logs -f app

# Health check (context-path /api/v1)
curl http://localhost:8086/api/v1/internal/health-check
```

Nếu app không kết nối được MySQL/Redis, vào trong container để kiểm tra network:

```bash
docker exec -it nino_app sh
# thử: nc -zv 100.106.5.35 3306   (MySQL)
#      nc -zv redis 6380          (Redis)
```

## 8. Dữ liệu persistent (uploads & logs)

`docker-compose.yml` mount 2 volume sau, để ảnh avatar và log **không bị mất** khi container bị xóa/rebuild (`docker compose down` hoặc `docker compose up --build`):

```yaml
services:
  app:
    volumes:
      - ./data/uploads:/app/uploads
      - ./data/logs:/app/logs
```

`./data/uploads` và `./data/logs` (đã có trong `.gitignore`, không commit) sẽ được Docker tự tạo trên host nếu chưa có. Vì container chạy bằng **user non-root** `appuser` (tạo bằng `addgroup -S`/`adduser -S` trong `Dockerfile`, UID do Alpine tự cấp), thư mục host mount vào có thể bị sai quyền ghi ở lần chạy đầu. Nếu gặp lỗi ghi file (upload avatar lỗi, hoặc log không ghi được), lấy UID/GID thật của `appuser` rồi chown lại thư mục host:

```bash
docker compose up -d          # chạy container trước
docker exec nino_app id appuser     # ví dụ trả về uid=100(appuser) gid=101(appgroup)
sudo chown -R 100:101 ./data/uploads ./data/logs
docker compose restart app
```

## 9. Cập nhật / redeploy khi có code mới

```bash
git pull
docker compose up --build -d
```

Nếu lần pull này có file migration mới (`V{n+1}__...sql`), **không cần** chạy tay lên MySQL — Flyway tự áp dụng khi app start (xem mục 3). Chỉ cần theo dõi log lúc container khởi động để chắc migration chạy thành công.

## 10. Rollback nhanh

```bash
git checkout <commit_hoặc_tag_cũ>
docker compose up --build -d
```

## 11. Troubleshooting thường gặp

| Triệu chứng | Nguyên nhân thường gặp |
|---|---|
| App crash lúc start, log `FlywayException: Validate failed... checksum mismatch` | Có ai đó sửa lại file migration cũ đã chạy rồi — không được sửa file cũ, phải tạo file `V{n+1}` mới (mục 3) |
| App crash lúc start, log `Migration ... failed` | File migration mới có lỗi SQL — sửa lại nội dung file `V{n+1}` đó (chưa từng chạy thành công thì sửa thoải mái), xoá dòng lỗi trong bảng `flyway_schema_history` nếu Flyway đã ghi nhận migration failed, rồi chạy lại |
| App crash lúc start, log `Schema-validation: missing table/column` (Hibernate) | Flyway chạy xong nhưng entity không khớp migration vừa thêm — kiểm tra lại nội dung file `V{n+1}` so với entity |
| Không kết nối được MySQL | Sai `DB_URL`/`DB_USERNAME`/`DB_PASSWORD`, MySQL chưa `bind-address` ra network, hoặc firewall/VPN (Tailscale) chặn |
| Redis connection refused | Container `redis` không cùng network `shared-network`, hoặc sai `REDIS_HOST`/`REDIS_PORT`/`REDIS_PASSWORD` |
| `docker compose up` báo network không tồn tại | `shared-network` chưa được tạo — chạy `docker network create shared-network` (mục 1) |
| Upload avatar lỗi quyền ghi file | Container chạy bằng user non-root `appuser`; nếu mount volume ngoài phải `chown` đúng UID/GID cho `appuser` |
| Port 8086 bị chiếm | Đổi mapping `ports` trong `docker-compose.yml` hoặc dừng process khác đang dùng cổng |

## 12. Checklist trước khi go-live

- [ ] `.env` không commit git, đã đổi `JWT_SECRET` và `DB_PASSWORD` khỏi giá trị mẫu
- [ ] `firebase-service-account.json` đã có ở `src/main/resources/`
- [ ] Log lúc start container không có lỗi Flyway (`FlywayException`/`Migration ... failed`) — Flyway tự chạy migration khi start, kiểm tra log để chắc đã áp dụng thành công (mục 3)
- [ ] Network `shared-network` tồn tại, MySQL reachable, container `redis` cùng network
- [ ] `curl .../internal/health-check` trả về OK
- [ ] `SWAGGER_ENABLED=false` (mặc định ở prod) — không lộ Swagger UI ra ngoài
- [ ] Thư mục `./data/uploads`, `./data/logs` trên host đã đúng quyền ghi cho `appuser` (mục 8)
