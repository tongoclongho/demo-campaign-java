package com.demo.campaign.service;

import com.demo.campaign.dto.CampaignDTO;
import com.demo.campaign.dto.CampaignUsedSeats;
import com.demo.campaign.dto.CreateCampaign;
import com.demo.campaign.entity.Campaign;
import com.demo.campaign.repository.CampaignRepository;
import com.demo.campaign.repository.ClaimCampaignRepository;
import com.demo.campaign.service.exception.ExistingCampaignCodeException;
import com.demo.campaign.service.mapper.MapperConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CampaignServiceImplTest {

    @Mock
    private CampaignRepository campaignRepository;

    @Mock
    private ClaimCampaignRepository claimCampaignRepository;

    @Mock
    private MapperConfig mapper;

    private CampaignServiceImpl campaignService;

    @BeforeEach
    void setUp() {
        campaignService = new CampaignServiceImpl(campaignRepository, claimCampaignRepository, mapper);
    }

    private static Campaign campaign(Long id, String code, int totalCapacity) {
        var campaign = Campaign.builder().code(code).totalCapacity(totalCapacity).build();
        campaign.setId(id);
        return campaign;
    }


    @Test
    void createCampaign_with_untrimmed_lowercase_code_then_code_normalised() {
        // Arrange
        var request = new CreateCampaign("  black_friday  ", 5);

        // Act
        campaignService.createCampaign(request);

        // Assert
        var captor = ArgumentCaptor.forClass(Campaign.class);
        verify(campaignRepository).save(captor.capture());
        assertEquals("BLACK_FRIDAY", captor.getValue().getCode());
    }


    @Test
    void createCampaign_with_existing_code_then_throws_and_nothing_saved() {
        // Arrange
        when(campaignRepository.existingCampaignCode("BLACK_FRIDAY")).thenReturn(1);

        // Act / Assert
        var ex = assertThrows(ExistingCampaignCodeException.class,
                () -> campaignService.createCampaign(new CreateCampaign("BLACK_FRIDAY", 5)));

        assertTrue(ex.getMessage().contains("BLACK_FRIDAY"));
        verify(campaignRepository, never()).save(any());
        verify(mapper, never()).toDTO(any(Campaign.class));
    }


    @Test
    void createCampaign_then_duplicate_check_uses_normalised_code() {
        // Act
        campaignService.createCampaign(new CreateCampaign("black_friday", 5));

        // Assert
        verify(campaignRepository).existingCampaignCode("BLACK_FRIDAY");
    }


    @Test
    void createCampaign_then_total_capacity_carried_through() {
        // Act
        campaignService.createCampaign(new CreateCampaign("TEST", 5));

        // Assert
        var captor = ArgumentCaptor.forClass(Campaign.class);
        verify(campaignRepository).save(captor.capture());
        assertEquals(5, captor.getValue().getTotalCapacity());
    }


    @Test
    void createCampaign_then_returns_dto_mapped_from_saved_entity() {
        // Arrange
        var saved = campaign(1L, "TEST", 5);
        var dto = CampaignDTO.builder().campaignId(1L).campaignCode("TEST").totalCapacity(5).build();
        when(campaignRepository.save(any(Campaign.class))).thenReturn(saved);
        when(mapper.toDTO(saved)).thenReturn(dto);

        // Act
        var result = campaignService.createCampaign(new CreateCampaign("TEST", 5));

        // Assert
        assertSame(dto, result);
        verify(mapper).toDTO(saved);
    }


    private Map<Long, CampaignDTO> byCampaignId(Collection<CampaignDTO> dtos) {
        var map = new HashMap<Long, CampaignDTO>();
        dtos.forEach(dto -> map.put(dto.getCampaignId(), dto));
        return map;
    }


    @Test
    void getCampaigns_with_no_approved_claims_then_used_seats_is_zero() {
        // Arrange
        var campaign = campaign(1L, "TEST", 5);
        when(campaignRepository.findAll()).thenReturn(List.of(campaign));
        when(mapper.toDTO(campaign))
                .thenReturn(CampaignDTO.builder().campaignId(1L).campaignCode("TEST").totalCapacity(5).build());
        when(claimCampaignRepository.aggregateCampaignUsedSeats(anySet())).thenReturn(List.of());

        // Act
        var result = campaignService.getCampaigns();

        // Assert
        assertEquals(1, result.size());
        assertEquals(0, result.iterator().next().getUsedSeats());
    }


    @Test
    void getCampaigns_with_used_seats_then_merged_onto_matching_campaign() {
        // Arrange
        var first = campaign(1L, "FIRST", 10);
        var second = campaign(2L, "SECOND", 10);
        when(campaignRepository.findAll()).thenReturn(List.of(first, second));
        when(mapper.toDTO(first))
                .thenReturn(CampaignDTO.builder().campaignId(1L).campaignCode("FIRST").totalCapacity(10).build());
        when(mapper.toDTO(second))
                .thenReturn(CampaignDTO.builder().campaignId(2L).campaignCode("SECOND").totalCapacity(10).build());
        when(claimCampaignRepository.aggregateCampaignUsedSeats(anySet()))
                .thenReturn(List.of(new CampaignUsedSeats(2L, 3L)));

        // Act
        var result = byCampaignId(campaignService.getCampaigns());

        // Assert
        assertEquals(3, result.get(2L).getUsedSeats());
        assertEquals(0, result.get(1L).getUsedSeats());
    }


    @Test
    void getCampaigns_with_null_used_seats_in_projection_then_stays_zero() {
        // Arrange
        var campaign = campaign(1L, "TEST", 5);
        when(campaignRepository.findAll()).thenReturn(List.of(campaign));
        when(mapper.toDTO(campaign))
                .thenReturn(CampaignDTO.builder().campaignId(1L).campaignCode("TEST").totalCapacity(5).build());
        when(claimCampaignRepository.aggregateCampaignUsedSeats(anySet()))
                .thenReturn(List.of(new CampaignUsedSeats(1L, null)));

        // Act
        var result = campaignService.getCampaigns();

        // Assert
        assertEquals(0, result.iterator().next().getUsedSeats());
    }


    @Test
    void getCampaigns_with_unknown_campaign_id_in_projection_then_ignored() {
        // Arrange
        var campaign = campaign(1L, "TEST", 5);
        when(campaignRepository.findAll()).thenReturn(List.of(campaign));
        when(mapper.toDTO(campaign))
                .thenReturn(CampaignDTO.builder().campaignId(1L).campaignCode("TEST").totalCapacity(5).build());
        when(claimCampaignRepository.aggregateCampaignUsedSeats(anySet()))
                .thenReturn(List.of(new CampaignUsedSeats(999L, 3L)));

        // Act
        var result = campaignService.getCampaigns();

        // Assert
        assertEquals(1, result.size());
        assertEquals(0, result.iterator().next().getUsedSeats());
    }


    @Test
    void getCampaigns_with_no_campaigns_then_aggregate_called_with_empty_id_set() {
        // Arrange
        when(campaignRepository.findAll()).thenReturn(List.of());
        when(claimCampaignRepository.aggregateCampaignUsedSeats(anySet())).thenReturn(List.of());

        // Act
        var result = campaignService.getCampaigns();

        // Assert
        assertTrue(result.isEmpty());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Set<Long>> captor = ArgumentCaptor.forClass(Set.class);
        verify(claimCampaignRepository).aggregateCampaignUsedSeats(captor.capture());
        assertTrue(captor.getValue().isEmpty());
    }


    @Test
    void createCampaign_with_null_total_capacity_then_null_reaches_the_entity() {
        // Act
        campaignService.createCampaign(new CreateCampaign("TEST", null));

        // Assert
        var captor = ArgumentCaptor.forClass(Campaign.class);
        verify(campaignRepository).save(captor.capture());
        assertNull(captor.getValue().getTotalCapacity());
    }
}
