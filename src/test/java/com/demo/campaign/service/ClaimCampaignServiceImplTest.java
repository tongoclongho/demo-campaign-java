package com.demo.campaign.service;

import com.demo.campaign.dto.ClaimSubmissionDTO;
import com.demo.campaign.dto.CreateClaimCampaign;
import com.demo.campaign.entity.Campaign;
import com.demo.campaign.entity.CampaignUser;
import com.demo.campaign.entity.ClaimCampaign;
import com.demo.campaign.entity.ClaimCampaignStatus;
import com.demo.campaign.repository.CampaignRepository;
import com.demo.campaign.repository.CampaignUserRepository;
import com.demo.campaign.repository.ClaimCampaignRepository;
import com.demo.campaign.service.exception.CampaignClaimedAlreadyException;
import com.demo.campaign.service.exception.CampaignNoAvailableSeatsException;
import com.demo.campaign.service.exception.NoSuchCampaignException;
import com.demo.campaign.service.exception.NoSuchUserException;
import com.demo.campaign.service.mapper.MapperConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Plain Mockito slice for the branching logic of {@link ClaimCampaignServiceImpl} — the synchronous
 * accept ({@code claimCampaign}, CL-S*) and the settlement decision ({@code processClaim}, CL-P*).
 * See docs/test_plan_service.md. The queue-draining job is covered against a real Redis in
 * {@link ClaimCampaignSubmissionJobTest}.
 */
@ExtendWith(MockitoExtension.class)
class ClaimCampaignServiceImplTest {

    private static final Long CAMPAIGN_ID = 2L;
    private static final Long USER_ID = 1L;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ClaimCampaignRepository claimCampaignRepository;

    @Mock
    private CampaignRepository campaignRepository;

    @Mock
    private CampaignUserRepository campaignUserRepository;

    @Mock
    private MapperConfig mapper;

    @Mock
    private RedisServiceHelper redisServiceHelper;

    private ClaimCampaignServiceImpl claimCampaignService;

    @BeforeEach
    void setUp() {
        claimCampaignService = new ClaimCampaignServiceImpl(redisTemplate, claimCampaignRepository,
                campaignRepository, campaignUserRepository, mapper, redisServiceHelper);
        // @Value-injected field; stays null under plain Mockito and would unbox to an NPE in Math.min
        ReflectionTestUtils.setField(claimCampaignService, "batchSize", 100L);
    }

    private static CreateClaimCampaign request(LocalDateTime claimDate) {
        var request = new CreateClaimCampaign();
        request.setCampaignId(CAMPAIGN_ID);
        request.setUserId(USER_ID);
        request.setClaimDate(claimDate);
        return request;
    }

    private static CampaignUser user(int loyaltyPoints) {
        var user = CampaignUser.builder().email("alice@campaign.com").loyaltyPoints(loyaltyPoints).build();
        user.setId(USER_ID);
        return user;
    }

    private static Campaign campaign() {
        var campaign = Campaign.builder().code("TEST").totalCapacity(5).build();
        campaign.setId(CAMPAIGN_ID);
        return campaign;
    }

    private void stubHappyPathLookups(int loyaltyPoints) {
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign()));
        when(campaignUserRepository.findById(USER_ID)).thenReturn(Optional.of(user(loyaltyPoints)));
    }

    private void verifyNothingPersistedOrEnqueued() {
        verify(claimCampaignRepository, never()).save(any());
        verify(redisServiceHelper, never()).sendClaimToPriorityQueue(any());
    }

    // ---------------------------------------------------------------------
    // claimCampaign
    // ---------------------------------------------------------------------

    /**
     * CL-S1 — the happy path persists a SUBMITTED row carrying the request's identifiers and date.
     */
    @Test
    void claimCampaign_with_all_guards_passing_then_persists_submitted_claim() {
        // Arrange
        var claimDate = LocalDateTime.of(2026, 8, 31, 10, 0);
        stubHappyPathLookups(77);

        // Act
        claimCampaignService.claimCampaign(request(claimDate));

        // Assert
        var captor = ArgumentCaptor.forClass(ClaimCampaign.class);
        verify(claimCampaignRepository).save(captor.capture());

        var saved = captor.getValue();
        assertEquals(ClaimCampaignStatus.SUBMITTED, saved.getStatus());
        assertEquals(CAMPAIGN_ID, saved.getCampaignId());
        assertEquals(USER_ID, saved.getUserId());
        assertEquals(claimDate, saved.getClaimDate());
        assertNull(saved.getReason());
    }

    /**
     * CL-S2 — the enqueued submission carries the loyalty points of the <em>loaded</em> user, so a
     * client cannot influence the value that rides in the ZSET member.
     */
    @Test
    void claimCampaign_with_all_guards_passing_then_enqueues_with_points_from_loaded_user() {
        // Arrange
        var claimDate = LocalDateTime.of(2026, 8, 31, 10, 0);
        stubHappyPathLookups(77);

        // Act
        claimCampaignService.claimCampaign(request(claimDate));

        // Assert
        var captor = ArgumentCaptor.forClass(ClaimSubmissionDTO.class);
        verify(redisServiceHelper).sendClaimToPriorityQueue(captor.capture());

        var submission = captor.getValue();
        assertEquals(CAMPAIGN_ID, submission.getCampaignId());
        assertEquals(USER_ID, submission.getUserId());
        assertEquals(claimDate, submission.getClaimDate());
        assertEquals(77, submission.getUserPoints());
    }

    /**
     * CL-S3 — an existing member is rejected by the Redis guard before anything is written.
     */
    @Test
    void claimCampaign_with_user_already_in_members_then_throws_and_nothing_persisted() {
        // Arrange
        doThrow(new CampaignClaimedAlreadyException("The user has claimed the campaign already."))
                .when(redisServiceHelper).requireUserNotInCampaignMembers(USER_ID, CAMPAIGN_ID);

        // Act / Assert
        assertThrows(CampaignClaimedAlreadyException.class,
                () -> claimCampaignService.claimCampaign(request(LocalDateTime.now())));

        verifyNothingPersistedOrEnqueued();
        verify(redisServiceHelper, never()).requireCampaignAvailableSeats(anyLong());
        verifyNoMoreInteractions(campaignRepository, campaignUserRepository);
    }

    /**
     * CL-S4 — a full campaign is rejected before Postgres is touched at all.
     */
    @Test
    void claimCampaign_with_no_available_seats_then_throws_without_touching_postgres() {
        // Arrange
        doThrow(new CampaignNoAvailableSeatsException("No available seats for the campaign id " + CAMPAIGN_ID))
                .when(redisServiceHelper).requireCampaignAvailableSeats(CAMPAIGN_ID);

        // Act / Assert
        assertThrows(CampaignNoAvailableSeatsException.class,
                () -> claimCampaignService.claimCampaign(request(LocalDateTime.now())));

        verifyNothingPersistedOrEnqueued();
        verifyNoMoreInteractions(campaignRepository, campaignUserRepository);
    }

    /**
     * CL-S5 — unknown campaign.
     */
    @Test
    void claimCampaign_with_unknown_campaign_then_throws_and_nothing_persisted() {
        // Arrange
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.empty());

        // Act / Assert
        assertThrows(NoSuchCampaignException.class,
                () -> claimCampaignService.claimCampaign(request(LocalDateTime.now())));

        verifyNothingPersistedOrEnqueued();
        verifyNoMoreInteractions(campaignUserRepository);
    }

    /**
     * CL-S6 — unknown user.
     */
    @Test
    void claimCampaign_with_unknown_user_then_throws_and_nothing_persisted() {
        // Arrange
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign()));
        when(campaignUserRepository.findById(USER_ID)).thenReturn(Optional.empty());

        // Act / Assert
        assertThrows(NoSuchUserException.class,
                () -> claimCampaignService.claimCampaign(request(LocalDateTime.now())));

        verifyNothingPersistedOrEnqueued();
    }

    /**
     * CL-S7 — the cheap Redis guards must run first, and in this order. A regression here means a full
     * campaign starts hitting Postgres on every request.
     */
    @Test
    void claimCampaign_then_redis_guards_run_before_postgres_lookups() {
        // Arrange
        stubHappyPathLookups(77);

        // Act
        claimCampaignService.claimCampaign(request(LocalDateTime.now()));

        // Assert
        var order = inOrder(redisServiceHelper, campaignRepository, campaignUserRepository);
        order.verify(redisServiceHelper).requireUserNotInCampaignMembers(USER_ID, CAMPAIGN_ID);
        order.verify(redisServiceHelper).requireCampaignAvailableSeats(CAMPAIGN_ID);
        order.verify(campaignRepository).findById(CAMPAIGN_ID);
        order.verify(campaignUserRepository).findById(USER_ID);
    }

    /**
     * CL-S8 — the row must exist before the claim is enqueued; enqueueing first would let the job pop a
     * claim whose row is not yet visible.
     */
    @Test
    void claimCampaign_then_persists_before_enqueueing() {
        // Arrange
        stubHappyPathLookups(77);

        // Act
        claimCampaignService.claimCampaign(request(LocalDateTime.now()));

        // Assert
        var order = inOrder(claimCampaignRepository, redisServiceHelper);
        order.verify(claimCampaignRepository).save(any(ClaimCampaign.class));
        order.verify(redisServiceHelper).sendClaimToPriorityQueue(any(ClaimSubmissionDTO.class));
    }

    // ---------------------------------------------------------------------
    // processClaim — the settlement decision
    // ---------------------------------------------------------------------

    private ClaimCampaign submittedClaim() {
        return ClaimCampaign.builder()
                .campaignId(CAMPAIGN_ID)
                .userId(USER_ID)
                .claimDate(LocalDateTime.of(2026, 8, 31, 10, 0))
                .status(ClaimCampaignStatus.SUBMITTED)
                .build();
    }

    /**
     * CL-P1 — a reserved seat plus a fresh member entry approves the claim.
     */
    @Test
    void processClaim_with_seat_reserved_and_member_added_then_approved() {
        // Arrange
        when(claimCampaignRepository.findByUserIdAndCampaignId(USER_ID, CAMPAIGN_ID))
                .thenReturn(Optional.of(submittedClaim()));
        when(redisServiceHelper.reserveCampaignSeat(CAMPAIGN_ID)).thenReturn(Optional.of(4L));
        when(redisServiceHelper.addUserToCampaignMembers(CAMPAIGN_ID, USER_ID)).thenReturn(Optional.of(Boolean.TRUE));

        // Act
        claimCampaignService.processClaim(CAMPAIGN_ID, USER_ID);

        // Assert
        var captor = ArgumentCaptor.forClass(ClaimCampaign.class);
        verify(claimCampaignRepository).save(captor.capture());
        assertEquals(ClaimCampaignStatus.APPROVED, captor.getValue().getStatus());
        assertNull(captor.getValue().getReason());
    }

    /**
     * CL-P2 — no seats left: the claim is rejected and the member set is never touched.
     */
    @Test
    void processClaim_with_no_seat_reserved_then_rejected_and_members_untouched() {
        // Arrange
        when(claimCampaignRepository.findByUserIdAndCampaignId(USER_ID, CAMPAIGN_ID))
                .thenReturn(Optional.of(submittedClaim()));
        when(redisServiceHelper.reserveCampaignSeat(CAMPAIGN_ID)).thenReturn(Optional.empty());

        // Act
        claimCampaignService.processClaim(CAMPAIGN_ID, USER_ID);

        // Assert
        var captor = ArgumentCaptor.forClass(ClaimCampaign.class);
        verify(claimCampaignRepository).save(captor.capture());

        var settled = captor.getValue();
        assertEquals(ClaimCampaignStatus.REJECTED, settled.getStatus());
        assertTrue(settled.getReason().contains(CAMPAIGN_ID.toString()), settled.getReason());
        assertTrue(settled.getReason().contains(USER_ID.toString()), settled.getReason());
        verify(redisServiceHelper, never()).addUserToCampaignMembers(anyLong(), anyLong());
    }

    /**
     * CL-P3 — the user was already in the member set: rejected with the "claimed already" reason.
     */
    @Test
    void processClaim_with_duplicate_member_then_rejected_with_already_claimed_reason() {
        // Arrange
        when(claimCampaignRepository.findByUserIdAndCampaignId(USER_ID, CAMPAIGN_ID))
                .thenReturn(Optional.of(submittedClaim()));
        when(redisServiceHelper.reserveCampaignSeat(CAMPAIGN_ID)).thenReturn(Optional.of(4L));
        when(redisServiceHelper.addUserToCampaignMembers(CAMPAIGN_ID, USER_ID)).thenReturn(Optional.empty());

        // Act
        claimCampaignService.processClaim(CAMPAIGN_ID, USER_ID);

        // Assert
        var captor = ArgumentCaptor.forClass(ClaimCampaign.class);
        verify(claimCampaignRepository).save(captor.capture());

        var settled = captor.getValue();
        assertEquals(ClaimCampaignStatus.REJECTED, settled.getStatus());
        assertTrue(settled.getReason().contains("claimed already"), settled.getReason());
    }

    /**
     * CL-P4 — a rejected claim must never be left at SUBMITTED; both rejection paths persist.
     */
    @Test
    void processClaim_with_either_rejection_path_then_claim_is_persisted() {
        // Arrange — no seats
        when(claimCampaignRepository.findByUserIdAndCampaignId(USER_ID, CAMPAIGN_ID))
                .thenReturn(Optional.of(submittedClaim()));
        when(redisServiceHelper.reserveCampaignSeat(CAMPAIGN_ID))
                .thenReturn(Optional.empty())     // first call: no seats
                .thenReturn(Optional.of(4L));     // second call: seat taken, duplicate member below
        when(redisServiceHelper.addUserToCampaignMembers(CAMPAIGN_ID, USER_ID)).thenReturn(Optional.empty());

        // Act
        claimCampaignService.processClaim(CAMPAIGN_ID, USER_ID);
        claimCampaignService.processClaim(CAMPAIGN_ID, USER_ID);

        // Assert
        var captor = ArgumentCaptor.forClass(ClaimCampaign.class);
        verify(claimCampaignRepository, Mockito.times(2)).save(captor.capture());
        captor.getAllValues()
                .forEach(claim -> assertEquals(ClaimCampaignStatus.REJECTED, claim.getStatus()));
    }

    /**
     * CL-P5 — documents a known gap: when the claim row is missing the warning is logged but there is
     * no early return, so {@code setStatus} NPEs inside the try and the catch block NPEs again on the
     * same null reference, letting the NPE escape.
     * Asserts current behaviour; see docs/test_plan_service.md "Known gaps" (1).
     */
    @Test
    void processClaim_with_missing_claim_row_then_currently_throws_npe() {
        // Arrange
        when(claimCampaignRepository.findByUserIdAndCampaignId(USER_ID, CAMPAIGN_ID)).thenReturn(Optional.empty());
        when(redisServiceHelper.reserveCampaignSeat(CAMPAIGN_ID)).thenReturn(Optional.of(4L));
        when(redisServiceHelper.addUserToCampaignMembers(CAMPAIGN_ID, USER_ID)).thenReturn(Optional.of(Boolean.TRUE));

        // Act / Assert
        assertThrows(NullPointerException.class,
                () -> claimCampaignService.processClaim(CAMPAIGN_ID, USER_ID));

        verify(claimCampaignRepository, never()).save(any());
    }

    /**
     * CL-P6 — documents a known gap: the seat decremented for a claim that is then rejected for a
     * duplicate member is never compensated. The counter is only corrected by the next
     * {@code refreshRedisCache}.
     * Asserts current behaviour; see docs/test_plan_service.md "Known gaps" (3).
     */
    @Test
    void processClaim_with_duplicate_member_then_reserved_seat_is_leaked() {
        // Arrange
        when(claimCampaignRepository.findByUserIdAndCampaignId(USER_ID, CAMPAIGN_ID))
                .thenReturn(Optional.of(submittedClaim()));
        when(redisServiceHelper.reserveCampaignSeat(CAMPAIGN_ID)).thenReturn(Optional.of(4L));
        when(redisServiceHelper.addUserToCampaignMembers(CAMPAIGN_ID, USER_ID)).thenReturn(Optional.empty());

        // Act
        claimCampaignService.processClaim(CAMPAIGN_ID, USER_ID);

        // Assert — the seat was taken, and nothing gives it back
        verify(redisServiceHelper).reserveCampaignSeat(CAMPAIGN_ID);
        verify(redisServiceHelper).addUserToCampaignMembers(CAMPAIGN_ID, USER_ID);
        verifyNoMoreInteractions(redisServiceHelper);
    }
}
