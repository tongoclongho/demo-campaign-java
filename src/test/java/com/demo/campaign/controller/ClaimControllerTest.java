package com.demo.campaign.controller;

import com.demo.campaign.dto.ClaimCampaignDTO;
import com.demo.campaign.dto.CreateClaimCampaign;
import com.demo.campaign.dto.ErrorResponse;
import com.demo.campaign.entity.ClaimCampaign;
import com.demo.campaign.entity.ClaimCampaignStatus;
import com.demo.campaign.repository.ClaimCampaignRepository;
import com.demo.campaign.service.ClaimCampaignService;
import com.demo.campaign.service.exception.CampaignClaimedAlreadyException;
import com.demo.campaign.service.exception.CampaignNoAvailableSeatsException;
import com.demo.campaign.service.exception.NoSuchCampaignException;
import com.demo.campaign.service.exception.NoSuchUserException;
import com.demo.campaign.service.mapper.MapperConfig;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@WebMvcTest(ClaimController.class)
@AutoConfigureRestTestClient
class ClaimControllerTest {

    private static final ParameterizedTypeReference<Map<String, String>> FIELD_ERRORS =
            new ParameterizedTypeReference<>() {};

    @Autowired
    private RestTestClient restTestClient;

    @MockitoBean
    private ClaimCampaignService claimCampaignService;

    @MockitoBean
    private ClaimCampaignRepository claimCampaignRepository;

    @MockitoBean
    private MapperConfig mapperConfig;

    @Test
    void getClaimCampaignStatus_from_existing_claim_campaign_then_success_with_status_200() {
        // Arrange
        var claimCampaign = ClaimCampaign.builder()
                .campaignId(1L)
                .userId(1L)
                .status(ClaimCampaignStatus.APPROVED)
                .claimDate(LocalDateTime.now())
                .build();

        var claimCampaignDTO = ClaimCampaignDTO.builder()
                .campaignId(1L)
                .userId(1L)
                .status("APPROVED")
                .claimDate(LocalDateTime.now())
                .build();

        when(claimCampaignRepository.findByUserIdAndCampaignId(any(), any())).thenReturn(Optional.of(claimCampaign));
        when(mapperConfig.toDTO(any(ClaimCampaign.class))).thenReturn(claimCampaignDTO);

        // Act
        var result = restTestClient.get()
                .uri("/api/v1/claim?userId={userId}&campaignId={campaignId}", 1L, 1L)
                .exchange()
                .expectStatus().isOk()
                .expectBody(ClaimCampaignDTO.class)
                .returnResult()
                .getResponseBody();

        // Assert

        Mockito.verify(claimCampaignRepository, times(1)).findByUserIdAndCampaignId(any(), any());
        Mockito.verify(mapperConfig, times(1)).toDTO(any(ClaimCampaign.class));

        assertNotNull(result);
        assertEquals(1L, result.getCampaignId());
        assertEquals(1L, result.getUserId());
        assertEquals("APPROVED", result.getStatus());

    }

    // ---------------------------------------------------------------------
    // POST /api/v1/claim
    // ---------------------------------------------------------------------

    /**
     * CL-P1 — happy path. The synchronous response is the accepted submission, not the decision.
     */
    @Test
    void claimCampaign_with_correct_request_body_then_success_with_status_200() {
        // Arrange
        var body = Map.of("userId", 1, "campaignId", 2);
        var claimCampaignDTO = ClaimCampaignDTO.builder()
                .userId(1L)
                .campaignId(2L)
                .status(ClaimCampaignStatus.SUBMITTED.name())
                .claimDate(LocalDateTime.now())
                .build();
        when(claimCampaignService.claimCampaign(any(CreateClaimCampaign.class))).thenReturn(claimCampaignDTO);

        // Act
        var result = restTestClient.post()
                .uri("/api/v1/claim")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange()
                .expectStatus().isOk()
                .expectBody(ClaimCampaignDTO.class)
                .returnResult()
                .getResponseBody();

        // Assert
        verify(claimCampaignService, times(1)).claimCampaign(any(CreateClaimCampaign.class));
        assertNotNull(result);
        assertEquals(1L, result.getUserId());
        assertEquals(2L, result.getCampaignId());
        assertEquals("SUBMITTED", result.getStatus());
    }

    /**
     * CL-P2 — campaignId is mandatory.
     */
    @Test
    void claimCampaign_with_missing_campaignId_then_failed_with_status_400() {
        // Arrange
        var body = Map.of("userId", 1);

        // Act
        var result = restTestClient.post()
                .uri("/api/v1/claim")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(FIELD_ERRORS)
                .returnResult()
                .getResponseBody();

        // Assert
        assertNotNull(result);
        assertEquals("must not be null", result.get("campaignId"));
        Mockito.verifyNoInteractions(claimCampaignService);
    }

    /**
     * CL-P3 — userId is mandatory.
     */
    @Test
    void claimCampaign_with_missing_userId_then_failed_with_status_400() {
        // Arrange
        var body = Map.of("campaignId", 1);

        // Act
        var result = restTestClient.post()
                .uri("/api/v1/claim")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(FIELD_ERRORS)
                .returnResult()
                .getResponseBody();

        // Assert
        assertNotNull(result);
        assertEquals("must not be null", result.get("userId"));
        Mockito.verifyNoInteractions(claimCampaignService);
    }

    /**
     * CL-P4 — every violation is reported, not just the first one.
     */
    @Test
    void claimCampaign_with_missing_userId_and_campaignId_then_failed_with_status_400() {
        // Arrange
        var body = Map.of();

        // Act
        var result = restTestClient.post()
                .uri("/api/v1/claim")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(FIELD_ERRORS)
                .returnResult()
                .getResponseBody();

        // Assert
        assertNotNull(result);
        assertTrue(result.containsKey("userId"), () -> "expected a userId violation but got " + result);
        assertTrue(result.containsKey("campaignId"), () -> "expected a campaignId violation but got " + result);
        Mockito.verifyNoInteractions(claimCampaignService);
    }

    /**
     * CL-P5 — the user already holds a seat in the campaign.
     */
    @Test
    void claimCampaign_with_already_claimed_user_then_failed_with_status_400() {
        // Arrange
        var body = Map.of("userId", 1, "campaignId", 2);
        when(claimCampaignService.claimCampaign(any(CreateClaimCampaign.class)))
                .thenThrow(new CampaignClaimedAlreadyException("The user has claimed the campaign already."));

        // Act
        var result = restTestClient.post()
                .uri("/api/v1/claim")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(ErrorResponse.class)
                .returnResult()
                .getResponseBody();

        // Assert
        assertNotNull(result);
        assertEquals(400, result.code());
        assertEquals("The user has claimed the campaign already.", result.message());
    }

    /**
     * CL-P6 — the campaign is full.
     */
    @Test
    void claimCampaign_with_no_available_seats_then_failed_with_status_400() {
        // Arrange
        var body = Map.of("userId", 1, "campaignId", 2);
        when(claimCampaignService.claimCampaign(any(CreateClaimCampaign.class)))
                .thenThrow(new CampaignNoAvailableSeatsException("The campaign has no available seats."));

        // Act
        var result = restTestClient.post()
                .uri("/api/v1/claim")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(ErrorResponse.class)
                .returnResult()
                .getResponseBody();

        // Assert
        assertNotNull(result);
        assertEquals(400, result.code());
        assertEquals("The campaign has no available seats.", result.message());
    }

    /**
     * CL-P7 — unknown campaign.
     */
    @Test
    void claimCampaign_with_unknown_campaign_then_failed_with_status_400() {
        // Arrange
        var body = Map.of("userId", 1, "campaignId", 999);
        when(claimCampaignService.claimCampaign(any(CreateClaimCampaign.class)))
                .thenThrow(new NoSuchCampaignException("The campaign does not exist."));

        // Act
        var result = restTestClient.post()
                .uri("/api/v1/claim")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(ErrorResponse.class)
                .returnResult()
                .getResponseBody();

        // Assert
        assertNotNull(result);
        assertEquals(400, result.code());
        assertEquals("The campaign does not exist.", result.message());
    }

    /**
     * CL-P8 — unknown user.
     */
    @Test
    void claimCampaign_with_unknown_user_then_failed_with_status_400() {
        // Arrange
        var body = Map.of("userId", 999, "campaignId", 2);
        when(claimCampaignService.claimCampaign(any(CreateClaimCampaign.class)))
                .thenThrow(new NoSuchUserException("The user does not exist."));

        // Act
        var result = restTestClient.post()
                .uri("/api/v1/claim")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(ErrorResponse.class)
                .returnResult()
                .getResponseBody();

        // Assert
        assertNotNull(result);
        assertEquals(400, result.code());
        assertEquals("The user does not exist.", result.message());
    }

    /**
     * CL-P9 — {@code claimDate} is {@code @JsonIgnore}d and therefore server-assigned. A client must
     * not be able to backdate itself to the front of the priority ZSET, whose score is derived from
     * this field.
     */
    @Test
    void claimCampaign_with_client_supplied_claimDate_then_claimDate_is_server_assigned() {
        // Arrange
        var beforeRequest = LocalDateTime.now().minusMinutes(1);
        var body = """
                {"userId":1,"campaignId":2,"claimDate":"2020-01-01T00:00:00"}""";
        when(claimCampaignService.claimCampaign(any(CreateClaimCampaign.class)))
                .thenReturn(ClaimCampaignDTO.builder()
                        .userId(1L)
                        .campaignId(2L)
                        .status(ClaimCampaignStatus.SUBMITTED.name())
                        .claimDate(LocalDateTime.now())
                        .build());

        // Act
        restTestClient.post()
                .uri("/api/v1/claim")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange()
                .expectStatus().isOk();

        // Assert
        var captor = ArgumentCaptor.forClass(CreateClaimCampaign.class);
        verify(claimCampaignService, times(1)).claimCampaign(captor.capture());

        var claimDate = captor.getValue().getClaimDate();
        assertNotNull(claimDate);
        assertThat(claimDate).isAfter(beforeRequest);
        assertThat(claimDate.getYear()).isNotEqualTo(2020);
    }

    /**
     * CL-P10 — a null reason is omitted from the response by {@code @JsonInclude(NON_NULL)}.
     */
    @Test
    void claimCampaign_with_null_reason_then_reason_omitted_from_response() {
        // Arrange
        var body = Map.of("userId", 1, "campaignId", 2);
        when(claimCampaignService.claimCampaign(any(CreateClaimCampaign.class)))
                .thenReturn(ClaimCampaignDTO.builder()
                        .userId(1L)
                        .campaignId(2L)
                        .status(ClaimCampaignStatus.SUBMITTED.name())
                        .claimDate(LocalDateTime.now())
                        .reason(null)
                        .build());

        // Act
        var result = restTestClient.post()
                .uri("/api/v1/claim")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        // Assert
        assertNotNull(result);
        assertThat(result).contains("\"status\":\"SUBMITTED\"");
        assertThat(result).doesNotContain("reason");
    }

    /**
     * CL-P11 — a rejection reason is serialized when present.
     */
    @Test
    void claimCampaign_with_rejected_status_then_reason_present_in_response() {
        // Arrange
        var body = Map.of("userId", 1, "campaignId", 2);
        when(claimCampaignService.claimCampaign(any(CreateClaimCampaign.class)))
                .thenReturn(ClaimCampaignDTO.builder()
                        .userId(1L)
                        .campaignId(2L)
                        .status(ClaimCampaignStatus.REJECTED.name())
                        .claimDate(LocalDateTime.now())
                        .reason("The campaign has no available seats.")
                        .build());

        // Act
        var result = restTestClient.post()
                .uri("/api/v1/claim")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange()
                .expectStatus().isOk()
                .expectBody(ClaimCampaignDTO.class)
                .returnResult()
                .getResponseBody();

        // Assert
        assertNotNull(result);
        assertEquals("REJECTED", result.getStatus());
        assertEquals("The campaign has no available seats.", result.getReason());
    }

    // ---------------------------------------------------------------------
    // GET /api/v1/claim
    // ---------------------------------------------------------------------

    /**
     * CL-G1 — documents a known gap: {@code NoSuchClaimCampaignException} has no handler in
     * {@link ControllerAdvice}, so a missing claim surfaces as a 500 instead of a 404.
     * Asserts current behaviour; see docs/test_plan_controller.md "Known gaps".
     */
    @Test
    void getClaimCampaignStatus_from_non_existing_claim_campaign_then_currently_failed_with_status_500() {
        // Arrange
        when(claimCampaignRepository.findByUserIdAndCampaignId(any(), any())).thenReturn(Optional.empty());

        // Act
        var result = restTestClient.get()
                .uri("/api/v1/claim?userId={userId}&campaignId={campaignId}", 1L, 1L)
                .exchange()
                .expectStatus().is5xxServerError()
                .expectBody(ErrorResponse.class)
                .returnResult()
                .getResponseBody();

        // Assert
        assertNotNull(result);
        assertEquals(500, result.code());
        assertEquals("An unexpected error occurred on the server.", result.message());
        Mockito.verifyNoInteractions(mapperConfig);
    }

    /**
     * CL-G2 — documents a known gap: {@code MissingServletRequestParameterException} is unmapped.
     * Asserts current behaviour; see docs/test_plan_controller.md "Known gaps".
     */
    @Test
    void getClaimCampaignStatus_with_missing_userId_param_then_currently_failed_with_status_500() {
        // Act
        var result = restTestClient.get()
                .uri("/api/v1/claim?campaignId={campaignId}", 1L)
                .exchange()
                .expectStatus().is5xxServerError()
                .expectBody(ErrorResponse.class)
                .returnResult()
                .getResponseBody();

        // Assert
        assertNotNull(result);
        assertEquals(500, result.code());
        Mockito.verifyNoInteractions(claimCampaignRepository);
    }

    /**
     * CL-G3 — same gap as CL-G2, for the campaignId parameter.
     */
    @Test
    void getClaimCampaignStatus_with_missing_campaignId_param_then_currently_failed_with_status_500() {
        // Act
        var result = restTestClient.get()
                .uri("/api/v1/claim?userId={userId}", 1L)
                .exchange()
                .expectStatus().is5xxServerError()
                .expectBody(ErrorResponse.class)
                .returnResult()
                .getResponseBody();

        // Assert
        assertNotNull(result);
        assertEquals(500, result.code());
        Mockito.verifyNoInteractions(claimCampaignRepository);
    }

    /**
     * CL-G4 — documents a known gap: {@code MethodArgumentTypeMismatchException} is unmapped.
     * Asserts current behaviour; see docs/test_plan_controller.md "Known gaps".
     */
    @Test
    void getClaimCampaignStatus_with_non_numeric_userId_param_then_currently_failed_with_status_500() {
        // Act
        var result = restTestClient.get()
                .uri("/api/v1/claim?userId=abc&campaignId=1")
                .exchange()
                .expectStatus().is5xxServerError()
                .expectBody(ErrorResponse.class)
                .returnResult()
                .getResponseBody();

        // Assert
        assertNotNull(result);
        assertEquals(500, result.code());
        Mockito.verifyNoInteractions(claimCampaignRepository);
    }

    /**
     * CL-G5 — guards against the two {@code Long} arguments being transposed.
     */
    @Test
    void getClaimCampaignStatus_then_params_passed_to_repository_in_order() {
        // Arrange
        var claimCampaign = ClaimCampaign.builder()
                .userId(1L)
                .campaignId(2L)
                .status(ClaimCampaignStatus.APPROVED)
                .claimDate(LocalDateTime.now())
                .build();
        when(claimCampaignRepository.findByUserIdAndCampaignId(any(), any())).thenReturn(Optional.of(claimCampaign));
        when(mapperConfig.toDTO(any(ClaimCampaign.class)))
                .thenReturn(ClaimCampaignDTO.builder().userId(1L).campaignId(2L).status("APPROVED").build());

        // Act
        restTestClient.get()
                .uri("/api/v1/claim?userId={userId}&campaignId={campaignId}", 1L, 2L)
                .exchange()
                .expectStatus().isOk();

        // Assert
        verify(claimCampaignRepository, times(1)).findByUserIdAndCampaignId(eq(1L), eq(2L));
    }

    /**
     * CL-G6 — a settled REJECTED claim is returned as-is, reason included.
     */
    @Test
    void getClaimCampaignStatus_from_rejected_claim_campaign_then_success_with_status_200() {
        // Arrange
        var claimCampaign = ClaimCampaign.builder()
                .userId(1L)
                .campaignId(2L)
                .status(ClaimCampaignStatus.REJECTED)
                .reason("The campaign has no available seats.")
                .claimDate(LocalDateTime.now())
                .build();
        when(claimCampaignRepository.findByUserIdAndCampaignId(any(), any())).thenReturn(Optional.of(claimCampaign));
        when(mapperConfig.toDTO(any(ClaimCampaign.class)))
                .thenReturn(ClaimCampaignDTO.builder()
                        .userId(1L)
                        .campaignId(2L)
                        .status(ClaimCampaignStatus.REJECTED.name())
                        .reason("The campaign has no available seats.")
                        .claimDate(LocalDateTime.now())
                        .build());

        // Act
        var result = restTestClient.get()
                .uri("/api/v1/claim?userId={userId}&campaignId={campaignId}", 1L, 2L)
                .exchange()
                .expectStatus().isOk()
                .expectBody(ClaimCampaignDTO.class)
                .returnResult()
                .getResponseBody();

        // Assert
        assertNotNull(result);
        assertEquals("REJECTED", result.getStatus());
        assertEquals("The campaign has no available seats.", result.getReason());
    }

}
