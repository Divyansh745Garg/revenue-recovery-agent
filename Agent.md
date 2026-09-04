# AGENT.md — Revenue Recovery Agent build brief

You are an AI coding agent (Claude Code / Codex) working inside a **fork** of an existing
distributed e-commerce microservices project. Read this whole file before making any changes.
Codex usage is limited — work through phases in large, self-contained passes rather than
stopping to ask questions unless something in Section 0 is genuinely ambiguous.

## 0. Non-negotiable ground rules

1. **Do not modify the existing reliability/consistency machinery.** The Saga choreography,
   transactional outbox, Redis idempotency keys, and RabbitMQ event contracts already in this
   repo are correct and out of scope. You are adding a new capability on top of them, not
   replacing or "improving" them. If a change to existing code is unavoidable (e.g. adding a
   field to the `Payment` model), keep it additive and backward compatible.
2. **The synchronous checkout path (gateway → auth → product → order → payment → notification)
   must not change.** Everything you build lives in a new, separate async consumer path,
   architecturally a sibling to `notification-service`, subscribing to events off RabbitMQ.
   Nothing in this project may add an LLM call to the hot path of a request.
3. **The LLM never executes anything directly.** It receives a structured input and returns a
   structured decision from a fixed, closed action set. A separate, plain-code orchestrator
   reads that decision and calls existing, already-idempotent service APIs to execute it. No
   function-calling / tool-use access for the LLM in this project.
4. **Every LLM decision is written to the outbox/audit trail, whether or not it required human
   approval.** No decision is untracked.
5. **No webhooks, no websockets.** Neither is required. If real Razorpay integration is
   attempted (Phase 8, optional), payment status is retrieved via a synchronous `payments.fetch`
   call immediately after checkout — never a webhook receiver, which would require a public
   tunnel (ngrok) and signature verification we don't have time for and don't need.
6. **Secrets never get committed.** `.env` is gitignored, always. A `.env.example` with
   placeholder values (`GEMINI_API_KEY=your_key_here`, `RAZORPAY_KEY_ID=your_key_here`,
   `RAZORPAY_KEY_SECRET=your_secret_here`) is committed instead. If you ever find a real key in
   a diff you're about to commit, stop and flag it instead of committing.
7. **Timebox strictly.** See Section 6. If behind schedule, cut in this order: Phase 8 (real
   Razorpay integration) → approval-queue UI polish → second demo scenario → Grafana dashboard
   polish. **Never cut the batch metrics run (Section 5) — it is the single most important
   artifact for evaluation.**
8. **Maintain `PROGRESS.md` at the repo root.** After finishing each phase in Section 6, append
   an entry: phase number, what was completed, what was skipped/deferred and why, any manual
   follow-up still needed. This is the resumability record if Codex quota runs out mid-build —
   keep it accurate and current, not retrospective.

## 1. What problem this solves (keep this framing in code comments / README)

Payment declines split into three buckets. Only one of them benefits from an LLM.

**Bucket 1 — Technical (auto-retry, no LLM, no judgment needed).** System-level failures where
nothing was refused — a retry is just "ask again, properly, this time."
- Payment gateway timeout
- Gateway 5xx / internal error
- Network blip between your services
- payment-service itself down or restarting
- RabbitMQ broker temporarily unavailable
- Issuer/bank system timeout (they didn't respond in time — not that they said no)
- Gateway rate-limiting you (429)

**Bucket 2 — Terminal (notify & stop, no LLM, no judgment needed).** The issuer made a decision
and a retry cannot change it.
- Card expired
- Card reported lost or stolen
- Card canceled/blocked by issuer
- Hard, explicit fraud block from the issuer
- Account closed
- (Borderline — a checkout-UX issue more than a recovery issue: repeated CVV/card-number
  mismatch is user input error, not something a backend retry fixes. Don't route this anywhere;
  it's out of scope.)

**Bucket 3 — Soft/ambiguous (routed to the agent — this is the whole project).** The right
response genuinely depends on weighing several weak, competing signals together.
- Insufficient funds
- Generic "do not honor" (banks often use this as a catch-all for soft flags they won't specify)
- Soft algorithmic risk hold (issuer's risk engine was cautious, not certain — not a hard block)
- OTP/3DS authentication failure (could be a genuine block, or the customer just fumbled the OTP)
- Issuer-issued "soft decline, please retry" code (a small number of decline codes explicitly
  mean "not now, but try again")
- Velocity/frequency limit temporarily triggered (too many attempts in a short window — often
  self-resolves)

The agent's job: given a signal bundle for one Bucket 3 decline, choose one action from a fixed
menu, with a written justification, subject to a stopping rule and (for anything customer-facing
or spend-impacting) human approval before execution.

## 2. New components to build

### 2.1 `PaymentFailureReason` enum (extend existing payment/order model)

```java
public enum PaymentFailureReason {
    // Bucket 1 — Technical — auto-retry, no LLM, existing infra handles this
    GATEWAY_TIMEOUT,
    GATEWAY_5XX,

    // Bucket 2 — Terminal — notify & stop, no LLM, existing infra handles this
    CARD_EXPIRED,
    CARD_STOLEN_BLOCKED,
    FRAUD_HARD_BLOCK,

    // Bucket 3 — Soft/ambiguous — routed to the recovery agent
    INSUFFICIENT_FUNDS,
    DO_NOT_HONOR,
    OTP_3DS_FAILED,
    RISK_SOFT_HOLD
}
```

Add a `declineCode` field (this enum) and keep the existing pass/fail status field as-is —
additive, not a replacement.

### 2.2 `FailureInjector` (replaces the current binary mock success/fail)

A weighted-random generator producing outcomes across the enum above. Suggested weights:
~35% technical, ~20% terminal, ~45% soft/ambiguous (weight soft heavier — it's the demo's
centerpiece). Each synthetic order also gets a synthetic customer history record: prior order
count, prior successful orders, prior decline-and-recovery events with realistic timing.

**Also build the recovery-timing dataset**: when generating synthetic soft/ambiguous declines,
assign each a probabilistic "hours until a retry would have succeeded," drawn from a distribution
that peaks somewhere in a plausible window (do not hardcode a flat constant — see Section 2.4).
This produces real, inspectable data instead of an asserted number.

### 2.3 New service: `recovery-agent-service`

Same stack as the rest of the project (Spring Boot) — do not introduce a new language/framework
under this deadline.

Responsibilities:
- Consume `payment.failed` events off RabbitMQ.
- Deterministic pre-filter (plain code, no LLM): route Bucket 1 reasons to the existing
  auto-retry path; route Bucket 2 reasons to the existing notify-and-stop path. Only Bucket 3
  reasons continue to the steps below.
- Build the **signal bundle** (structured, not raw logs) by querying existing order/payment/
  customer data:

```json
{
  "order_id": "ord_8841",
  "customer_id": "cust_552",
  "decline_reason": "INSUFFICIENT_FUNDS",
  "order_value": 4200,
  "attempt_number": 1,
  "prior_recovery_attempts_this_order": 0,
  "customer_order_history": {
    "total_prior_orders": 3,
    "prior_orders_succeeded": 3,
    "prior_same_reason_declines": 1,
    "prior_same_reason_recovered_within_hours": 41
  },
  "hours_since_decline": 2
}
```

- Call Gemini 3.7 Flash (`gemini-3.7-flash`, endpoint
  `https://generativelanguage.googleapis.com/v1beta/models/gemini-3.7-flash:generateContent`,
  auth via `x-goog-api-key` header, key read from `GEMINI_API_KEY` env var) with the bundle,
  using structured/JSON output. System prompt instructs the model to return **only** JSON
  matching the schema in 2.4 below, and to ground its `delay_hours` choice in the customer's own
  `prior_same_reason_recovered_within_hours` when present, falling back to a supplied
  population-level recovery curve otherwise. Enforce a max attempt count in the prompt/logic —
  never let the model recommend more than 3 total attempts on one order.

### 2.4 Fixed action schema (the LLM's entire output surface)

```json
{
  "action": "RETRY_SILENT | OFFER_ALT_METHOD | SEND_NUDGE | ESCALATE_HUMAN | STOP",
  "delay_hours": 18,
  "confidence": "high | medium | low",
  "requires_human_approval": true,
  "justification": "One sentence, becomes part of the audit log"
}
```

`requires_human_approval` is `true` for anything customer-facing or spend-impacting
(`OFFER_ALT_METHOD`, `SEND_NUDGE`, `ESCALATE_HUMAN`); `false` only for `RETRY_SILENT` and `STOP`,
since those have no customer-facing side effect.

**On the `delay_hours` number — do not present it as a flat constant.** Priority order:
1. If `customer_order_history.prior_same_reason_recovered_within_hours` is present, use that
   (customer-specific, defensible).
2. Otherwise, use the peak of the population-level recovery-vs-hours-since-decline curve
   generated from the synthetic batch (Section 5) — computed, not asserted. Surface this curve
   as a chart in the demo.

### 2.5 Orchestrator (plain code, not the LLM)

Reads the decision. If `requires_human_approval` is `false`, executes immediately through the
**existing** idempotent order/payment APIs (same idempotency-key mechanism already in the repo —
do not build a new one). If `true`, writes a row to a minimal approval queue instead of executing.

### 2.6 Approval queue (minimal — do not over-invest here)

A table + two endpoints: list pending, approve/reject by id. A basic HTML page or even
Postman/curl calls shown on camera are acceptable. On approve, the orchestrator executes exactly
as it would have for an auto-safe action. On reject, write a `REJECTED` outbox record and stop.

### 2.7 Audit trail

Every decision (auto-executed or approval-gated, approved or rejected) gets an outbox record
containing: input signal bundle, full LLM output including justification, execution outcome,
timestamp. Reuse the existing outbox pattern and table structure — this is additive rows, not new
infrastructure.

### 2.8 Metrics

Extend the existing Prometheus setup with:
- `business_recovery_attempts_total{action=...}`
- `business_recovery_success_total`
- `business_recovery_false_positive_total` (an escalation/nudge that a human rejected or that
  didn't recover money)
- `business_revenue_recovered_total`

Add one Grafana panel if time allows; a printed batch summary is an acceptable substitute if not.

## 3. What NOT to build

- No webhook receiver, no websocket server — see ground rule 5.
- No LLM function-calling / tool access.
- No fifth or sixth demo scenario — two named scenarios plus one batch run is the complete target
  (Section 4). Depth over breadth.
- No new database, message broker, or language runtime beyond what's already in the repo.
- Do not attempt to derive Bucket 3 decline-reason granularity from Razorpay's real test mode —
  it doesn't expose that (only success/failure). If Phase 8 is attempted, the real gateway
  supplies the pass/fail signal only; reason-code detail still comes from the synthetic layer.

## 4. Target demo scenarios (build to exactly these, no more)

**Scenario A — auto-safe path.** Customer with 3/3 prior successful orders, 1 prior
`INSUFFICIENT_FUNDS` decline that recovered in 41h historically. New order declines the same way,
attempt #1. Agent returns `RETRY_SILENT`, `requires_human_approval: false`. Orchestrator executes
directly via the existing idempotent retry endpoint. Compress the wait for demo purposes — do not
literally sleep in real time. Show the order flip to `PAID` and the audit record.

**Scenario B — approval-gated path.** Customer with no purchase history, `DO_NOT_HONOR`, already
attempt #3 (two prior auto-retries failed). Agent returns `ESCALATE_HUMAN`,
`requires_human_approval: true`, citing the repeated-decline/no-history signals. Show it land in
the approval queue, then approve/reject it on camera.

Give both scenarios roughly equal screen time in the video — Scenario A is the proof the system
executes autonomously where it's safe to; Scenario B is the proof it gates where it isn't.

## 5. Batch run (the most important artifact — protect this above all else)

Script that fires 100–200 simulated failed payments across a realistic mix of all reason codes,
lets the full pipeline run end to end, and prints/exports a summary:
- Recovery rate (Bucket 3 only)
- False-positive / unnecessary-escalation rate
- Revenue recovered vs. revenue at risk
- The recovery-vs-hours-since-decline curve referenced in 2.4

Report the numbers honestly, including the unflattering ones. Do not tune the synthetic
distribution to make the numbers look artificially good.

## 6. Time-boxed plan — execute each phase in one self-contained pass

For each phase below: implement everything listed, run `docker compose up -d --build`, verify it
actually works (curl the relevant endpoint / check the queue / check the log), THEN update
`PROGRESS.md` with a status entry, THEN move to the next phase. Don't ask for confirmation
between sub-steps within a phase — only stop and ask if a ground rule in Section 0 is genuinely
unclear for the specific case in front of you.

**Phase 1 (today, remainder):** Section 2.1 enum + `declineCode` field. Section 2.2
`FailureInjector` with synthetic customer history and recovery-curve data generation.
`payment-service` publishes enriched `payment.failed` events carrying the reason code.

**Phase 2 (tomorrow morning):** Section 2.3 `recovery-agent-service` scaffold, RabbitMQ consumer,
deterministic pre-filter, signal bundle construction, Gemini 3.7 Flash call wired to the fixed
schema in 2.4.

**Phase 3 (tomorrow afternoon):** Section 2.5 orchestrator, 2.6 approval queue, 2.7 audit records,
2.8 metrics counters.

**Phase 4 (tomorrow evening):** Section 5 batch runner + summary output. This is the line not to
cross without having working, even if it means cutting Phase 8.

**Phase 5:** Reproduce Scenario A and B (Section 4) end to end, each runnable via a single command
or short script. Fix anything that breaks the reproduction before moving on.

**Phase 6:** README update — state plainly that decline codes are simulated (with the Razorpay
test-mode rationale from Section 3), the LLM's blast radius is limited to the fixed action set,
and stopping/approval rules are enforced in code, not by the model's discretion.

**Phase 7:** Final `PROGRESS.md` review — confirm every phase has a status entry, nothing silently
skipped without a note.

**Phase 8 (optional, only if all above is done with time still remaining):** Real Razorpay
integration, scoped narrowly:
- Create a test-mode Razorpay account, generate test API keys, store as `RAZORPAY_KEY_ID` /
  `RAZORPAY_KEY_SECRET` in `.env` (never committed — see ground rule 6).
- `payment-service` creates a real Razorpay Order via their Orders API for each checkout.
- Use Razorpay's test Checkout to complete or fail the payment: for cards, use one of Razorpay's
  published test card numbers (e.g. `4718 6091 0820 4366`, any future expiry, any random CVV —
  these always succeed); for UPI, use `success@razorpay` or `failure@razorpay` to deterministically
  control the outcome.
- Immediately after checkout, call Razorpay's `payments.fetch` REST endpoint synchronously to get
  the real status — no webhook, no public tunnel needed.
- The real status feeds the existing binary success/fail path exactly as the mock did before; on
  failure, the `FailureInjector`'s reason-code logic still assigns the Bucket 1/2/3 classification,
  since Razorpay's test mode doesn't provide that granularity itself. Document this clearly in the
  README so it isn't mistaken for something the gateway actually reported.
- Day-after (recording day): no new code, integration or otherwise.

## 7. Definition of done

- [ ] Existing synchronous checkout flow unchanged and still passes whatever tests/health checks
  it had before.
- [ ] Bucket 1 and Bucket 2 failures never reach the LLM (verify with a log/metric showing zero
  LLM calls for those reason codes).
- [ ] Scenario A and B both reproducible end to end with a single command or short script.
- [ ] Every decision, approved or not, has a corresponding audit record.
- [ ] Batch runner produces recovery rate, false-positive rate, revenue recovered, and the
  recovery-timing curve, from a single run.
- [ ] README states plainly: decline codes are simulated (with the Razorpay test-mode rationale),
  the LLM's blast radius is limited to picking from a fixed action set, and stopping/approval
  rules are enforced in code, not by the model's discretion.
- [ ] `.env` is gitignored; `.env.example` is committed with placeholder values only; no real key
  appears anywhere in git history.
- [ ] `PROGRESS.md` has one entry per phase, accurately reflecting what's done/deferred.
