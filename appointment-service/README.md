
# Appointment Service

The **Appointment Service** is responsible for managing appointment creation, retrieval, updates, and communication with other microservices. It acts as a Kafka producer to publish events that are consumed by the **Notification Service**.

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
3. Ensure that the **Kafka** and **MongoDB** services are up and running as part of the Docker Compose setup.
4. Follow the instructions in the root **README.md** file for initial setup.

### Run with Docker Compose

1. **Dev mode (Admin only)**  
   Runs from `compose-dev.yml`
2. **Prod mode (Admin + Config Server)**  
   Runs from `compose-prod.yml`

Refer to the root `README` for full stack commands.

---

## APIs

### 1. **Create Appointment**
- **Endpoint**: `POST /api/v1/appointments/create`
- **Description**: Creates a new appointment.
- **Request Body**:
    ```json
    {
        "doctorId": "123e4567-e89b-12d3-a456-426614174000",
        "patientId": "123e4567-e89b-12d3-a456-426614174001",
        "appointmentTime": "2025-01-20T10:00:00",
        "doctorComments": "Initial consultation",
        "notes": "Bring previous reports"
    }
    ```
- **Response**: Returns the appointment ID or an error message.

---

### 2. **Get Appointments by Doctor**
- **Endpoint**: `GET /api/v1/appointments/doctor/{doctorId}`
- **Description**: Retrieves all appointments for a specific doctor.

---

### 3. **Get Appointments by Patient**
- **Endpoint**: `GET /api/v1/appointments/patient/{patientId}`
- **Description**: Retrieves all appointments for a specific patient.

---

### 4. **Get All Appointments**
- **Endpoint**: `GET /api/v1/appointments/all`
- **Description**: Retrieves all appointments in the system.

---

### 5. **Update Appointment**
- **Endpoint**: `PUT /api/v1/appointments`
- **Description**: Updates an existing appointment.
- **Request Body**:
    ```json
    {
        "id": "123e4567-e89b-12d3-a456-426614174002",
        "doctorId": "123e4567-e89b-12d3-a456-426614174000",
        "patientId": "123e4567-e89b-12d3-a456-426614174001",
        "appointmentTime": "2025-01-20T15:00:00",
        "doctorComments": "Follow-up visit",
        "notes": "Check blood pressure"
    }
    ```
- **Response**: Returns the updated appointment ID or an error message.

---

## Kafka Integration

The **Appointment Service** acts as a Kafka producer to publish appointment-related events. The events are consumed by the **Notification Service** to notify users.

- **Topic Name**: Configured in the `application.yml` file as `spring.kafka.topic.name`.
- **Event Publishing**: Upon successful creation or update of an appointment, an event is published to Kafka.

---

## Environment Variables

- `DOCTOR_SERVICE_URL`: The base URL for the **Doctor Service** (e.g., `http://doctor-service:8080/api/v1/doctor`).
- `PATIENT_SERVICE_URL`: The base URL for the **Patient Service** (e.g., `http://patient-service:8080/api/v1/patient`).
- `SPRING_KAFKA_TOPIC_NAME`: Kafka topic name for publishing events.
- `MONGO_URI`: MongoDB connection string.

---

## Data Initialization

- **MongoDB**: The service uses MongoDB to store appointment details.
- **Collections**:
    - `appointments`: Stores appointment records.

---

## Additional Notes

1. **Monitoring**:
    - The service is registered with **Spring Boot Admin** for centralized monitoring.
2. **Prod centralized config**:
   - In prod mode, runtime configuration is loaded from `admin-service` config-server.
3. **Prod observability**:
   - In prod mode, metrics, traces, and logs are exported to the central OTLP collector stack.
