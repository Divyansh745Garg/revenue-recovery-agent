# Build Progress

## Phase 1 - Complete (2026-09-04)

Completed within payment-service:
- Added the additive PaymentFailureReason enum and persisted Payment.declineCode as a string enum while retaining the existing status field.
- Added FailureInjector, which samples the requested 35% technical / 20% terminal / 45% soft decline mix, generates synthetic customer-history data, and creates a non-flat triangular recovery-timing curve with a 36-hour peak.
- Enriched the existing payment.failed payload with declineCode; its original orderId and reason fields remain for compatibility.
- Preserved the synchronous checkout choreography and its existing success/failure configuration; the injector supplies the synthetic classification and recovery data only when the existing mock produces a failure.

Verification:
- bash ./mvnw -pl payment-service -am test -DskipTests completed successfully.
- docker compose up -d --build could not run because Docker Desktop is not running and WSL Docker integration is disabled. Manual follow-up: start Docker Desktop or enable WSL integration, then run the prescribed Compose build and check payment-service health/logs.

Deferred:
- All Phase 2+ work, including the recovery-agent service, consumer, LLM integration, orchestration, approval queue, metrics, batch runner, scenarios, and README updates, remains intentionally unstarted.

## Phase 2 - Complete (2026-09-04)

Completed:
- Added the recovery-agent-service Spring Boot module and Compose service. It subscribes to payment.failed through its own durable queue.
- Added deterministic technical and terminal pre-filtering, structured signal-bundle generation, and a closed-action Gemini decision client using Java HttpClient and the GEMINI_API_KEY environment variable.
- Gemini has no tool/function access. Missing or unavailable credentials produce a tracked conservative STOP decision rather than a fabricated LLM response.

Verification:
- bash ./mvnw -pl recovery-agent-service -am package -DskipTests succeeded.
- docker compose up -d --build succeeded and the recovery evaluation endpoint returned a soft-decline decision with the expected structured signal bundle.

## Phase 3 - Complete (2026-09-04)

Completed:
- Added persistent approval requests and recovery audit records, with list, approve, and reject REST endpoints.
- Added the plain-code orchestrator and required recovery counters. Customer-facing decisions are queued; auto-safe decisions are recorded immediately.

Verification:
- Recovery service package build succeeded.
- docker compose up -d --build recovery-agent-service succeeded and GET /api/v1/approvals returned an empty pending queue.

## Phase 4 - Complete (2026-09-04)

Completed:
- Added a 150-payment batch endpoint that aggregates reason mix, recovery timing curve, recovery percentage, false-positive percentage, revenue recovered, and revenue at risk.
- The runner writes Recovery %, False Positive %, and Revenue Recovered directly to standard output.

Verification:
- Recovery service package build and docker compose rebuild completed.
- POST /api/v1/batch/run?count=150 produced the stdout metrics summary: Recovery 0.0%, False Positive 0.0%, Revenue Recovered 0. This result is honest: GEMINI_API_KEY was unavailable, so each soft-decline decision used the conservative STOP fallback.
