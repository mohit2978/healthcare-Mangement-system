
# Admin Service

The Admin Service is a centralized monitoring and management solution for microservices in the Healthcare System. It leverages Spring Boot Admin to provide a user-friendly interface for tracking the status, health, and metrics of all registered services.

It now has profile-based behavior:
- `dev`: Spring Boot Admin Server only.
- `prod`: Spring Boot Admin Server + Spring Cloud Config Server.

It also participates in OpenTelemetry export in both local and prod stacks.

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

### Steps to Run

1. **Dev mode (Admin only)**  
   Runs from `compose-dev.yml`
2. **Prod mode (Admin + Config Server)**  
   Runs from `compose-prod.yml`

Refer to the root `README` for full stack commands.

---

## Features
- Centralized dashboard to monitor all registered microservices.
- Health check, metrics, and status monitoring.
- Customized UI title: **Healthcare Admin Panel**.
- In `prod`, serves centralized config from:
  - `src/main/resources/configs/{application}/{application}-prod.yml`
- OTLP telemetry is exported through env vars to `otel-lgtm` in local observability mode and to `otel-collector` in prod.
