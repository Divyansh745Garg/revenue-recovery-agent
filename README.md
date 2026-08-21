# Distributed E-Commerce Backend

> A production-inspired distributed e-commerce platform built with **Java 21**, **Spring Boot 3**, **Docker**, **RabbitMQ**, **Redis**, and **PostgreSQL**. The project demonstrates enterprise backend architecture through microservices, Saga-based distributed transactions, stateless authentication, event-driven communication, and scalable system design.

> **📚 Architecture & Engineering Deep Dive**  
> For a detailed breakdown of the system design—including the Transactional Outbox pattern, Saga rollbacks, Redis-backed idempotency, and the "Castle & Moat" security model—see **[Architecture.md](Architecture.md)**.

---

# System Architecture

<p align="center">
    <img src="./image/HLD 2.png" width="1100" alt="System Architecture">
</p>

The platform is designed around **loose coupling**, **clear service ownership**, and **fault isolation**. Every microservice owns a single business capability and communicates with other services exclusively through REST APIs or asynchronous events.

---

# Technology Stack

| Category | Technology |
|-----------|------------|
| **Language** | Java 21 |
| **Framework** | Spring Boot 3 |
| **API Gateway** | Spring Cloud Gateway |
| **Database** | PostgreSQL |
| **Cache** | Redis |
| **Messaging** | RabbitMQ |
| **Security** | JWT |
| **Containerization** | Docker & Docker Compose |
| **Concurrency** | Java 21 Virtual Threads |

---

# Microservices

| Service | Port | Responsibility |
|----------|------|----------------|
| **API Gateway** | `8080` | Routing, authentication, authorization, and rate limiting |
| **Auth Service** | `8081` | User registration, login, JWT issuance |
| **Product Service** | `8082` | Product catalog and inventory management |
| **Order Service** | `8083` | Checkout workflow, idempotency, and order lifecycle |
| **Notification Service** | `8084` | Email and notification processing |
| **Payment Service** | `8085` | Payment processing and Saga event publishing |

### Observability Infrastructure
| Tool | Port | Purpose |
|------|------|---------|
| **Grafana** | `3001` | Visualization dashboards for custom business metrics |
| **Prometheus**| `9090` | Time-series database scraping Spring Actuator metrics |
| **Zipkin** | `9411` | Distributed tracing across microservice boundaries |

---



# Running the Project

## Prerequisites

- Java 21
- Maven
- Docker Desktop
- Docker Compose

---

## 1. Clone the Repository

```bash
git clone https://github.com/Divyansh745Garg/distributed-commerce.git
cd distributed-ecommerce
```

---

## 2. Build the Microservices

```bash
./mvnw clean package -DskipTests
```

---

## 3. Start the Backend Infrastructure & Services

```bash
docker compose up --build -d
```

> **Note:** Wait approximately **30–45 seconds** for PostgreSQL, Redis, RabbitMQ, Prometheus, Grafana, and Zipkin to initialize before the 6 Spring Boot applications become fully available.

---

## 4. Run the Frontend

If your repository includes a frontend application, navigate into the frontend directory, install the dependencies, and start the development server.

```bash
cd frontend
npm install
npm run dev
```

> Ensure the frontend is configured to use the API Gateway as its backend:
>
> `http://localhost:8080`

---

## 5. Verify Backend Services

```bash
docker compose ps
```

---

# Observability & Monitoring

The platform includes a complete observability stack for monitoring application health, business metrics, and distributed request tracing.

## 1. Prometheus

**URL**

```text
http://localhost:9090/targets
```
<p align="center">
    <img src="./image/Prometheus.png" width="1100" alt="System Architecture">
</p>


### Verify Target Health

1. Open **Status → Targets**.
2. Confirm that all microservices report an **UP** status:

- API Gateway
- Auth Service
- Product Service
- Order Service
- Notification Service
- Payment Service

If any service reports **`connection refused`** or **`no such host`**, verify that all containers are running:

```bash
docker compose ps
```

---

## 2. Grafana

**URL**

```text
http://localhost:3001
```

### Initial Configuration

1. Log in using the default credentials:

```text
Username: admin
Password: admin
```

2. Navigate to:

```text
Connections → Data Sources → Add data source
```

3. Choose **Prometheus**.

4. Configure the datasource URL as either:

```text
http://prometheus:9090
```

or

```text
http://localhost:9090
```
<p align="center">
    <img src="./image/Grafana1.png" width="1100" alt="System Architecture">
</p>


5. Click **Save & Test**.

You can now build dashboards using both JVM metrics and custom business metrics such as:

```text
business_payments_declined_total
```

---

## 3. Zipkin Distributed Tracing

**URL**

```text
http://localhost:9411
```

Open the Zipkin UI after placing orders through the API Gateway.

Each request generates a distributed trace that visualizes latency and execution flow across services including:

- API Gateway
- Auth Service
- Order Service
- Payment Service
- Notification Service

This makes it easy to inspect request propagation accross the different services, identify bottlenecks, and debug failures.

---

# Testing the Checkout Flow

## 1. Authenticate

```http
POST /api/v1/auth/login
```

Retrieve the generated JWT from the response.

---

## 2. Create an Order

```http
POST /api/v1/orders
Authorization: Bearer <JWT>
Idempotency-Key: order-001
```

Request Body

```json
{
  "userId": "user-123",
  "items": [
    {
      "productId": "<PRODUCT_UUID>",
      "quantity": 2
    }
  ]
}
```

---

## 3. Verify Idempotency

Repeat the **exact same request** using the same `Idempotency-Key`.

The Order Service immediately returns the cached response instead of executing the checkout workflow again, demonstrating idempotent request handling.

---

## 4. Observe the Event Flow

Watch the asynchronous Saga choreography by tailing the Docker logs.

```bash
docker compose logs -f
```

---

# Project Structure

```text
distributed-ecommerce
│
├── api-gateway
├── auth-service
├── product-service
├── order-service
├── payment-service
├── notification-service
├── frontend
├── monitoring
│
├── docker-compose.yml
├── pom.xml
├── README.md
└── ARCHITECTURE.md
```

---

# Future Improvements

- OpenTelemetry distributed tracing
- Resilience4j circuit breakers
- Kafka event streaming
- Kubernetes deployment manifests
- GitHub Actions CI/CD pipeline
- Cache-aside strategy for product catalog

---

## Documentation

| Document | Description |
|----------|-------------|
| **README.md** | Project overview and setup instructions |
| **ARCHITECTURE.md** | Deep dive into architecture, distributed transactions, Saga choreography, Transactional Outbox, security model, and engineering decisions |

---

## License

This project is intended for educational and portfolio purposes to demonstrate modern distributed systems architecture using Spring Boot microservices. Any improvements and suggestions would be great to hear and improve 