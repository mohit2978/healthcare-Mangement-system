
# Auth Service

Auth Service provides authentication and authorization capabilities for your application. It handles login (`signin`), registration (`signup`), JWT generation, and JWT validation for downstream requests coming through `gateway-service`.

Configuration is profile-based:
- `application-dev.yml`: local runtime config.
- `application-prod.yml`: imports centralized config from `admin-service` config-server.

OpenTelemetry is enabled in both profiles:
- local dev uses `compose-dev.yml` and exports to `otel-lgtm`
- prod uses `deployment/compose-prod.yml` and exports to `otel-collector`

---

### Environment Setup

* The project uses SDKMAN for managing Java and Maven versions.
* Initialize your development environment using **SDKMAN** CLI and sdkman env file [`sdkmanrc`](.sdkmanrc)

```shell
sdk env install
sdk env
```
#### Note: To install SDKMAN refer: [sdkman.io](https://sdkman.io/install)

---

## How to Run

### Prerequisites
1. Install Java 21 and Maven.
2. Install Docker Desktop and Docker Compose.
3. Install MongoDB Compass to view data.

### Steps to Run

1. **Dev mode (Admin only)**  
   Runs from `compose-dev.yml`
2. **Prod mode (Admin + Config Server)**  
   Runs from `compose-prod.yml`

Refer to the root `README` for full stack commands.

---

## APIs

### 1. **Signup API**
- **Endpoint**: `POST /api/v1/auth/signup`
- **Description**: Registers a new user with a username, email, roles, and password.
- **Request Body**:
    ```json
    {
        "username": "aman",
        "email": "aman@google.com",
        "roles": ["ROLE_ADMIN"],
        "password": "Admin@123"
    }
    ```
- **Response**: Returns success or error message based on the operation's outcome.

---

### 2. **Signin API**
- **Endpoint**: `POST /api/v1/auth/signin`
- **Description**: Authenticates a user and provides a JWT token.
- **Request Body**:
    ```json
    {
        "username": "aman",
        "password": "Admin@123"
    }
    ```
- **Response**:
    ```json
    {
        "token": "eyJhbGciOiJIUzIINiJ9.eyJzdWIiOiJhbWFuIiwicm9sZSI6I|JPTEVIUEFUSUVOVCIsImIhdCI6MTczNTI5MjA5NCwiZXhwIjoxNzM1Mzc4NDk0fQ.aZ_N7UBbJQQX_8z_4VXsmiUR_KZclossHYsCXt2_isk",
        "type": "Bearer",
        "id": "676e742b58574f384989b0af",
        "username": "aman",
        "email": "aman@google.com",
        "roles": [
            "ROLE_PATIENT"
        ]
    }
    ```

---

### 3. **Token Validation API (Used by Gateway)**
- **Endpoint**: `POST /api/v1/auth`
- **Description**: Validates JWT and performs RBAC authorization using user role + original HTTP method + original API path.
- **Behavior**:
    - Requires headers from `gateway-service`:
        - `Authorization: Bearer <token>`
        - `X-Original-Method`
        - `X-Original-Path`
    - Returns `202 Accepted` (or `200 OK`) when token is valid and role is authorized for method/path.
    - Returns `403 Forbidden` when token is valid but method/path is not allowed for the role.
    - Returns unauthorized status when token is invalid or missing.

---

## JWT Configuration

- `auth-service` is the only service that requires JWT signing/validation secret.
- In Docker Compose, JWT secret is provided via Docker secrets:
    - Secret file: `deployment/secrets/jwt.secret`
    - Mounted key: `JWT_SECRET`
    - In `prod`, loaded through optional configtree import from `/run/secrets/`.

## Observability

- Metrics, traces, and logs are exported via OTLP using the `OTLP_*` environment variables.
- `application-dev.yml` keeps local OTLP defaults for `otel-lgtm`.
- `application-prod.yml` relies on the centralized config server to switch endpoints for prod.

## RBAC Authorization Configuration

`auth-service` enforces role-based authorization from `authorization.role-policies`:
- Dev: `application-dev.yml`
- Prod: centralized config in `admin-service/src/main/resources/configs/auth-service/auth-service-prod.yml`

Current local default configuration:

1. `DOCTOR`
    - Methods: `GET`, `POST`, `PUT`
    - Paths:
        - `/api/v1/doctor/**`
        - `/api/v1/appointments`
        - `/api/v1/appointments/doctor/**`
2. `PATIENT`
    - Methods: `GET`, `POST`, `PUT`
    - Paths:
        - `/api/v1/patient/**`
        - `/api/v1/appointments/create`
        - `/api/v1/appointments`
        - `/api/v1/appointments/patient/**`
3. `ADMIN`
    - Methods: `GET`, `POST`, `PUT`, `PATCH`, `DELETE`
    - Paths:
        - `/api/**`

Notes:
- RBAC checks are applied for business APIs under `/api/v1/**`.
- Infra/auth open endpoints such as `/api/v1/auth/signin`, `/api/v1/auth/signup`, and actuator endpoints remain outside RBAC checks.

## Data Initialization

MongoDB sample data is initialized from:

- `deployment/db/init.sh`

This script seeds default roles and users used for login:

1. `ADMIN`
    - `username`: `admin`
    - `email`: `noreplyhungrycoders@gmail.com`
    - `password`: `admin123`
2. `DOCTOR`
    - `username`: `doctor`
    - `email`: `doctorhungrycoders@gmail.com`
    - `password`: `doctor123`
3. `PATIENT`
    - `username`: `patient`
    - `email`: `patienthungrycoders@gmail.com`
    - `password`: `patient123`

The same script also inserts one sample document each in `doctors` and `patients` collections.

---

## Additional Notes

- Ensure email functionality by configuring `MAIL_SERVER_USERNAME` and `MAIL_SERVER_PASSWORD` in the relevant compose file.
- All services, including the `auth-service`, are registered in Spring Boot Admin for centralized monitoring.
