
# API Gateway Service

The API Gateway Service serves as the central entry point for all client requests. It handles routing, delegated authentication checks via `auth-service`, and fallback mechanisms for microservices. The service also integrates circuit breakers to improve resiliency and fault tolerance.

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
1. Install **Java 21** and **Maven**.
2. Install **Docker Desktop** and **Docker Compose**.

### Steps to Run

1. **Dev mode (Admin only)**  
   Runs from `compose-dev.yml`
2. **Prod mode (Admin + Config Server)**  
   Runs from `compose-prod.yml`

Refer to the root `README` for full stack commands.

---

## Features

1. **Routing**: Handles routing of requests to microservices (e.g., Auth, Doctor, Patient, Appointment).
2. **Authentication Delegation**: For secured endpoints, forwards request headers to `auth-service` for token validation.
3. **Circuit Breaker**: Implements fallback logic using Resilience4j.
4. **Fallback**: Provides fallback routes for service failures to ensure graceful degradation.

---

## Gateway Configuration

### Routing

The API Gateway routes incoming requests to the respective microservices:

| Service             | Path                        | Fallback Path            | Target URI                     |
|---------------------|-----------------------------|--------------------------|--------------------------------|
| **Doctor Service**  | `/api/v1/doctor/**`         | `/fallback/doctor`       | `http://doctor-service:8080`  |
| **Patient Service** | `/api/v1/patient/**`        | `/fallback/patient`      | `http://patient-service:8080` |
| **Appointment Service** | `/api/v1/appointments/**` | `/fallback/appointment`  | `http://appointment-service:8080` |
| **Auth Service**    | `/api/v1/auth/**`           | `/fallback/auth`         | `http://auth-service:8080`    |

### Open API Endpoints

The following endpoints are publicly accessible without authentication:
- `/api/v1/auth/signup`
- `/api/v1/auth/signin`

---

## Authentication and JWT Validation

The API Gateway does not validate JWT locally anymore.
- Requests targeting auth endpoints (`/api/v1/auth/**`) are forwarded directly.
- For other protected routes, the global gateway filter calls `POST /api/v1/auth` on `auth-service`.
- Gateway forwards incoming headers (including `Authorization`) to `auth-service`.
- If `auth-service` returns `202 Accepted` or `200 OK`, request is routed to target service.
- Otherwise gateway returns the same error status from `auth-service`.

---

## Circuit Breaker Configuration

Resilience4j is used to handle failures:
- Each route has a specific circuit breaker (e.g., `doctorCircuitBreaker`).
- Fallback URIs are defined for degraded service responses.

---

## APIs

### Fallback APIs

In case of service failure, fallback responses are provided:

#### 1. **Doctor Service Fallback**
- **Endpoint**: `GET /fallback/doctor`
- **Response**: `Doctor Service is currently unavailable. Please try again later.`

#### 2. **Patient Service Fallback**
- **Endpoint**: `GET /fallback/patient`
- **Response**: `Patient Service is currently unavailable. Please try again later.`

#### 3. **Appointment Service Fallback**
- **Endpoint**: `GET /fallback/appointment`
- **Response**: `Appointment Service is currently unavailable. Please try again later.`

#### 4. **Auth Service Fallback**
- **Endpoint**: `GET /fallback/auth`
- **Response**: `Auth Service is currently unavailable. Please try again later.`

---

## Additional Notes

1. **Environment Variables**:
    - `AUTH_SERVICE_BASE_URL`: Base URL of `auth-service`.
    - `AUTH_SERVICE_URI`: Auth URI used for direct auth routes and token validation checks (default `/api/v1/auth`).
    - OTLP env vars are injected by the compose files for traces, metrics, and logs.

2. **Dependencies**:
    - `spring-cloud-starter-gateway`: Provides routing and gateway features.
    - `spring-cloud-starter-circuitbreaker-reactor-resilience4j`: Handles circuit breaker and fallback logic.

3. **Docker Integration**:
    - Ensure all microservices (Auth, Doctor, Patient, Appointment) are running for full functionality.
    - `gateway-service` does not require `JWT_SECRET`; it depends on `auth-service` for JWT validation.
    - Prod runtime route/auth config is served from `admin-service` centralized config.
