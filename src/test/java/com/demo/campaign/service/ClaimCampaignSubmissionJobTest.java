package com.demo.campaign.service;

import com.demo.campaign.dto.ClaimSubmissionDTO;
import com.demo.campaign.entity.ClaimCampaign;
import com.demo.campaign.entity.ClaimCampaignStatus;
import com.demo.campaign.repository.CampaignRepository;
import com.demo.campaign.repository.CampaignUserRepository;
import com.demo.campaign.repository.ClaimCampaignRepository;
import com.demo.campaign.service.mapper.MapperConfig;
import com.demo.campaign.util.AppConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;


class ClaimCampaignSubmissionJobTest extends AbstractRedisTest {

    private static final Long CAMPAIGN_ID = 7L;

    private ClaimCampaignRepository claimCampaignRepository;
    private RedisServiceHelper redisServiceHelper;
    private ClaimCampaignServiceImpl claimCampaignService;

    /** Rows the mocked repository will serve, keyed {@code userId#campaignId}. */
    private final Map<String, ClaimCampaign> rows = new HashMap<>();

    @BeforeEach
    void setUp() {
        rows.clear();
        claimCampaignRepository = mock(ClaimCampaignRepository.class);
        redisServiceHelper = new RedisServiceHelper(redisTemplate);

        when(claimCampaignRepository.findByUserIdAndCampaignId(any(), any()))
                .thenAnswer(invocation -> Optional.ofNullable(
                        rows.get(invocation.getArgument(0) + "#" + invocation.getArgument(1))));

        claimCampaignService = new ClaimCampaignServiceImpl(redisTemplate, claimCampaignRepository,
                mock(CampaignRepository.class), mock(CampaignUserRepository.class), mock(MapperConfig.class),
                redisServiceHelper);
        ReflectionTestUtils.setField(claimCampaignService, "batchSize", 100L);
    }


    private static String zsetKey(Long campaignId) {
        return AppConstants.CLAIM_CAMPAIGN_PRIORITY_ZSET + campaignId;
    }

    private void givenSeats(Long campaignId, int seats) {
        redisTemplate.opsForValue().set(AppConstants.CAMPAIGN_AVAILABLE_SEATS + campaignId, String.valueOf(seats));
    }

    private void givenQueuedClaim(Long campaignId, Long userId, int points, LocalDateTime claimDate) {
        givenClaimRow(campaignId, userId, claimDate);
        redisServiceHelper.sendClaimToPriorityQueue(ClaimSubmissionDTO.builder()
                .campaignId(campaignId)
                .userId(userId)
                .userPoints(points)
                .claimDate(claimDate)
                .build());
    }

    private void givenQueuedClaimWithoutRow(Long campaignId, Long userId, int points, LocalDateTime claimDate) {
        redisServiceHelper.sendClaimToPriorityQueue(ClaimSubmissionDTO.builder()
                .campaignId(campaignId)
                .userId(userId)
                .userPoints(points)
                .claimDate(claimDate)
                .build());
    }

    private void givenClaimRow(Long campaignId, Long userId, LocalDateTime claimDate) {
        rows.put(userId + "#" + campaignId, ClaimCampaign.builder()
                .campaignId(campaignId)
                .userId(userId)
                .claimDate(claimDate)
                .status(ClaimCampaignStatus.SUBMITTED)
                .build());
    }

    private void givenRawQueuedValue(Long campaignId, String value, LocalDateTime claimDate) {
        redisTemplate.opsForZSet().add(zsetKey(campaignId), value,
                Long.MAX_VALUE - claimDate.toInstant(ZoneOffset.UTC).toEpochMilli());
    }

    private Map<Long, ClaimCampaign> settledByUserId() {
        var captor = ArgumentCaptor.forClass(ClaimCampaign.class);
        verify(claimCampaignRepository, atLeastOnce()).save(captor.capture());
        return captor.getAllValues().stream()
                .collect(Collectors.toMap(ClaimCampaign::getUserId, Function.identity()));
    }


    @Test
    void executeClaimCampaignSubmission_with_nothing_queued_then_no_repository_interaction() {
        // Act
        claimCampaignService.executeClaimCampaignSubmission();

        // Assert
        assertTrue(redisServiceHelper.getCampaignIdsForClaimCheck().isEmpty());
        verifyNoInteractions(claimCampaignRepository);
    }


    @Test
    void processClaimCheck_with_one_seat_then_earliest_claim_wins() {
        // Arrange — enqueue order 1,2,3 but claim dates t3 < t1 < t2
        var t1 = LocalDateTime.of(2026, 8, 31, 10, 0);
        var t2 = LocalDateTime.of(2026, 8, 31, 11, 0);
        var t3 = LocalDateTime.of(2026, 8, 31, 9, 0);
        givenSeats(CAMPAIGN_ID, 1);
        givenQueuedClaim(CAMPAIGN_ID, 1L, 10, t1);
        givenQueuedClaim(CAMPAIGN_ID, 2L, 10, t2);
        givenQueuedClaim(CAMPAIGN_ID, 3L, 10, t3);

        // Act
        int processed = claimCampaignService.processClaimCheck(CAMPAIGN_ID);

        // Assert
        assertEquals(3, processed);
        var settled = settledByUserId();
        assertEquals(ClaimCampaignStatus.APPROVED, settled.get(3L).getStatus());
        assertEquals(ClaimCampaignStatus.REJECTED, settled.get(1L).getStatus());
        assertEquals(ClaimCampaignStatus.REJECTED, settled.get(2L).getStatus());
    }


    @Test
    void processClaimCheck_with_same_claim_date_then_higher_points_wins() {
        // Arrange
        givenSeats(CAMPAIGN_ID, 1);
        givenQueuedClaim(CAMPAIGN_ID, 1L, 10, LocalDateTime.of(2026, 8, 31, 10, 0));
        givenQueuedClaim(CAMPAIGN_ID, 2L, 9999, LocalDateTime.of(2026, 8, 31, 10, 0));

        // Act
        claimCampaignService.processClaimCheck(CAMPAIGN_ID);

        // Assert
        var settled = settledByUserId();
        assertEquals(ClaimCampaignStatus.REJECTED, settled.get(1L).getStatus());
        assertEquals(ClaimCampaignStatus.APPROVED, settled.get(2L).getStatus());
    }

    @Test
    void processClaimCheck_with_higher_points_on_later_claim_then_earlier_claim_still_wins() {
        // Arrange
        givenSeats(CAMPAIGN_ID, 1);
        givenQueuedClaim(CAMPAIGN_ID, 1L, 10, LocalDateTime.of(2026, 8, 31, 10, 0));
        givenQueuedClaim(CAMPAIGN_ID, 2L, 9999, LocalDateTime.of(2026, 8, 31, 10, 30));

        // Act
        claimCampaignService.processClaimCheck(CAMPAIGN_ID);

        // Assert
        var settled = settledByUserId();
        assertEquals(ClaimCampaignStatus.APPROVED, settled.get(1L).getStatus());
        assertEquals(ClaimCampaignStatus.REJECTED, settled.get(2L).getStatus());
    }


    @Test
    void processClaimCheck_with_queue_larger_than_batch_size_then_queue_fully_drained() {
        // Arrange
        ReflectionTestUtils.setField(claimCampaignService, "batchSize", 2L);
        givenSeats(CAMPAIGN_ID, 5);
        for (long userId = 1; userId <= 5; userId++) {
            givenQueuedClaim(CAMPAIGN_ID, userId, 10, LocalDateTime.of(2026, 8, 31, 10, (int) userId));
        }

        // Act
        int processed = claimCampaignService.processClaimCheck(CAMPAIGN_ID);

        // Assert
        assertEquals(5, processed);
        assertEquals(0L, redisTemplate.opsForZSet().zCard(zsetKey(CAMPAIGN_ID)));
        verify(claimCampaignRepository, times(5)).save(any(ClaimCampaign.class));
    }


    @Test
    void executeClaimCampaignSubmission_then_campaign_id_set_is_drained() {
        // Arrange
        givenSeats(100L, 1);
        givenSeats(200L, 1);
        givenQueuedClaim(100L, 1L, 10, LocalDateTime.of(2026, 8, 31, 10, 0));
        givenQueuedClaim(200L, 2L, 10, LocalDateTime.of(2026, 8, 31, 10, 0));

        // Act
        claimCampaignService.executeClaimCampaignSubmission();

        // Assert
        assertEquals(0L, redisTemplate.opsForSet().size(AppConstants.CLAIM_CAMPAIGN_PRIORITY_IDS_SET));
        var settled = settledByUserId();
        assertEquals(ClaimCampaignStatus.APPROVED, settled.get(1L).getStatus());
        assertEquals(ClaimCampaignStatus.APPROVED, settled.get(2L).getStatus());
    }


    @Test
    void executeClaimCampaignSubmission_with_one_failing_campaign_then_other_campaign_still_settled() {
        // Arrange — campaign 100 has a queued claim with no backing row (CL-P5 conditions)
        givenSeats(100L, 1);
        givenSeats(200L, 1);
        givenQueuedClaimWithoutRow(100L, 11L, 10, LocalDateTime.of(2026, 8, 31, 10, 0));
        givenQueuedClaim(200L, 22L, 10, LocalDateTime.of(2026, 8, 31, 10, 0));

        // Act
        claimCampaignService.executeClaimCampaignSubmission();

        // Assert
        var settled = settledByUserId();
        assertEquals(1, settled.size());
        assertEquals(ClaimCampaignStatus.APPROVED, settled.get(22L).getStatus());
    }


    @Test
    void processClaimCheck_with_encoded_value_then_user_id_extracted_from_suffix() {
        // Arrange
        givenSeats(CAMPAIGN_ID, 1);
        givenClaimRow(CAMPAIGN_ID, 42L, LocalDateTime.of(2026, 8, 31, 10, 0));
        givenRawQueuedValue(CAMPAIGN_ID, "100#42", LocalDateTime.of(2026, 8, 31, 10, 0));

        // Act
        claimCampaignService.processClaimCheck(CAMPAIGN_ID);

        // Assert — 42 is the user id, not 100
        verify(claimCampaignRepository).findByUserIdAndCampaignId(42L, CAMPAIGN_ID);
        assertEquals(ClaimCampaignStatus.APPROVED, settledByUserId().get(42L).getStatus());
    }


    @Test
    void processClaimCheck_with_malformed_value_then_batch_continues() {
        // Arrange
        givenSeats(CAMPAIGN_ID, 5);
        rows.put("null#" + CAMPAIGN_ID, ClaimCampaign.builder()
                .campaignId(CAMPAIGN_ID)
                .status(ClaimCampaignStatus.SUBMITTED)
                .build());
        givenRawQueuedValue(CAMPAIGN_ID, "malformed", LocalDateTime.of(2026, 8, 31, 10, 0));
        givenClaimRow(CAMPAIGN_ID, 43L, LocalDateTime.of(2026, 8, 31, 11, 0));
        givenRawQueuedValue(CAMPAIGN_ID, "100#43", LocalDateTime.of(2026, 8, 31, 11, 0));

        // Act
        int processed = claimCampaignService.processClaimCheck(CAMPAIGN_ID);

        // Assert — both entries consumed, and the well-formed claim still settled
        assertEquals(2, processed);
        assertEquals(0L, redisTemplate.opsForZSet().zCard(zsetKey(CAMPAIGN_ID)));

        var captor = ArgumentCaptor.forClass(ClaimCampaign.class);
        verify(claimCampaignRepository, times(2)).save(captor.capture());
        var validClaim = captor.getAllValues().stream()
                .filter(claim -> Long.valueOf(43L).equals(claim.getUserId()))
                .findFirst()
                .orElseThrow();
        assertEquals(ClaimCampaignStatus.APPROVED, validClaim.getStatus());
    }


    @Test
    void processClaimCheck_with_empty_queue_then_returns_zero() {
        // Act
        int processed = claimCampaignService.processClaimCheck(CAMPAIGN_ID);

        // Assert
        assertEquals(0, processed);
        verifyNoInteractions(claimCampaignRepository);
    }


    @Test
    void executeClaimCampaignSubmission_then_processes_every_registered_campaign() {
        // Arrange
        List.of(100L, 200L, 300L).forEach(campaignId -> {
            givenSeats(campaignId, 1);
            givenQueuedClaim(campaignId, campaignId, 10, LocalDateTime.of(2026, 8, 31, 10, 0));
        });

        // Act
        claimCampaignService.executeClaimCampaignSubmission();

        // Assert
        verify(claimCampaignRepository, times(3)).save(any(ClaimCampaign.class));
    }
}
