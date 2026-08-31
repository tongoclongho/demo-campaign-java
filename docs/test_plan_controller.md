# Controller Test Plan

Scope: `CampaignController`, `ClaimController`, and the exception mapping in `ControllerAdvice`.

## Slice and conventions

Use the existing pattern — `@WebMvcTest(<Controller>.class)` + `@AutoConfigureRestTestClient`, with
every collaborator supplied as `@MockitoBean`. No Docker, no database. Naming follows the convention
already in the repo: `method_condition_then_outcome`.

```java
@WebMvcTest(ClaimController.class)
@AutoConfigureRestTestClient
class ClaimControllerTest {
    @Autowired private RestTestClient restTestClient;
    @MockitoBean private ClaimCampaignService claimCampaignService;
    @MockitoBean private ClaimCampaignRepository claimCampaignRepository;
    @MockitoBean private MapperConfig mapperConfig;
}
```

`ControllerAdvice` is picked up by the `@WebMvcTest` slice, so error mapping is verified through the
endpoints rather than in a separate test class. Error bodies are the `ErrorResponse` record
(`{"code":…,"message":…}`); validation failures instead return a flat field→message JSON map.

## Existing coverage

| Test | Status |
| --- | --- |
| `CampaignControllerTest.getCampaign_then_success_with_status_200` | present |
| `CampaignControllerTest.createCampaign_with_correct_request_body_then_success_with_status_201` | present |
| `CampaignControllerTest.createCampaign_with_missing_campaignCode_then_failed_with_status_400` | present |
| `ClaimControllerTest.getClaimCampaignStatus_from_existing_claim_campaign_then_success_with_status_200` | present |

Everything below is new.

## CampaignController — `GET /api/v1/campaign`

| ID | Scenario | Arrange | Assert |
| --- | --- | --- | --- |
| CC-G1 | no campaigns exist | `getCampaigns()` returns empty list | 200, body `[]` |
| CC-G2 | usedSeats is carried through | DTO with `totalCapacity=5`, `usedSeats=3` | 200, `$[0].usedSeats == 3` |
| CC-G3 | multiple campaigns | two DTOs | 200, size 2, order preserved as returned |
| CC-G4 | service throws unmapped runtime exception | `getCampaigns()` throws `RuntimeException` | 500, `code == 500`, message is the generic `"An unexpected error occurred on the server."` — asserts the catch-all handler does not leak the cause |

## CampaignController — `POST /api/v1/campaign`

| ID | Scenario | Arrange | Assert |
| --- | --- | --- | --- |
| CC-P1 | `totalCapacity = 0` violates `@Min(1)` | body `{"campaignCode":"X","totalCapacity":0}` | 400, `$.totalCapacity` present |
| CC-P2 | negative `totalCapacity` | `totalCapacity = -1` | 400, `$.totalCapacity` present |
| CC-P3 | blank (whitespace) campaignCode | `{"campaignCode":"   ", …}` | 400, `$.campaignCode == "must not be blank"` |
| CC-P4 | duplicate code rejected by service | service throws `ExistingCampaignCodeException` | 400, `$.code == 400`, `$.message` contains the code |
| CC-P5 | request body reaches the service unchanged | valid body | `verify(campaignService).createCampaign(captor)`; captured `campaignCode`/`totalCapacity` match the JSON — the controller must not pre-normalise |
| CC-P6 | **`totalCapacity` omitted entirely** | `{"campaignCode":"X"}` | Documents a real gap: `@Min` treats `null` as valid and there is no `@NotNull`, so this currently passes validation and reaches the service. Assert current behaviour (delegates to service), and file the fix — see *Known gaps*. |
| CC-P7 | malformed JSON | body `"{"` | Currently 500 via the catch-all handler, because `HttpMessageNotReadableException` has no handler. Assert current behaviour and see *Known gaps*. |

## ClaimController — `POST /api/v1/claim`

| ID | Scenario | Arrange | Assert |
| --- | --- | --- | --- |
| CL-P1 | happy path | service returns DTO with status `SUBMITTED` | 200, `$.status == "SUBMITTED"`, `$.userId`, `$.campaignId` |
| CL-P2 | `campaignId` null | `{"userId":1}` | 400, `$.campaignId == "must not be null"` |
| CL-P3 | `userId` null | `{"campaignId":1}` | 400, `$.userId == "must not be null"` |
| CL-P4 | both null | `{}` | 400, both keys present in the map |
| CL-P5 | user already claimed | service throws `CampaignClaimedAlreadyException` | 400, `$.code == 400`, message propagated |
| CL-P6 | campaign full | service throws `CampaignNoAvailableSeatsException` | 400, message propagated |
| CL-P7 | unknown campaign | service throws `NoSuchCampaignException` | 400 |
| CL-P8 | unknown user | service throws `NoSuchUserException` | 400 |
| CL-P9 | **client-supplied `claimDate` is ignored** | body includes `"claimDate":"2020-01-01T00:00:00"` | capture the `CreateClaimCampaign`; `claimDate` is ~now, not 2020 — `@JsonIgnore` means the field is server-assigned and a client cannot backdate itself to the front of the priority queue. This is a security-relevant assertion, since queue order is scored on `claimDate`. |
| CL-P10 | `reason` omitted when null | DTO with `reason == null` | 200, response JSON has no `reason` key (`@JsonInclude(NON_NULL)`) |
| CL-P11 | `reason` present when rejected | DTO with `status=REJECTED`, `reason="…"` | 200, `$.reason` present |

## ClaimController — `GET /api/v1/claim`

| ID | Scenario | Arrange | Assert |
| --- | --- | --- | --- |
| CL-G1 | claim not found | repository returns `Optional.empty()` | Currently **500** — `NoSuchClaimCampaignException` has no handler in `ControllerAdvice` and falls to the catch-all. Assert current behaviour; see *Known gaps*. |
| CL-G2 | `userId` param missing | `GET /api/v1/claim?campaignId=1` | Currently 500 (`MissingServletRequestParameterException` unmapped). Same gap as CL-G1. |
| CL-G3 | `campaignId` param missing | `GET /api/v1/claim?userId=1` | as above |
| CL-G4 | non-numeric param | `?userId=abc&campaignId=1` | Currently 500 (`MethodArgumentTypeMismatchException` unmapped) |
| CL-G5 | params passed in the right order | valid request | `verify(repo).findByUserIdAndCampaignId(1L, 2L)` with `?userId=1&campaignId=2` — guards against the two `Long`s being transposed |
| CL-G6 | REJECTED claim is returned as-is | repo returns REJECTED entity with reason | 200, `$.status == "REJECTED"`, `$.reason` present |

## Known gaps this plan documents

These are existing defects, not test bugs. Each case above asserts **current** behaviour so the
suite stays green; if any is fixed, update the linked case to the desired expectation.

1. `NoSuchClaimCampaignException` (and the Spring binding exceptions above) have no
   `@ExceptionHandler`, so client errors surface as 500. A `404` handler for the former and a `400`
   handler for `HttpMessageNotReadableException` / `MissingServletRequestParameterException` /
   `MethodArgumentTypeMismatchException` would fix CL-G1..G4 and CC-P7.
2. `CreateCampaign.totalCapacity` needs `@NotNull` alongside `@Min(1)`; without it a null capacity
   reaches Hibernate and fails as a 500 at insert time (CC-P6).
