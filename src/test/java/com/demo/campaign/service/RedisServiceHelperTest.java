package com.demo.campaign.service;

import com.demo.campaign.dto.ClaimSubmissionDTO;
import com.demo.campaign.service.exception.CampaignClaimedAlreadyException;
import com.demo.campaign.service.exception.CampaignNoAvailableSeatsException;
import com.demo.campaign.util.AppConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link RedisServiceHelper} against a real Redis container — mocking StringRedisTemplate here would
 * assert our assumptions rather than Redis's behaviour, which is where the bugs live.
 * See docs/test_plan_service.md (RS-*).
 */
class RedisServiceHelperTest extends AbstractRedisTest {

    private static final Long CAMPAIGN_ID = 7L;

    private RedisServiceHelper redisServiceHelper;

    @BeforeEach
    void setUp() {
        redisServiceHelper = new RedisServiceHelper(redisTemplate);
    }

    private static String membersKey() {
        return AppConstants.CAMPAIGN_MEMBERS_SET + CAMPAIGN_ID;
    }

    private static String seatsKey() {
        return AppConstants.CAMPAIGN_AVAILABLE_SEATS + CAMPAIGN_ID;
    }

    private static String zsetKey() {
        return AppConstants.CLAIM_CAMPAIGN_PRIORITY_ZSET + CAMPAIGN_ID;
    }

    private static ClaimSubmissionDTO submission(Long userId, int points, LocalDateTime claimDate) {
        return ClaimSubmissionDTO.builder()
                .campaignId(CAMPAIGN_ID)
                .userId(userId)
                .userPoints(points)
                .claimDate(claimDate)
                .build();
    }

    // ---------------------------------------------------------------------
    // RS-1 requireUserNotInCampaignMembers
    // ---------------------------------------------------------------------

    @Test
    void requireUserNotInCampaignMembers_with_existing_member_then_throws() {
        // Arrange
        redisTemplate.opsForSet().add(membersKey(), "1");

        // Act / Assert
        assertThrows(CampaignClaimedAlreadyException.class,
                () -> redisServiceHelper.requireUserNotInCampaignMembers(1L, CAMPAIGN_ID));
    }

    @Test
    void requireUserNotInCampaignMembers_with_other_member_then_passes() {
        // Arrange
        redisTemplate.opsForSet().add(membersKey(), "2");

        // Act / Assert
        assertDoesNotThrow(() -> redisServiceHelper.requireUserNotInCampaignMembers(1L, CAMPAIGN_ID));
    }

    @Test
    void requireUserNotInCampaignMembers_with_missing_key_then_passes() {
        // Act / Assert — no key at all must not NPE on the unboxed isMember result
        assertDoesNotThrow(() -> redisServiceHelper.requireUserNotInCampaignMembers(1L, CAMPAIGN_ID));
    }

    // ---------------------------------------------------------------------
    // RS-2 requireCampaignAvailableSeats
    // ---------------------------------------------------------------------

    @Test
    void requireCampaignAvailableSeats_with_missing_key_then_throws() {
        assertThrows(CampaignNoAvailableSeatsException.class,
                () -> redisServiceHelper.requireCampaignAvailableSeats(CAMPAIGN_ID));
    }

    @Test
    void requireCampaignAvailableSeats_with_zero_seats_then_throws() {
        // Arrange
        redisTemplate.opsForValue().set(seatsKey(), "0");

        // Act / Assert
        assertThrows(CampaignNoAvailableSeatsException.class,
                () -> redisServiceHelper.requireCampaignAvailableSeats(CAMPAIGN_ID));
    }

    @Test
    void requireCampaignAvailableSeats_with_negative_seats_then_throws() {
        // Arrange — the counter can go negative, see RS-5
        redisTemplate.opsForValue().set(seatsKey(), "-1");

        // Act / Assert
        assertThrows(CampaignNoAvailableSeatsException.class,
                () -> redisServiceHelper.requireCampaignAvailableSeats(CAMPAIGN_ID));
    }

    @Test
    void requireCampaignAvailableSeats_with_positive_seats_then_passes() {
        // Arrange
        redisTemplate.opsForValue().set(seatsKey(), "1");

        // Act / Assert
        assertDoesNotThrow(() -> redisServiceHelper.requireCampaignAvailableSeats(CAMPAIGN_ID));
    }

    // ---------------------------------------------------------------------
    // RS-3 / RS-4 sendClaimToPriorityQueue
    // ---------------------------------------------------------------------

    /**
     * RS-3 — both keys are written: the per-campaign ZSET and the id-set the job scans.
     */
    @Test
    void sendClaimToPriorityQueue_then_writes_zset_member_and_registers_campaign_id() {
        // Act
        redisServiceHelper.sendClaimToPriorityQueue(submission(42L, 100, LocalDateTime.of(2026, 8, 31, 10, 0)));

        // Assert
        assertEquals(1L, redisTemplate.opsForZSet().zCard(zsetKey()));
        assertTrue(redisTemplate.opsForZSet().rank(zsetKey(), "100#42") != null,
                "expected the ZSET member to be {loyaltyPoints}#{userId}");
        assertTrue(redisTemplate.opsForSet().isMember(AppConstants.CLAIM_CAMPAIGN_PRIORITY_IDS_SET,
                CAMPAIGN_ID.toString()));
    }

    /**
     * RS-4 — the score is inverted ({@code Long.MAX_VALUE - epochMilli}) so that {@code ZPOPMAX}
     * yields the earliest claim first. This is the fairness property of the whole pipeline.
     */
    @Test
    void sendClaimToPriorityQueue_with_two_claims_then_earlier_claim_scores_higher() {
        // Arrange
        var earlier = LocalDateTime.of(2026, 8, 31, 10, 0);
        var later = LocalDateTime.of(2026, 8, 31, 11, 0);

        // Act
        redisServiceHelper.sendClaimToPriorityQueue(submission(1L, 10, earlier));
        redisServiceHelper.sendClaimToPriorityQueue(submission(2L, 10, later));

        // Assert
        var earlierScore = redisTemplate.opsForZSet().score(zsetKey(), "10#1");
        var laterScore = redisTemplate.opsForZSet().score(zsetKey(), "10#2");
        assertThat(earlierScore).isGreaterThan(laterScore);

        var popped = redisTemplate.opsForZSet().popMax(zsetKey());
        assertEquals("10#1", popped.getValue());
    }

    // ---------------------------------------------------------------------
    // RS-5 reserveCampaignSeat
    // ---------------------------------------------------------------------

    @Test
    void reserveCampaignSeat_with_one_seat_left_then_returns_zero() {
        // Arrange
        redisTemplate.opsForValue().set(seatsKey(), "1");

        // Act
        Optional<Long> result = redisServiceHelper.reserveCampaignSeat(CAMPAIGN_ID);

        // Assert — DECR returns the post-decrement value
        assertEquals(Optional.of(0L), result);
        assertEquals("0", redisTemplate.opsForValue().get(seatsKey()));
    }

    /**
     * RS-5 — documents the overshoot: the DECR happens before the result is checked, so a failed
     * reservation still leaves the counter one lower. Only the 60s refresh corrects it.
     * See docs/test_plan_service.md "Known gaps" (3).
     */
    @Test
    void reserveCampaignSeat_with_no_seats_left_then_empty_and_counter_overshoots() {
        // Arrange
        redisTemplate.opsForValue().set(seatsKey(), "0");

        // Act
        Optional<Long> result = redisServiceHelper.reserveCampaignSeat(CAMPAIGN_ID);

        // Assert
        assertTrue(result.isEmpty());
        assertEquals("-1", redisTemplate.opsForValue().get(seatsKey()));
    }

    // ---------------------------------------------------------------------
    // RS-6 addUserToCampaignMembers
    // ---------------------------------------------------------------------

    @Test
    void addUserToCampaignMembers_then_first_call_wins_and_duplicate_is_empty() {
        // Act
        var first = redisServiceHelper.addUserToCampaignMembers(CAMPAIGN_ID, 1L);
        var second = redisServiceHelper.addUserToCampaignMembers(CAMPAIGN_ID, 1L);

        // Assert — SADD returns 0 for a member that is already present
        assertEquals(Optional.of(Boolean.TRUE), first);
        assertTrue(second.isEmpty());
        assertEquals(1L, redisTemplate.opsForSet().size(membersKey()));
    }

    // ---------------------------------------------------------------------
    // RS-7 getCampaignIdsForClaimCheck
    // ---------------------------------------------------------------------

    @Test
    void getCampaignIdsForClaimCheck_then_returns_ids_and_drains_the_set() {
        // Arrange
        redisTemplate.opsForSet().add(AppConstants.CLAIM_CAMPAIGN_PRIORITY_IDS_SET, "1", "2");

        // Act
        List<Long> ids = redisServiceHelper.getCampaignIdsForClaimCheck();

        // Assert
        assertThat(ids).containsExactlyInAnyOrder(1L, 2L);
        assertEquals(0L, redisTemplate.opsForSet().size(AppConstants.CLAIM_CAMPAIGN_PRIORITY_IDS_SET));
    }

    @Test
    void getCampaignIdsForClaimCheck_with_empty_set_then_returns_empty_list() {
        // Act
        List<Long> ids = redisServiceHelper.getCampaignIdsForClaimCheck();

        // Assert
        assertTrue(ids.isEmpty());
    }

    // ---------------------------------------------------------------------
    // RS-8 / RS-9 refresh
    // ---------------------------------------------------------------------

    /**
     * RS-8 — an empty recomputed member list must delete the key, not leave stale members behind.
     */
    @Test
    void refreshCampaignMembersCache_with_empty_ids_then_key_deleted() {
        // Arrange
        redisTemplate.opsForSet().add(membersKey(), "1", "2");

        // Act
        redisServiceHelper.refreshCampaignMembersCache(CAMPAIGN_ID, List.of());

        // Assert
        assertFalse(redisTemplate.hasKey(membersKey()));
    }

    @Test
    void refreshCampaignMembersCache_with_ids_then_members_written() {
        // Act
        redisServiceHelper.refreshCampaignMembersCache(CAMPAIGN_ID, List.of(1L, 2L));

        // Assert
        assertThat(redisTemplate.opsForSet().members(membersKey())).containsExactlyInAnyOrder("1", "2");
    }

    /**
     * RS-9 — the refresh takes {@code max(current, recomputed)} so it cannot clobber an in-flight
     * decrement with a stale higher value.
     */
    @Test
    void refreshCampaignAvailableSeatsCache_with_lower_recomputed_value_then_keeps_current() {
        // Arrange
        redisTemplate.opsForValue().set(seatsKey(), "5");

        // Act
        redisServiceHelper.refreshCampaignAvailableSeatsCache(CAMPAIGN_ID, 3);

        // Assert
        assertEquals("5", redisTemplate.opsForValue().get(seatsKey()));
    }

    @Test
    void refreshCampaignAvailableSeatsCache_with_higher_recomputed_value_then_takes_recomputed() {
        // Arrange
        redisTemplate.opsForValue().set(seatsKey(), "1");

        // Act
        redisServiceHelper.refreshCampaignAvailableSeatsCache(CAMPAIGN_ID, 4);

        // Assert
        assertEquals("4", redisTemplate.opsForValue().get(seatsKey()));
    }

    @Test
    void refreshCampaignAvailableSeatsCache_with_missing_key_then_writes_recomputed() {
        // Act
        redisServiceHelper.refreshCampaignAvailableSeatsCache(CAMPAIGN_ID, 4);

        // Assert
        assertEquals("4", redisTemplate.opsForValue().get(seatsKey()));
    }

    // ---------------------------------------------------------------------
    // RS-10 refresh methods swallow failures
    // ---------------------------------------------------------------------

    /**
     * RS-10 — both refresh methods log and continue, so one broken campaign cannot abort the whole
     * 60s reconciliation sweep. Uses a failing template rather than the container.
     */
    @Test
    void refresh_methods_with_failing_template_then_no_exception_escapes() {
        // Arrange
        var failingTemplate = mock(StringRedisTemplate.class);
        doThrow(new IllegalStateException("redis down")).when(failingTemplate).delete(anyString());
        when(failingTemplate.opsForSet()).thenThrow(new IllegalStateException("redis down"));

        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(failingTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(any())).thenThrow(new IllegalStateException("redis down"));

        var failingHelper = new RedisServiceHelper(failingTemplate);

        // Act / Assert
        assertDoesNotThrow(() -> failingHelper.refreshCampaignMembersCache(CAMPAIGN_ID, List.of()));
        assertDoesNotThrow(() -> failingHelper.refreshCampaignMembersCache(CAMPAIGN_ID, List.of(1L)));
        assertDoesNotThrow(() -> failingHelper.refreshCampaignAvailableSeatsCache(CAMPAIGN_ID, 3));
    }
}
