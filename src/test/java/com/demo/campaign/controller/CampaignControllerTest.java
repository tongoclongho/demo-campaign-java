package com.demo.campaign.controller;

import com.demo.campaign.controller.admin.CampaignController;
import com.demo.campaign.dto.CampaignDTO;
import com.demo.campaign.dto.CreateCampaign;
import com.demo.campaign.dto.ErrorResponse;
import com.demo.campaign.service.CampaignService;
import com.demo.campaign.service.exception.ExistingCampaignCodeException;
import org.junit.jupiter.api.Assertions;
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
import org.springframework.test.web.servlet.client.assertj.RestTestClientResponse;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@WebMvcTest(CampaignController.class)
@AutoConfigureRestTestClient
class CampaignControllerTest {

    private static final ParameterizedTypeReference<List<CampaignDTO>> CAMPAIGN_LIST =
            new ParameterizedTypeReference<>() {};

    private static final ParameterizedTypeReference<Map<String, String>> FIELD_ERRORS =
            new ParameterizedTypeReference<>() {};

    @Autowired
    private RestTestClient restTestClient;

    @MockitoBean
    private CampaignService campaignService;

    @Test
    void getCampaign_then_success_with_status_200() {
        // Arrange
        CampaignDTO campaign = CampaignDTO.builder()
                .campaignId(1L)
                .campaignCode("TEST")
                .totalCapacity(100)
                .build();
        when(campaignService.getCampaigns()).thenReturn(List.of(campaign));

        // Act
        var result = restTestClient.get()
                .uri("/api/v1/campaign")
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<List<CampaignDTO>>() {})
                .returnResult()
                .getResponseBody();

        // Assert
        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals("TEST", result.get(0).getCampaignCode());
        assertEquals(100, result.get(0).getTotalCapacity());
        assertEquals(0, result.get(0).getUsedSeats());
    }

    /**
     * CC-G1 — no campaigns exist.
     */
    @Test
    void getCampaign_with_no_existing_campaign_then_success_with_empty_list_and_status_200() {
        // Arrange
        when(campaignService.getCampaigns()).thenReturn(List.of());

        // Act
        var result = restTestClient.get()
                .uri("/api/v1/campaign")
                .exchange()
                .expectStatus().isOk()
                .expectBody(CAMPAIGN_LIST)
                .returnResult()
                .getResponseBody();

        // Assert
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    /**
     * CC-G2 — usedSeats computed by the service is carried through to the response.
     */
    @Test
    void getCampaign_with_used_seats_then_used_seats_carried_through_with_status_200() {
        // Arrange
        CampaignDTO campaign = CampaignDTO.builder()
                .campaignId(1L)
                .campaignCode("TEST")
                .totalCapacity(5)
                .usedSeats(3)
                .build();
        when(campaignService.getCampaigns()).thenReturn(List.of(campaign));

        // Act
        var responseSpec = restTestClient.get()
                .uri("/api/v1/campaign")
                .exchange();

        var result = RestTestClientResponse.from(responseSpec);

        // Assert
        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$[0].totalCapacity").isEqualTo(5);
        assertThat(result).bodyJson().extractingPath("$[0].usedSeats").isEqualTo(3);
    }

    /**
     * CC-G3 — multiple campaigns are returned in the order the service produced them.
     */
    @Test
    void getCampaign_with_multiple_campaigns_then_order_preserved_with_status_200() {
        // Arrange
        CampaignDTO first = CampaignDTO.builder()
                .campaignId(1L)
                .campaignCode("FIRST")
                .totalCapacity(10)
                .usedSeats(1)
                .build();
        CampaignDTO second = CampaignDTO.builder()
                .campaignId(2L)
                .campaignCode("SECOND")
                .totalCapacity(20)
                .usedSeats(2)
                .build();
        when(campaignService.getCampaigns()).thenReturn(List.of(first, second));

        // Act
        var result = restTestClient.get()
                .uri("/api/v1/campaign")
                .exchange()
                .expectStatus().isOk()
                .expectBody(CAMPAIGN_LIST)
                .returnResult()
                .getResponseBody();

        // Assert
        assertNotNull(result);
        assertEquals(2, result.size());
        assertEquals("FIRST", result.get(0).getCampaignCode());
        assertEquals("SECOND", result.get(1).getCampaignCode());
    }

    /**
     * CC-G4 — an unmapped runtime exception falls to the catch-all handler, which must not leak the
     * cause to the client.
     */
    @Test
    void getCampaign_when_service_throws_unmapped_exception_then_failed_with_status_500() {
        // Arrange
        when(campaignService.getCampaigns()).thenThrow(new RuntimeException("connection pool exhausted at db-01"));

        // Act
        var result = restTestClient.get()
                .uri("/api/v1/campaign")
                .exchange()
                .expectStatus().is5xxServerError()
                .expectBody(ErrorResponse.class)
                .returnResult()
                .getResponseBody();

        // Assert
        assertNotNull(result);
        assertEquals(500, result.code());
        assertEquals("An unexpected error occurred on the server.", result.message());
    }

    @Test
    void createCampaign_with_correct_request_body_then_success_with_status_201() {
        // Arrange
        var body = new CreateCampaign("TEST", 100);

        CampaignDTO campaign = CampaignDTO.builder()
                .campaignId(1L)
                .campaignCode("TEST")
                .totalCapacity(100)
                .build();
        when(campaignService.createCampaign(any(CreateCampaign.class))).thenReturn(campaign);

        // Act
        var result = restTestClient.post()
                .uri("/api/v1/campaign")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange()
                .expectStatus()
                .isCreated()
                .expectBody(CampaignDTO.class)
                .returnResult()
                .getResponseBody();

        // Assert
        Mockito.verify(campaignService, times(1)).createCampaign(any());
        Assertions.assertNotNull(result);
        Assertions.assertEquals("TEST", result.getCampaignCode());
        Assertions.assertEquals(100, result.getTotalCapacity());
        Assertions.assertEquals(0, result.getUsedSeats());
    }

    @Test
    void createCampaign_with_missing_campaignCode_then_failed_with_status_400() {
        // Arrange
        var body = Map.of("totalCapacity", 100);

        // Act
        var responseSpec = restTestClient.post()
                .uri("/api/v1/campaign")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange();

        var result = RestTestClientResponse.from(responseSpec);

        // Assert

        assertThat(result).hasStatus4xxClientError();
        assertThat(result).bodyJson().extractingPath("$.campaignCode").asString().isEqualTo("must not be blank");
    }

    /**
     * CC-P1 — {@code totalCapacity = 0} violates {@code @Min(1)}.
     */
    @Test
    void createCampaign_with_zero_totalCapacity_then_failed_with_status_400() {
        // Arrange
        var body = Map.of("campaignCode", "TEST", "totalCapacity", 0);

        // Act
        var result = restTestClient.post()
                .uri("/api/v1/campaign")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(FIELD_ERRORS)
                .returnResult()
                .getResponseBody();

        // Assert
        assertNotNull(result);
        assertTrue(result.containsKey("totalCapacity"), () -> "expected a totalCapacity violation but got " + result);
        Mockito.verifyNoInteractions(campaignService);
    }

    /**
     * CC-P2 — a negative {@code totalCapacity} violates {@code @Min(1)}.
     */
    @Test
    void createCampaign_with_negative_totalCapacity_then_failed_with_status_400() {
        // Arrange
        var body = Map.of("campaignCode", "TEST", "totalCapacity", -1);

        // Act
        var result = restTestClient.post()
                .uri("/api/v1/campaign")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(FIELD_ERRORS)
                .returnResult()
                .getResponseBody();

        // Assert
        assertNotNull(result);
        assertTrue(result.containsKey("totalCapacity"), () -> "expected a totalCapacity violation but got " + result);
        Mockito.verifyNoInteractions(campaignService);
    }

    /**
     * CC-P3 — a whitespace-only campaignCode is blank, not merely empty.
     */
    @Test
    void createCampaign_with_blank_campaignCode_then_failed_with_status_400() {
        // Arrange
        var body = Map.of("campaignCode", "   ", "totalCapacity", 100);

        // Act
        var responseSpec = restTestClient.post()
                .uri("/api/v1/campaign")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange();

        var result = RestTestClientResponse.from(responseSpec);

        // Assert
        assertThat(result).hasStatus4xxClientError();
        assertThat(result).bodyJson().extractingPath("$.campaignCode").asString().isEqualTo("must not be blank");
        Mockito.verifyNoInteractions(campaignService);
    }

    /**
     * CC-P4 — a duplicate campaign code is rejected by the service and mapped to a 400 ErrorResponse.
     */
    @Test
    void createCampaign_with_duplicated_campaignCode_then_failed_with_status_400() {
        // Arrange
        var body = new CreateCampaign("TEST", 100);
        when(campaignService.createCampaign(any(CreateCampaign.class)))
                .thenThrow(new ExistingCampaignCodeException("The campaign code TEST is already existing."));

        // Act
        var result = restTestClient.post()
                .uri("/api/v1/campaign")
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
        assertThat(result.message()).contains("TEST");
    }

    /**
     * CC-P5 — the controller must hand the deserialized body to the service untouched.
     */
    @Test
    void createCampaign_with_correct_request_body_then_request_reaches_service_unchanged() {
        // Arrange
        var body = Map.of("campaignCode", "  Spaced Code  ", "totalCapacity", 7);
        when(campaignService.createCampaign(any(CreateCampaign.class)))
                .thenReturn(CampaignDTO.builder().campaignId(1L).campaignCode("  Spaced Code  ").totalCapacity(7).build());

        // Act
        restTestClient.post()
                .uri("/api/v1/campaign")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange()
                .expectStatus().isCreated();

        // Assert
        var captor = ArgumentCaptor.forClass(CreateCampaign.class);
        verify(campaignService, times(1)).createCampaign(captor.capture());
        assertEquals("  Spaced Code  ", captor.getValue().campaignCode());
        assertEquals(7, captor.getValue().totalCapacity());
    }

    /**
     * CC-P6 — documents a known gap: {@code @Min} treats {@code null} as valid and there is no
     * {@code @NotNull}, so an omitted totalCapacity passes validation and reaches the service.
     * Asserts current behaviour; see docs/test_plan_controller.md "Known gaps".
     */
    @Test
    void createCampaign_with_missing_totalCapacity_then_currently_delegates_to_service() {
        // Arrange
        var body = new HashMap<String, Object>();
        body.put("campaignCode", "TEST");
        when(campaignService.createCampaign(any(CreateCampaign.class)))
                .thenReturn(CampaignDTO.builder().campaignId(1L).campaignCode("TEST").build());

        // Act
        restTestClient.post()
                .uri("/api/v1/campaign")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange()
                .expectStatus().isCreated();

        // Assert
        var captor = ArgumentCaptor.forClass(CreateCampaign.class);
        verify(campaignService, times(1)).createCampaign(captor.capture());
        Assertions.assertNull(captor.getValue().totalCapacity());
    }

    /**
     * CC-P7 — documents a known gap: {@code HttpMessageNotReadableException} has no handler, so
     * malformed JSON surfaces as a 500 instead of a 400.
     * Asserts current behaviour; see docs/test_plan_controller.md "Known gaps".
     */
    @Test
    void createCampaign_with_malformed_json_then_currently_failed_with_status_500() {
        // Act
        var result = restTestClient.post()
                .uri("/api/v1/campaign")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{")
                .exchange()
                .expectStatus().is5xxServerError()
                .expectBody(ErrorResponse.class)
                .returnResult()
                .getResponseBody();

        // Assert
        assertNotNull(result);
        assertEquals(500, result.code());
        assertEquals("An unexpected error occurred on the server.", result.message());
        Mockito.verifyNoInteractions(campaignService);
    }

}
