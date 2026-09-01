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


    @Test
    void getClaimCampaignStatus_from_non_existing_claim_campaign_then_currently_failed_with_status_400() {
        // Arrange
        when(claimCampaignRepository.findByUserIdAndCampaignId(any(), any())).thenReturn(Optional.empty());

        // Act
        var result = restTestClient.get()
                .uri("/api/v1/claim?userId={userId}&campaignId={campaignId}", 1L, 1L)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(ErrorResponse.class)
                .returnResult()
                .getResponseBody();

        // Assert
        assertNotNull(result);
        assertEquals(400, result.code());
        assertEquals("The user has not claimed the campaign yet.", result.message());
        Mockito.verifyNoInteractions(mapperConfig);
    }

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
