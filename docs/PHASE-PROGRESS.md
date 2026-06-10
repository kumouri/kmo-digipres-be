# PHASE-PROGRESS — T9 Salon "StyleConsult AI" (`salon-styleconsult`)

Branch `salon-styleconsult` off `main` (e169596). New module `module/styleconsult`, rides the
**`chairfill`** module key (+ `@ConditionalOnBean(SalonBookingService.class)` — salon-spa loaded),
error band **4450–4459**, default OFF. Detail plan: `~/.claude/plans/salon-styleconsult.md`. The
salon's first **vision-COMPOSITION** use: an inspiration photo → `AiVisionService` hair/style
assessment → margin-aware service + retail recommendations → consult-to-booking + retail-attach
analytics. The closest precedent is **T8 QuoteNow** (`module/quoting`: photo intake → `AiVisionService`
→ recommendation → book).

This ledger is the authoritative per-sub-phase progress record (the `resume-interrupted-phase`
contract). One row per sub-phase: status, the commit(s), and the validation evidence. It replaces the
prior T8 `home-quotenow` ledger that occupied this path — that work is already on `main`.

| Sub-phase | Scope | Status | Commit(s) | Validation |
|---|---|---|---|---|
| S0 | detail plan + this ledger | DONE | 4fdcee6 | n/a — docs |
| S1 | `module/styleconsult` + `StyleConsult`/`StyleAttributes` models + `StyleConsultVisionService` (inspo photo→attrs, the `QuoteVisionService` twin) + public intake controller + orchestrator | DONE | 0e47bb5 (impl) / 33538b1 (tests) | `StyleConsultVisionServiceIT` 4/0 (legible read→confidence 1.0; partial→0.5; image block hit WireMock; upstream-500→empty attrs + photo stored; blank→empty). `StyleConsultIntakeIT` 6/0. |
| S2 | `StyleRecommendationService` (pure: style→service rules + margin-aware retail ranking over the product catalog) + the `Product.unitCost` additive-nullable margin seam + the STYLIST_CONFIRM guardrail | DONE | 0e47bb5 / 33538b1 | `StyleRecommendationServiceTest` 7/0 (**higher-margin product first; null-cost ranked last; service-typed/inactive excluded; max-N cap**; style→service mapping; the STYLIST_CONFIRM_NOTE guardrail on every rec). |
| S3 | consult-to-booking funnel — `StyleConsultBookingService.accept` → real `Booking` via the unchanged `SalonBookingService.create`; link consult→booking; booking-link SMS (no live Cal.com); explicit-boolean idempotent | DONE | 0e47bb5 / 33538b1 | `StyleConsultAcceptIT` 5/0 (accept→1 Booking via salon service + consult BOOKED/linked + SMS mocked; re-accept→no 2nd Booking/SMS; explicit service choice; 4455; 4450). **@IdempotentRoute removed from the public accept route** — `IdempotencyWebFilter` needs a TenantContext at filter time (the token sets it inside the controller); service-level explicit-boolean is the idempotency. |
| S4 | retail-attach analytics + office consult-inbox + token issuer + demo seed + CLAUDE.md T9 + openapi check | DONE | 0e47bb5 / 33538b1 | `StyleConsultAnalyticsIT` 4/0 (retail-attach funnel; office inbox; 1132 per-tenant gate), `StyleConsultModuleGateIT` 2/0 (chairfill OFF→absent+404). Regression green: `AiVisionServiceIT` 5/0, `EquipmentVisionIT` 6/0, `module.chairfill.*` (these exercise the salon-spa `SalonBookingService`/`ServiceMenu`; 50/0 across 8 classes), `OpenApiEndpointIT` 2/0. openapi.json unchanged — NO styleconsult routes (module OFF in OpenApiEndpointIT → FE hand-writes all api/*.ts, the T1-T8 precedent). |

## Invariants carried (the §9 / §7 contract)
- `getMultipartData()` for the inspiration photo — never `@RequestBody MultiValueMap`.
- Reused cores empty-diff vs `main`: `AiVisionService`, `QuoteVisionService`/all `module/quoting`,
  `SalonBookingService`, `Booking`, `ServiceMenu`/`SalonMenuService`, `ProductService`/`ProductController`,
  `PublicWidgetTokenService`, `FileStorageService`, `AttachmentRepository`, `ContactRepository`,
  `TwilioSmsService`, `IntegrationConnection`(+repo), all `module/chairfill`. The `styleconsult`
  package is strictly additive (+ the `.imports` line, the `GlobalErrorHandler` 4450–4459 Javadoc, the
  `DomainEventType` T9 block, app props). **The one justified seam:** a strictly-additive nullable
  `Product.unitCost` (the margin source — `Booking.noShowRisk` additive-nullable precedent; legacy
  products deserialize null = zero/unknown margin, ranked last; `ProductService`/`Controller`
  behaviour otherwise byte-identical).
- `switchIfEmpty` only for genuine not-found; every conditional-create / accept-guard is
  explicit-boolean — never `switchIfEmpty(create/book)`.
- The margin-aware retail ranking is the headline correctness property: products ranked by
  `unitPrice − unitCost` descending (null cost → margin 0, last); asserted in
  `StyleRecommendationServiceTest`.
- Every recommendation carries the central `StyleRecommendationService.STYLIST_CONFIRM_NOTE` — the
  never-auto-charge guardrail; set centrally so it can never be omitted; asserted in an IT.
- AI is triage, not truth — a vision budget/upstream/parse failure degrades to the manual/notes path;
  the consult is always produced; the photo is always stored.
- No live external in the loop: AiVision → WireMock Anthropic; Twilio `@MockitoBean`'d; no live
  Cal.com; FileStorage in-memory `@Bean @Primary` stub.

## Error codes (4450–4459)
- 4450 style-consult token widgetType mismatch (401)
- 4451 no image part on an explicitly-empty photo intake (400, defensive — a missing photo is the manual path)
- 4452 invalid intake / manual-attrs body (400, defensive/reserved)
- 4453 no bookable service to recommend/book (404, on accept when nothing is resolvable from the menu)
- 4454 unsupported image media type (415)
- 4455 style consult not found for the tenant (404, accept/detail)
- 4456 consult not in an acceptable state — cannot accept (409, explicit-boolean) — RESERVED-as-advisory
- 4457–4459 RESERVED
- Reused (NOT re-allocated): 1600-1603 (widget token), 1200-1203 (AI budget/upstream/missing-key via
  `AiVisionService`), 1310/1311 (file storage), 2530-2532 (Twilio SMS), 2900 (salon Booking/menu
  not-found via the unchanged salon services), 1130/1132 (module gate via
  `TenantModuleRegistry.requireEnabled("chairfill")`), 1800 (RoleGuard ADMIN).

## Endpoints (the FE leg types to these)
- `POST /public/integrations/styleconsult/{token}/consult` (multipart) → `StyleConsultResponse` — the
  public inspiration-photo intake (photo optional; manual notes path first-class).
- `POST /public/integrations/styleconsult/{token}/consults/{consultId}/accept?serviceMenuItemId=` —
  `@IdempotentRoute` → `StyleConsultResponse` — accept → book.
- `GET /styleconsult/consults`[/{id}] (staff, `requireEnabled("chairfill")`) → office consult inbox.
- `GET /styleconsult/analytics` (staff) → `StyleConsultAnalytics` (retail-attach funnel).
- `POST /styleconsult/tokens` (ADMIN) → `{"token": "..."}` — mint the `style-consult` widget token.

All carry the module `@ConditionalOnProperty(kmosf.modules.chairfill)` → absent from the OpenAPI spec
when the module is off (`OpenApiEndpointIT` runs with chairfill OFF) → the FE hand-writes the
`api/*.ts` (the T1–T8 precedent). No `openapi.json` change expected.
