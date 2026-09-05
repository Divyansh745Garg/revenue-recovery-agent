# Recoup — AI Revenue Recovery Agent

![Java 21](https://img.shields.io/badge/Java-21-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white)
![Spring Boot 3](https://img.shields.io/badge/Spring_Boot-3-6DB33F?style=for-the-badge&logo=spring-boot&logoColor=white)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-316192?style=for-the-badge&logo=postgresql&logoColor=white)
![Redis](https://img.shields.io/badge/Redis-DC382D?style=for-the-badge&logo=redis&logoColor=white)
![RabbitMQ](https://img.shields.io/badge/RabbitMQ-FF6600?style=for-the-badge&logo=rabbitmq&logoColor=white)
![Docker](https://img.shields.io/badge/Docker-2496ED?style=for-the-badge&logo=docker&logoColor=white)
![Gemini 3.7 Flash](https://img.shields.io/badge/Gemini_3.7_Flash-8E75B2?style=for-the-badge&logo=googlegemini&logoColor=white)

**A distributed e-commerce platform with an AI-powered revenue recovery agent built in.**

Recoup handles the full commerce lifecycle — auth, catalog, orders, payments, notifications
— and closes the loop on the one failure mode most platforms leave to chance: a payment
that declines for a reason that might, with the right response, still be recoverable.

Built for **Razorpay's AI Buildathon** — Track 3: AI Revenue Recovery.

---

## Table of Contents

- [Overview](#overview)
- [Architecture](#architecture)
- [Microservices](#microservices)
- [Failure classification](#failure-classification)
- [Where idempotency fits](#where-idempotency-fits)
- [Data flow: entry, validation, exit](#data-flow-entry-validation-exit)
- [Demo scenarios](#demo-scenarios)
- [Why payment processing stays deterministic](#why-payment-processing-stays-deterministic)
- [Observability & monitoring](#observability--monitoring)
- [Simulation & System Constraints](#simulation--system-constraints)
- [Challenges we hit](#challenges-we-hit)
- [Tech stack](#tech-stack)
- [Running it](#running-it)
- [Roadmap](#roadmap)

---

## Overview

Most commerce platforms treat every declined payment the same way: retry it, or don't.
Recoup treats declines as three distinct categories, because they genuinely are:

- **Technical** failures (timeouts, 5xx errors) — nothing was refused, just retry.
- **Terminal** failures (expired cards, hard fraud blocks) — no retry changes the outcome.
- **Soft or ambiguous** failures (insufficient funds, a vague "do not honor," a soft risk
  hold) — the right response genuinely depends on weighing several weak signals together:
  this customer's own retry history, order value, attempt count, time since decline.

The platform's Saga choreography, transactional outbox, and idempotency guarantees handle
the first two categories deterministically. For the third, Recoup runs a bounded AI agent
that decides — from a fixed, auditable menu of actions — what's worth trying, and gates
anything customer-facing behind human approval before it happens.

*(Note: A detailed architectural breakdown and failure scenario mapping can be found in the `notes/` directory).*
## Architecture

```
┌──────────────────────┐
│  Client / Storefront │
└──────────┬───────────┘
           │
           ▼
┌──────────────────────┐
│     API Gateway      │
└──────────┬───────────┘
           │
     ┌─────┼─────┬──────────┐
     ▼     ▼     ▼          ▼
  ┌─────┐┌──────┐┌───────┐┌─────────┐
  │Auth ││Product││ Order││ Payment │
  │Svc  ││ Svc  ││  Svc  ││   Svc   │
  └─────┘└──────┘└───┬───┘└────┬────┘
                     └─────┬───┘
                           ▼
                ┌─────────────────────┐
                │  Failure Classifier │
                └──────────┬──────────┘
                           │
              ┌────────────┴────────────┐
              │                         │
              ▼                         ▼
     ┌────────────────┐       ┌────────────────┐
     │ Technical /    │       │ Soft /         │
     │ Terminal       │       │ Ambiguous      │
     └───────┬────────┘       └───────┬────────┘
             │                        │
             ▼                        ▼
     ┌────────────────┐       ┌────────────────┐
     │ Auto-Retry /   │       │    RabbitMQ    │
     │ Notify & Stop  │       │   Event Bus    │
     └───────┬────────┘       └───────┬────────┘
             │                        │
             │               ┌────────┴────────┐
             │               ▼                 ▼
             │        ┌──────────────┐  ┌──────────────┐
             │        │ Notification │  │ Recovery     │
             │        │   Service    │  │ Agent        │
             │        └──────────────┘  └──────┬───────┘
             │                                  │
             │                                  ▼
             │                         ┌────────────────┐
             │                         │ Signal Bundle  │
             │                         └───────┬────────┘
             │                                 │
             │                                 ▼
             │                         ┌────────────────┐
             │                         │ Gemini 3.7     │
             │                         │ Flash LLM      │
             │                         └───────┬────────┘
             │                                 │
             │                    ┌────────────┴────────────┐
             │                    ▼                         ▼
             │           ┌────────────────┐        ┌────────────────┐
             │           │ Auto-Safe      │        │ Approval Queue │
             │           │ RETRY / STOP   │        │  Human Gate    │
             │           └───────┬────────┘        └───────┬────────┘
             │                   │                         │
             │                   └──────────┬──────────────┘
             │                              ▼
             │                  ┌────────────────────────┐
             │                  │ Execute Order/Payment  │
             │                  │ APIs + Idempotency Key │
             │                  └───────────┬────────────┘
             │                              │
             └──────────────────────────────┤
                                            ▼
                                  ┌────────────────────┐
                                  │ Audit Log / Outbox │
                                  └────────────────────┘


 Shared Infrastructure
 ─────────────────────────────────────────────────────────
 Redis          → Idempotency + Cache
 PostgreSQL     → Durable State


 Observability
 ─────────────────────────────────────────────────────────
 Prometheus     → Metrics
 Grafana        → Dashboards
 Zipkin         → Distributed Tracing
```

The synchronous checkout path (gateway → auth → product → order → payment → notification)
never touches the LLM. The recovery agent lives entirely in a separate, asynchronous
consumer path reacting to events on RabbitMQ — no model call ever sits in the hot path of
a live request.

## Microservices

| Service | Port | Responsibility |
|---|---|---|
| API Gateway | `8080` | Routing, authentication, authorization, rate limiting |
| Auth Service | `8081` | User registration, login, JWT issuance |
| Product Service | `8082` | Product catalog and inventory management |
| Order Service | `8083` | Checkout workflow, idempotency, order lifecycle, Saga choreography |
| Payment Service | `8085` | Payment processing, decline classification, outbox event publishing |
| Notification Service | `8084` | Email and notification processing |
| Recovery Agent Service | `8086` | Signal bundling, LLM-based decisioning, orchestration, approval queue, audit log, merchant console |

## Failure classification

| Bucket | Examples | Handled by | LLM involved? |
|---|---|---|---|
| **Technical** | Gateway timeout, 5xx, MQ blip, issuer timeout, rate limiting (429) | Auto-retry | No |
| **Terminal** | Expired card, stolen/blocked card, hard fraud block, closed account | Notify & stop | No |
| **Soft / ambiguous** | Insufficient funds, generic "do not honor," soft risk hold, OTP/3DS failure, velocity limit | Recovery Agent | **Yes** |

A deterministic pre-filter makes this routing decision before anything reaches an LLM —
technical and terminal declines never generate a model call at all.

## Where idempotency fits

Idempotency is not a fourth bucket — the three buckets above classify *why* a payment
failed; idempotency is a safety property governing *how any action gets executed*,
regardless of which bucket triggered it.

Every action the recovery agent executes — a retry, a notification — goes through the
same idempotency-key mechanism used by the checkout flow itself. A duplicate execution
attempt on the same order, from any source, is detected and blocked rather than
re-executed. This guarantee sits underneath every action the agent can take.

## Data flow: entry, validation, exit


```text
┌──────────────────────────────────────────────────────────────────────┐
│ 1. ENTRY                                                             │
│                                                                      │
│ payment.failed event                                                 │
│        │                                                             │
│        └── declineCode + order/payment identifiers                   │
│                         │                                            │
│                         ▼                                            │
│                       RabbitMQ                                       │
└─────────────────────────┬────────────────────────────────────────────┘
                          │
                          ▼
┌──────────────────────────────────────────────────────────────────────┐
│ 2. ROUTING                                                           │
│                                                                      │
│ Java Failure Classifier                                              │
│        │                                                             │
│        ├── Technical / Terminal ──────► Bypass Recovery Agent        │
│        │                               (Retry / Notify / Stop)       │
│        │                                                             │
│        └── Soft / Ambiguous ─────────► Recovery Agent                │
└──────────────────────────────────────┬───────────────────────────────┘
                                       │
                                       ▼
┌──────────────────────────────────────────────────────────────────────┐
│ 3. SIGNAL BUNDLE CONSTRUCTION                                        │
│                                                                      │
│ Recovery Agent queries PostgreSQL / relational data                  │
│                                                                      │
│   • Order value                                                      │
│   • Previous recovery history                                        │
│   • Attempt count                                                    │
│   • Temporal / contextual data                                       │
│                                                                      │
│   Raw application logs are NEVER sent to the LLM.                    │
│   Only structured, relevant signals are included.                    │
└──────────────────────────────────────┬───────────────────────────────┘
                                       │
                                       ▼
┌──────────────────────────────────────────────────────────────────────┐
│ 4. MODEL DECISION                                                    │
│                                                                      │
│              Signal Bundle                                           │
│                    │                                                 │
│                    ▼                                                 │
│          ┌─────────────────────┐                                     │
│          │ Gemini 3.7 Flash    │                                     │
│          │                     │                                     │
│          │ Strict System Prompt│                                     │
│          │ + JSON Schema       │                                     │
│          └──────────┬──────────┘                                     │
│                     │                                                │
│                     ▼                                                │
│              Structured Action                                       │
└─────────────────────┬────────────────────────────────────────────────┘
                      │
                      ▼
┌──────────────────────────────────────────────────────────────────────┐
│ 5. DETERMINISTIC VALIDATION                                          │
│                                                                      │
│ Application code validates the model output.                         │
│                                                                      │
│   ✓ Action must belong to a closed enum                              │
│     RETRY_SILENT | ESCALATE_HUMAN | STOP | ...                       │
│                                                                      │
│   ✓ Maximum 3 attempts per order                                     │
│     Enforced independently by application code                       │
│                                                                      │
│   ✓ requires_human_approval                                          │
│     Derived from the action type in code                             │
│     Never trusted directly from the model                            │
└─────────────────────┬────────────────────────────────────────────────┘
                      │
                      ▼
              ┌───────────────┐
              │ Approval      │
              │ Required?     │
              └───────┬───────┘
                      │
             ┌────────┴────────┐
             │                 │
            NO                YES
             │                 │
             ▼                 ▼
┌──────────────────────┐  ┌──────────────────────┐
│ 6A. IMMEDIATE        │  │ 6B. HUMAN APPROVAL   │
│     EXECUTION        │  │                      │
│                      │  │ Postgres-backed      │
│ Execute action       │  │ Approval Queue       │
│ immediately          │  │                      │
└──────────┬───────────┘  └──────────┬───────────┘
           │                         │
           │                    Human approval
           │                         │
           │                         ▼
           │                ┌──────────────────┐
           │                │ Execute Approved │
           │                │ Action           │
           │                └────────┬─────────┘
           │                         │
           └────────────┬────────────┘
                        ▼
┌──────────────────────────────────────────────────────────────────────┐
│ 7. AUDIT                                                             │
│                                                                      │
│ Every decision is permanently recorded as an immutable outbox record │
│                                                                      │
│   Input Signal Bundle                                                │
│          +                                                           │
│   Model Decision                                                     │
│          +                                                           │
│   Decision Justification                                             │
│          │                                                           │
│          ▼                                                           │
│   Immutable Audit Record                                             │
└──────────────────────────────────────────────────────────────────────┘
```

**Validation, before anything executes.**
- A closed enum of allowed actions — `RETRY_SILENT`, `OFFER_ALT_METHOD`, `SEND_NUDGE`,
  `ESCALATE_HUMAN`, `STOP`. Anything outside this set is rejected.
- A hard cap of 3 total attempts per order, enforced in code, independent of the model.
- A `requires_human_approval` flag, derived from the action type in code — never taken as
  an unchecked model claim.

**Exit.** No approval required → executes immediately through the platform's idempotent
order/payment APIs. Approval required → held in a queue until a human approves or rejects
it, then executes through the same layer.

**Always.** Every decision, either path, is written to the outbox as a permanent audit
record — input bundle, decision, justification, outcome.

### Key Safety Guarantees

| Guarantee                   | Enforcement                                                    |
| --------------------------- | -------------------------------------------------------------- |
| **Closed action space**     | Model output is validated against a predefined enum            |
| **Maximum attempts**        | Hard limit of 3 attempts enforced in application code          |
| **Human approval**          | Derived deterministically from the action type                 |
| **No raw logs to LLM**      | Model receives only the structured Signal Bundle               |
| **Structured model output** | Strict JSON schema enforced at the model boundary              |
| **Idempotent execution**    | Existing order/payment idempotency key is reused               |
| **Durable auditability**    | Every decision is persisted as an immutable outbox record      |
| **Human-in-the-loop**       | Risky actions are halted in a PostgreSQL-backed Approval Queue |

### Design Principle

> **The LLM recommends; deterministic application code decides what is actually allowed to execute.**

This separation ensures that the recovery agent can provide intelligent decisions without becoming a source of uncontrolled side effects. The model operates within a constrained action space, while critical business rules, attempt limits, approval requirements, and execution safeguards remain outside the model and under application control.


## Demo scenarios

**Scenario A — autonomous recovery.** A customer with a clean 3-for-3 order history and a
prior `INSUFFICIENT_FUNDS` decline that recovered within 41 hours triggers a new decline,
attempt #1. The agent returns `RETRY_SILENT`, high confidence, and it executes immediately
— no human step, since the action carries no customer-facing risk. The order flips to
`PAID`. A second "retry same action" click on the same order demonstrates the idempotency
guarantee live: the duplicate is detected and blocked, execution count stays at one.

**Scenario B — gated escalation.** A customer with no purchase history, on a `DO_NOT_HONOR`
decline, already at attempt #3 after two failed auto-retries. The agent returns
`ESCALATE_HUMAN`. The console shows **"AWAITING HUMAN APPROVAL — action is blocked"** before
any control is available, then a reviewer approves or rejects it on screen.

Both are one-click, deterministic demos from the Merchant Recovery Console at
`/recovery-console/dashboard.html` — no manual data seeding required.

## Why payment processing stays deterministic

Payment processing should behave identically given identical input — deterministic,
auditable, boring in the best sense. The recovery agent is deliberately kept downstream
and asynchronous: it never sits in the payment-service request path, never gates a live
checkout, and never introduces model variability into the one part of the system where
variability is actively undesirable. Its entire surface area is a decision about what to
do *after* a failure has already been recorded — it never influences how a payment is
actually processed.

## Observability & monitoring

Every service, including the recovery agent, reports into the same observability stack:

- **Prometheus metrics**: `business_recovery_attempts_total{action=...}`,
  `business_recovery_success_total`, `business_recovery_false_positive_total`,
  `business_revenue_recovered_total`, alongside standard JVM and Spring Actuator metrics.
- **Zipkin traces** cover the recovery agent the same way they cover every other service —
  a decision's full path is inspectable end to end.
- **Audit Log** on the console — every decision the agent has made, newest first, with its
  plain-text justification.
- **Batch run panel** — recovery rate, false-positive rate, revenue recovered vs. at risk,
  and the recovery-timing curve retry delays are grounded in, from a single on-demand run.

## Simulation & System Constraints

Payment decline codes and customer histories are **simulated** via a `FailureInjector`, due
to Razorpay test-mode limitations — sandbox checkout exposes only a binary success/failure
outcome, not decline-reason granularity, so no integration path provides this detail.

**Safety & blast radius.** The LLM does not execute code or contact customers directly. Its
blast radius is strictly bound by the `RecoveryDecisionService` — it can only output one of
a fixed set of `RecoveryAction` enums. All stopping rules, cooling-off periods, and approval
gates are enforced in the orchestration code, not by model discretion. High-risk decisions
(`ESCALATE_HUMAN`, `SEND_NUDGE`, `OFFER_ALT_METHOD`) automatically pause and land in the
approval queue for manual review before anything customer-facing happens.

## Challenges we hit

- **Context size vs. signal quality.** Feeding a customer's full order history to the model
  on every call doesn't scale and adds latency for no benefit — the signal bundle is kept
  to a small, structured field set rather than raw historical data, which also made model
  output far more consistent.
- **Model selection.** Gemini 3.7 Flash was chosen for reliable structured/JSON output at
  low latency and cost for a high-volume, low-complexity decision task — this doesn't need
  a large reasoning model, and using one would only slow down batch evaluation.
- **Free-tier rate limiting.** Iterating on plumbing rather than model behavior repeatedly
  hit the free-tier daily request cap. Solved with an `LLM_MOCK_MODE` toggle for
  logic/integration debugging (canned, schema-valid responses, zero real calls) and a
  response cache keyed by input signal hash, so real quota is spent only where it matters.

## Tech stack

| Category | Technology |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 3 |
| API Gateway | Spring Cloud Gateway |
| Database | PostgreSQL |
| Cache | Redis |
| Messaging | RabbitMQ |
| Security | JWT |
| LLM | Gemini 3.7 Flash (structured JSON output) |
| Containerization | Docker & Docker Compose |
| Observability | Prometheus, Grafana, Zipkin |
| Concurrency | Java 21 Virtual Threads |

## Running it

Everything runs via Docker Compose — no local Java or Maven required.

```bash
git clone <your-repo-url>
cd recoup
cp .env.example .env
docker compose build
docker compose up -d
docker compose ps
```

Health check:
```bash
curl http://localhost:8086/actuator/health
```

Merchant Recovery Console:
```
http://localhost:8080/recovery-console/dashboard.html
```

Batch run and audit log:
```bash
curl -X POST "http://localhost:8080/recovery-console/api/v1/batch/run?count=30"
curl "http://localhost:8080/recovery-console/api/v1/audits"
```

### Environment variables

`.env` is gitignored and must never be committed — a real key in a public repo gets
scraped and abused within minutes, and stays in git history even after a later commit
removes it. Commit `.env.example` instead:

```
GEMINI_API_KEY=your_key_here
RAZORPAY_KEY_ID=your_key_here
RAZORPAY_KEY_SECRET=your_secret_here
LLM_MOCK_MODE=false
```

## Roadmap

- Extend the signal-bundle → fixed-action pattern to checkout-abandonment recovery.
- Replace the retry-timing heuristic with a model trained on real recovery outcomes once
  production data exists — every downstream consumer depends only on the fixed action
  schema, not on how the decision was produced, so this swap requires no architectural change.
- Route real Razorpay gateway status (pass/fail only) through the live sandbox, layering
  the existing simulated decline taxonomy on top.
