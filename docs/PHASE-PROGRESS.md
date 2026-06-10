# PHASE-PROGRESS — T8 Home Services "QuoteNow" (`home-quotenow`)

Branch `home-quotenow` off `main` (811b157). Module `quoting`, error band **4430–4449**, default OFF.
Detail plan: `~/.claude/plans/home-quotenow.md`. The first vision-COMPOSITION flagship (photo →
price RANGE → repair-vs-replace → book), reusing the shipped vision spine (`AiVisionService.extract`,
the `EquipmentVisionService` precedent).

This ledger is the authoritative per-sub-phase progress record (the `resume-interrupted-phase`
contract). One row per sub-phase: status, the commit(s), and the validation evidence. It replaces the
prior `gate2-nurture-copyfilter-scoping` ledger that occupied this path — that work is already on `main`.

| Sub-phase | Scope | Status | Commit(s) | Validation |
|---|---|---|---|---|
| Q0 | detail plan + this ledger | DONE | (first commit) | n/a — docs |
| Q1 | module + `PriceBook` + `QuoteSynthesisService` (range synthesis) + price-book CRUD + demo skeleton | DONE | (Q1 commit) | `QuoteSynthesisServiceTest` 11/0 green; `compileJava` clean |
| Q2 | `QuoteVisionService` (photo→attrs) + confidence + the "estimate, final price after inspection" guardrail; vision-fail → manual path | DONE | (Q2 commit) | `QuoteVisionServiceIT` 4/0 green (legible read feeds synthesis + disclaimer; image block hit WireMock; partial→confidence 0.5; upstream-500→empty attrs + photo stored; blank→empty) |
| Q3 | `RepairVsReplaceReasoner` (+ financing flag) + `QuoteBookingService` (booking-link SMS, no live Cal.com; optional Documenso SOW on REPLACE) | PENDING | | |
| Q4 | public intake endpoint(s) + accept + office quote-inbox (list/detail) + token issuer + finish demo seed + openapi regen + CLAUDE.md T8 | PENDING | | |

## Invariants carried (the §9 / §7 contract)
- `getMultipartData()` for the photo — never `@RequestBody MultiValueMap`.
- Reused cores empty-diff vs `main`: `AiVisionService`, `EquipmentVisionService`, `QuoteService`,
  `QuotePdfService`, `ServiceRequestWidgetController`, `EquipmentPhotoController`, `TwilioSmsService`,
  `IntegrationConnection`(+repo). The `quoting` package is strictly additive (+ the `.imports` line,
  the `GlobalErrorHandler` 4430–4449 Javadoc, the `DomainEventType` T8 block, app props, openapi regen).
- `switchIfEmpty` only for genuine not-found; every conditional-create / status-guard is
  explicit-boolean — never `switchIfEmpty(create/send)`.
- The price RANGE is the product (never a single number); EVERY range carries the non-blank
  estimate-disclaimer (the wrong-number-liability fence; asserted in an IT).
- AI is triage, not truth — a vision budget/upstream/parse failure degrades to the manual path; the
  quote is always produced; the photo is always stored.
- No live external in the loop: AiVision → WireMock Anthropic; Twilio `@MockitoBean`'d; no live
  Cal.com / Documenso.

## Error codes (4430–4449)
- 4430 quote-intake token widgetType mismatch (401)
- 4431 price book not found for tenant (404, read)
- 4432 invalid price-book body (400)
- 4433 no image part on a photo intake (400)
- 4434 unsupported image media type (415)
- 4435 quote not found (accept) (404)
- 4436 quote not in an acceptable state (409, explicit-boolean)
- 4437 invalid intake/manual-attrs body (400)
- 4438–4449 RESERVED
- Reused (NOT re-allocated): 1600–1603 (widget token), 1200–1203 (AI budget/upstream/missing-key —
  via `AiVisionService`), 1310/1311 (file storage), 2530–2532 (Twilio SMS), 2510 (Documenso-not-
  connected if the optional SOW path is exercised), 1130/1132 (module gate), 1800 (RoleGuard ADMIN).
