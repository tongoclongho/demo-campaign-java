package com.demo.campaign.repository;

import com.demo.campaign.dto.CampaignUsedSeats;
import com.demo.campaign.entity.ClaimCampaign;
import com.demo.campaign.entity.ClaimCampaignStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Import(TestcontainersConfiguration.class)
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ClaimCampaignRepositoryTest {

    @Autowired
    private ClaimCampaignRepository claimCampaignRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void findByUserIdAndCampaignId_when_match_exists_then_returns_the_claim() {
        ClaimCampaign saved = save(1001L, 501L, ClaimCampaignStatus.APPROVED, null);

        Optional<ClaimCampaign> found = claimCampaignRepository.findByUserIdAndCampaignId(501L, 1001L);

        assertTrue(found.isPresent());
        assertEquals(saved.getId(), found.get().getId());
        assertEquals(ClaimCampaignStatus.APPROVED, found.get().getStatus());
    }


    @Test
    void findByUserIdAndCampaignId_when_no_match_then_empty() {
        assertTrue(claimCampaignRepository.findByUserIdAndCampaignId(999_001L, 999_002L).isEmpty());
    }


    @Test
    void findByUserIdAndCampaignId_arguments_are_not_transposed() {
        save(2L, 1L, ClaimCampaignStatus.SUBMITTED, null);

        assertTrue(claimCampaignRepository.findByUserIdAndCampaignId(1L, 2L).isPresent(),
                "(userId=1, campaignId=2) must match the stored row");
        assertTrue(claimCampaignRepository.findByUserIdAndCampaignId(2L, 1L).isEmpty(),
                "swapping the arguments must not match — findByUserIdAndCampaignId(userId, campaignId)");
    }


    @Test
    void findByUserIdAndCampaignId_returns_only_the_requested_campaign_for_a_user() {
        ClaimCampaign onCampaignA = save(1002L, 601L, ClaimCampaignStatus.APPROVED, null);
        ClaimCampaign onCampaignB = save(1003L, 601L, ClaimCampaignStatus.REJECTED, "no seats");

        ClaimCampaign found = claimCampaignRepository.findByUserIdAndCampaignId(601L, 1003L).orElseThrow();

        assertEquals(onCampaignB.getId(), found.getId());
        assertEquals(ClaimCampaignStatus.REJECTED, found.getStatus());
        assertEquals(onCampaignA.getId(),
                claimCampaignRepository.findByUserIdAndCampaignId(601L, 1002L).orElseThrow().getId());
    }


    @Test
    void getClaimCampaignBy_filters_by_campaign_and_status() {
        ClaimCampaign approved = save(1010L, 701L, ClaimCampaignStatus.APPROVED, null);
        save(1010L, 702L, ClaimCampaignStatus.SUBMITTED, null);
        save(1010L, 703L, ClaimCampaignStatus.REJECTED, "no seats");

        List<ClaimCampaign> result = claimCampaignRepository.getClaimCampaignBy(1010L, "APPROVED");

        assertEquals(1, result.size());
        assertEquals(approved.getId(), result.get(0).getId());
        assertEquals(701L, result.get(0).getUserId());
    }


    @Test
    void getClaimCampaignBy_is_scoped_to_the_requested_campaign() {
        save(1020L, 801L, ClaimCampaignStatus.APPROVED, null);
        save(1020L, 802L, ClaimCampaignStatus.APPROVED, null);
        save(1021L, 803L, ClaimCampaignStatus.APPROVED, null);

        List<ClaimCampaign> result = claimCampaignRepository.getClaimCampaignBy(1020L, "APPROVED");

        assertEquals(2, result.size());
        assertTrue(result.stream().allMatch(claim -> claim.getCampaignId() == 1020L));
    }


    @Test
    void status_enum_is_stored_as_its_name_so_the_native_query_matches() {
        ClaimCampaign approved = save(1030L, 901L, ClaimCampaignStatus.APPROVED, null);
        entityManager.flush();

        Object rawColumn = entityManager.getEntityManager()
                .createNativeQuery("SELECT status FROM claim_campaign WHERE id = :id")
                .setParameter("id", approved.getId())
                .getSingleResult();
        assertEquals("APPROVED", rawColumn, "status must be stored as the enum name, not its ordinal");

        assertEquals(1, claimCampaignRepository.getClaimCampaignBy(1030L, "APPROVED").size());
    }


    @Test
    void getClaimCampaignBy_unknown_status_string_then_empty_list() {
        save(1040L, 902L, ClaimCampaignStatus.APPROVED, null);

        assertTrue(claimCampaignRepository.getClaimCampaignBy(1040L, "PENDING").isEmpty());
        assertTrue(claimCampaignRepository.getClaimCampaignBy(1040L, "approved").isEmpty(),
                "the comparison is case-sensitive; only the exact enum name matches");
    }


    @Test
    void getClaimCampaignBy_campaign_without_claims_then_empty_list() {
        assertTrue(claimCampaignRepository.getClaimCampaignBy(999_999L, "APPROVED").isEmpty());
    }


    @Test
    void aggregateCampaignUsedSeats_counts_approved_only() {
        save(1100L, 1L, ClaimCampaignStatus.APPROVED, null);
        save(1100L, 2L, ClaimCampaignStatus.APPROVED, null);
        save(1100L, 3L, ClaimCampaignStatus.SUBMITTED, null);
        save(1100L, 4L, ClaimCampaignStatus.REJECTED, "no seats");
        entityManager.flush();

        List<CampaignUsedSeats> result = claimCampaignRepository.aggregateCampaignUsedSeats(Set.of(1100L));

        assertEquals(1, result.size());
        assertEquals(2L, result.get(0).usedSeats());
    }


    @Test
    void aggregateCampaignUsedSeats_groups_per_campaign() {
        save(1110L, 1L, ClaimCampaignStatus.APPROVED, null);
        save(1110L, 2L, ClaimCampaignStatus.APPROVED, null);
        save(1110L, 3L, ClaimCampaignStatus.APPROVED, null);
        save(1111L, 4L, ClaimCampaignStatus.APPROVED, null);
        entityManager.flush();

        Map<Long, Long> byCampaign = usedSeatsByCampaign(Set.of(1110L, 1111L));

        assertEquals(2, byCampaign.size());
        assertEquals(3L, byCampaign.get(1110L));
        assertEquals(1L, byCampaign.get(1111L));
    }


    @Test
    void aggregateCampaignUsedSeats_campaign_without_approved_claims_then_no_row() {
        save(1120L, 1L, ClaimCampaignStatus.APPROVED, null);
        save(1121L, 2L, ClaimCampaignStatus.SUBMITTED, null);
        entityManager.flush();

        Map<Long, Long> byCampaign = usedSeatsByCampaign(Set.of(1120L, 1121L, 1122L));

        assertEquals(Set.of(1120L), byCampaign.keySet(),
                "campaigns with no APPROVED rows are simply absent from the result");
    }


    @Test
    void aggregateCampaignUsedSeats_with_an_empty_id_set_then_empty_list() {
        save(1160L, 1L, ClaimCampaignStatus.APPROVED, null);
        entityManager.flush();

        assertTrue(claimCampaignRepository.aggregateCampaignUsedSeats(Set.of()).isEmpty(),
                "Hibernate renders IN (null) for an empty collection — valid SQL, matches nothing");
    }


    @Test
    void aggregateCampaignUsedSeats_binds_both_record_components() {
        save(1130L, 1L, ClaimCampaignStatus.APPROVED, null);
        save(1130L, 2L, ClaimCampaignStatus.APPROVED, null);
        entityManager.flush();

        List<CampaignUsedSeats> result = claimCampaignRepository.aggregateCampaignUsedSeats(Set.of(1130L));

        assertEquals(1, result.size());
        CampaignUsedSeats row = result.get(0);
        assertNotNull(row.campaignId(), "campaignId bound to null — the alias did not match the record component");
        assertNotNull(row.usedSeats(), "usedSeats bound to null — the alias did not match the record component");
        assertEquals(1130L, row.campaignId());
        assertEquals(2L, row.usedSeats());
    }


    @Test
    void aggregateCampaignUsedSeats_with_a_subset_of_ids_then_only_those_rows() {
        save(1140L, 1L, ClaimCampaignStatus.APPROVED, null);
        save(1141L, 2L, ClaimCampaignStatus.APPROVED, null);
        entityManager.flush();

        Map<Long, Long> byCampaign = usedSeatsByCampaign(Set.of(1141L));

        assertEquals(Set.of(1141L), byCampaign.keySet());
        assertEquals(1L, byCampaign.get(1141L));
    }

    // ------------------------------------------------------------------------ schema behaviour


    @Test
    void orphan_claim_inserts_because_there_are_no_foreign_keys() {
        ClaimCampaign orphan = save(999_999L, 888_888L, ClaimCampaignStatus.SUBMITTED, null);
        entityManager.flush();
        entityManager.clear();

        ClaimCampaign reloaded = claimCampaignRepository.findById(orphan.getId()).orElseThrow();
        assertEquals(999_999L, reloaded.getCampaignId());
        assertEquals(888_888L, reloaded.getUserId());
    }

    @Test
    void claim_with_null_reason_persists_and_reads_back_null() {
        ClaimCampaign saved = save(1150L, 1L, ClaimCampaignStatus.SUBMITTED, null);
        entityManager.flush();
        entityManager.clear();

        assertNull(claimCampaignRepository.findById(saved.getId()).orElseThrow().getReason());
    }

    private ClaimCampaign save(Long campaignId, Long userId, ClaimCampaignStatus status, String reason) {
        return claimCampaignRepository.save(ClaimCampaign.builder()
                .campaignId(campaignId)
                .userId(userId)
                .claimDate(LocalDateTime.now())
                .status(status)
                .reason(reason)
                .build());
    }

    private Map<Long, Long> usedSeatsByCampaign(Set<Long> campaignIds) {
        return claimCampaignRepository.aggregateCampaignUsedSeats(campaignIds).stream()
                .collect(Collectors.toMap(CampaignUsedSeats::campaignId, CampaignUsedSeats::usedSeats,
                        (a, b) -> {
                            throw new AssertionError("aggregateCampaignUsedSeats returned two rows for one campaign");
                        }));
    }
}
