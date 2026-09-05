# Nino API

Backend API quản lý sự kiện và nhắc nhở (event & reminder management), viết bằng **Spring Boot 3.3 / Java 17**.

## Tính năng chính

- **Xác thực**: đăng ký/đăng nhập bằng email-password (JWT access + refresh token) và đăng nhập bằng Google (verify idToken).
- **Quản lý sự kiện**: tạo/sửa/xóa sự kiện, danh mục sự kiện, người tham gia, nhắc nhở lặp lại theo lịch âm/dương.
- **Người thân (Relative)**: quản lý danh sách người thân, nhóm liên quan tới sự kiện.
- **Thông báo & Push**: gửi notification qua Firebase Cloud Messaging (FCM), quản lý device token.
- **Người dùng**: hồ sơ, cài đặt, lịch sử đăng nhập.
- **Lịch âm**: chuyển đổi dương lịch ↔ âm lịch.
- **Ghi log request**: log bất đồng bộ mọi request (ẩn dữ liệu nhạy cảm) phục vụ audit.
- **Swagger/OpenAPI**: tài liệu API tự sinh tại `/swagger-ui.html`.

## Kiến trúc & thư mục

```
src/main/java/com/app/nino/
├── config/         # Cấu hình Spring (Security, CORS, Redis, JWT, Firebase, OpenAPI, i18n...)
├── controller/      # REST controller (Auth, Event, User, Relative, Notification, Device, Health, Home)
├── exception/       # Exception tùy biến + GlobalExceptionHandler (trả lỗi JSON thống nhất)
├── filter/          # Servlet filter (request logging)
├── model/
│   ├── dto/request/   # DTO nhận vào từ client
│   ├── dto/response/  # DTO trả về client
│   ├── entity/        # JPA entity
│   └── converter/      # JPA AttributeConverter
├── repository/      # Spring Data JPA repository
├── scheduler/       # Job chạy định kỳ (@Scheduled) — nhắc nhở, tính lại lịch lặp
├── security/        # JWT filter/provider, UserDetails, xử lý lỗi auth
├── service/         # Business logic
└── util/            # Tiện ích (parse device, mask dữ liệu nhạy cảm, đổi lịch âm)
```

- **Base path**: mọi endpoint có prefix `/api/v1` (`server.servlet.context-path`).
- **Bảo mật**: Spring Security + JWT, stateless (không session). Endpoint public: `/auth/register`, `/auth/login`, `/auth/refresh`, `/auth/google`, health check, Swagger UI.
- **DB**: MySQL (qua Spring Data JPA/Hibernate).
- **Cache/Session hỗ trợ**: Redis (Lettuce).

## Môi trường chạy

Dự án có **2 môi trường**, mỗi môi trường đi kèm 1 cặp file cấu hình cố định — sửa cấu hình phải đúng cặp file tương ứng:

| Môi trường | Nơi chạy | File Spring profile | File biến môi trường | Docker compose |
|---|---|---|---|---|
| **dev** | Máy cá nhân (localhost) | `application.yml` (mặc định, không hậu tố) | `.env.dev` | _(chưa có file compose riêng cho dev)_ |
| **prod** | Server Ubuntu (Docker) | `application-prod.yml` (override lên trên `application.yml`) | `.env` | `docker-compose.yml` |

`application-prod.yml` chỉ chứa phần **khác** so với `application.yml` (tắt Swagger mặc định, tắt `show-sql`, thu hẹp actuator, giảm log level) — mọi giá trị khác vẫn kế thừa từ file mặc định.

Cổng chạy thống nhất: **8086** (Dockerfile `EXPOSE 8086`, healthcheck và `docker-compose` đều map `8086:8086`, `SERVER_PORT=8086` trong cả `.env` và `.env.dev`).

### Chạy dev (local, không cần Docker)

```bash
./mvnw spring-boot:run
```

Spring Boot tự đọc `.env.dev` (qua `DotenvConfig`) và dùng `application.yml` mặc định. Swagger UI: `http://localhost:8086/api/v1/swagger-ui.html`.

### Chạy prod (Docker, trên server Ubuntu)

```bash
docker compose up --build -d
```

Dùng `Dockerfile` (multi-stage: build bằng Maven → chạy bằng JRE Alpine, user non-root), nạp biến môi trường từ `.env`, kích hoạt `SPRING_PROFILES_ACTIVE=prod` → Spring Boot merge `application.yml` + `application-prod.yml`.

## Cấu hình cần chuẩn bị trước khi chạy

- `.env` / `.env.dev`: DB (`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`), `JWT_SECRET`, `GOOGLE_CLIENT_ID`, Redis (`REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD`)...
- `src/main/resources/firebase-service-account.json`: service account Firebase để gửi FCM push notification (**không commit** — đã có trong `.gitignore`).

## CORS

Cấu hình trong `AppConfig.corsConfigurer()`. Hiện đang cho phép mọi origin (`"*"`) kèm danh sách domain cụ thể đã khai báo sẵn (`nino.thongtinchinhhieu.site`, `nino-api.thongtinchinhhieu.site`) để tiện siết lại sau này bằng cách bỏ `"*"`.

## Build & test

```bash
./mvnw clean compile      # build
./mvnw test                # chạy unit test
./mvnw clean package       # đóng gói jar (target/app-nino.jar)
```
