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
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;


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


    private ClaimCampaign submittedClaim() {
        return ClaimCampaign.builder()
                .campaignId(CAMPAIGN_ID)
                .userId(USER_ID)
                .claimDate(LocalDateTime.of(2026, 8, 31, 10, 0))
                .status(ClaimCampaignStatus.SUBMITTED)
                .build();
    }


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


    @Test
    void processClaim_with_either_REJECTED_or_APPROVED_then_nothing_persisted() {
        // Arrange — no seats
        when(claimCampaignRepository.findByUserIdAndCampaignId(USER_ID, CAMPAIGN_ID))
                .thenReturn(Optional.of(submittedClaim()));
        when(redisServiceHelper.reserveCampaignSeat(CAMPAIGN_ID))
                .thenReturn(Optional.empty())     // first call: no seats -> REJECTED
                .thenReturn(Optional.of(4L));     // second call: available seats -> silent skip

        // Act
        claimCampaignService.processClaim(CAMPAIGN_ID, USER_ID);
        claimCampaignService.processClaim(CAMPAIGN_ID, USER_ID);

        // Assert
        var captor = ArgumentCaptor.forClass(ClaimCampaign.class);
        verify(claimCampaignRepository, Mockito.times(1)).save(captor.capture());
        captor.getAllValues()
                .forEach(claim -> assertEquals(ClaimCampaignStatus.REJECTED, claim.getStatus()));
    }


    @Test
    void processClaim_with_missing_claim_row_then_nothing_persisted() {
        // Arrange
        when(claimCampaignRepository.findByUserIdAndCampaignId(USER_ID, CAMPAIGN_ID)).thenReturn(Optional.empty());

        // Act
        claimCampaignService.processClaim(CAMPAIGN_ID, USER_ID);

        // Assert
        verify(claimCampaignRepository, times(1)).findByUserIdAndCampaignId(any(), any());
        verifyNoInteractions(redisServiceHelper);
        verify(claimCampaignRepository, never()).save(any());


    }

    @Test
    void claimCampaign_with_duplicated_userId_and_campaignId_then_return_existing_one() {
        // Arrange
        when(claimCampaignRepository.findByUserIdAndCampaignId(USER_ID, CAMPAIGN_ID))
                .thenReturn(Optional.of(submittedClaim()));

        doNothing().when(redisServiceHelper).requireUserNotInCampaignMembers(USER_ID, CAMPAIGN_ID);
        doNothing().when(redisServiceHelper).requireCampaignAvailableSeats(CAMPAIGN_ID);

        // Act
        claimCampaignService.claimCampaign(request(LocalDateTime.now()));

        // Assert
        verifyNoInteractions(campaignRepository, campaignUserRepository);
        verifyNothingPersistedOrEnqueued();
    }
}
