package com.demo.campaign;

import com.demo.campaign.dto.CampaignUsedSeats;
import com.demo.campaign.entity.ClaimCampaign;
import com.demo.campaign.entity.ClaimCampaignStatus;
import com.demo.campaign.repository.ClaimCampaignRepository;
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

/**
 * RP-F*, RP-G*, RP-A*, RP-C4 and RP-C5 from docs/test_plan_repository.md.
 *
 * <p>Three of the queries under test are {@code nativeQuery = true}, so this runs against a real
 * {@code postgres:latest} container — Docker must be running. {@code @DataJpaTest} is transactional
 * and rolls back after each test, so the campaign ids invented below never leak between tests.
 * There are no foreign keys on {@code claim_campaign}, so claims can reference campaign ids that do
 * not exist in the {@code campaign} table (RP-C4).
 */
@Import(TestcontainersConfiguration.class)
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ClaimCampaignRepositoryTest {

    @Autowired
    private ClaimCampaignRepository claimCampaignRepository;

    @Autowired
    private TestEntityManager entityManager;

    // ---------------------------------------------------------------- findByUserIdAndCampaignId

    /** RP-F1 */
    @Test
    void findByUserIdAndCampaignId_when_match_exists_then_returns_the_claim() {
        ClaimCampaign saved = save(1001L, 501L, ClaimCampaignStatus.APPROVED, null);

        Optional<ClaimCampaign> found = claimCampaignRepository.findByUserIdAndCampaignId(501L, 1001L);

        assertTrue(found.isPresent());
        assertEquals(saved.getId(), found.get().getId());
        assertEquals(ClaimCampaignStatus.APPROVED, found.get().getStatus());
    }

    /** RP-F2 */
    @Test
    void findByUserIdAndCampaignId_when_no_match_then_empty() {
        assertTrue(claimCampaignRepository.findByUserIdAndCampaignId(999_001L, 999_002L).isEmpty());
    }

    /**
     * RP-F3 — both parameters are {@code Long}, so transposing them is invisible to the compiler.
     * The claim is stored as (userId = 1, campaignId = 2); only the (1, 2) call may find it.
     */
    @Test
    void findByUserIdAndCampaignId_arguments_are_not_transposed() {
        save(2L, 1L, ClaimCampaignStatus.SUBMITTED, null);

        assertTrue(claimCampaignRepository.findByUserIdAndCampaignId(1L, 2L).isPresent(),
                "(userId=1, campaignId=2) must match the stored row");
        assertTrue(claimCampaignRepository.findByUserIdAndCampaignId(2L, 1L).isEmpty(),
                "swapping the arguments must not match — findByUserIdAndCampaignId(userId, campaignId)");
    }

    /** RP-F4 */
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

    // --------------------------------------------------------------------- getClaimCampaignBy

    /** RP-G1 */
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

    /** RP-G2 */
    @Test
    void getClaimCampaignBy_is_scoped_to_the_requested_campaign() {
        save(1020L, 801L, ClaimCampaignStatus.APPROVED, null);
        save(1020L, 802L, ClaimCampaignStatus.APPROVED, null);
        save(1021L, 803L, ClaimCampaignStatus.APPROVED, null);

        List<ClaimCampaign> result = claimCampaignRepository.getClaimCampaignBy(1020L, "APPROVED");

        assertEquals(2, result.size());
        assertTrue(result.stream().allMatch(claim -> claim.getCampaignId() == 1020L));
    }

    /**
     * RP-G3 — {@code @Enumerated(EnumType.STRING)} is load-bearing: the native query compares the
     * column against the literal {@code "APPROVED"}, so switching to ordinal storage would silently
     * make {@code CacheManager} recompute every campaign as having zero used seats.
     */
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

    /** RP-G4 */
    @Test
    void getClaimCampaignBy_unknown_status_string_then_empty_list() {
        save(1040L, 902L, ClaimCampaignStatus.APPROVED, null);

        assertTrue(claimCampaignRepository.getClaimCampaignBy(1040L, "PENDING").isEmpty());
        assertTrue(claimCampaignRepository.getClaimCampaignBy(1040L, "approved").isEmpty(),
                "the comparison is case-sensitive; only the exact enum name matches");
    }

    /** RP-G5 */
    @Test
    void getClaimCampaignBy_campaign_without_claims_then_empty_list() {
        assertTrue(claimCampaignRepository.getClaimCampaignBy(999_999L, "APPROVED").isEmpty());
    }

    // ------------------------------------------------------------- aggregateCampaignUsedSeats

    /** RP-A1 — only APPROVED rows count towards used seats. */
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

    /** RP-A2 */
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

    /**
     * RP-A3 — a campaign with no approved claims produces <em>no row at all</em>, not a row with 0.
     * That is why {@code CampaignServiceImpl} defaults {@code usedSeats} to 0 instead of reading it
     * off the projection.
     */
    @Test
    void aggregateCampaignUsedSeats_campaign_without_approved_claims_then_no_row() {
        save(1120L, 1L, ClaimCampaignStatus.APPROVED, null);
        save(1121L, 2L, ClaimCampaignStatus.SUBMITTED, null);
        entityManager.flush();

        Map<Long, Long> byCampaign = usedSeatsByCampaign(Set.of(1120L, 1121L, 1122L));

        assertEquals(Set.of(1120L), byCampaign.keySet(),
                "campaigns with no APPROVED rows are simply absent from the result");
    }

    /**
     * RP-A4 — settles known gap #1 in docs/test_plan_repository.md. The gap predicted a Postgres
     * syntax error from {@code IN ()}; in practice Hibernate expands an empty collection to
     * {@code IN (null)}, which is valid SQL matching nothing. So
     * {@code CampaignServiceImpl.getCampaigns()} calling this unconditionally with an empty
     * {@code map.keySet()} is safe, and no early return is needed.
     */
    @Test
    void aggregateCampaignUsedSeats_with_an_empty_id_set_then_empty_list() {
        save(1160L, 1L, ClaimCampaignStatus.APPROVED, null);
        entityManager.flush();

        assertTrue(claimCampaignRepository.aggregateCampaignUsedSeats(Set.of()).isEmpty(),
                "Hibernate renders IN (null) for an empty collection — valid SQL, matches nothing");
    }

    /**
     * RP-A5 — the projection binding. Postgres folds unquoted aliases to lower case
     * ({@code campaignId} → {@code campaignid}), so this is the case that catches a projection which
     * silently binds nulls into the {@link CampaignUsedSeats} record.
     */
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

    /** RP-A6 */
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

    /**
     * RP-C4 — {@code claim_campaign.campaign_id} / {@code user_id} are plain {@code bigint} columns
     * with no foreign keys, so an orphan claim inserts cleanly. That is by design: the claim pipeline
     * writes the row before anything validates the campaign exists.
     */
    @Test
    void orphan_claim_inserts_because_there_are_no_foreign_keys() {
        ClaimCampaign orphan = save(999_999L, 888_888L, ClaimCampaignStatus.SUBMITTED, null);
        entityManager.flush();
        entityManager.clear();

        ClaimCampaign reloaded = claimCampaignRepository.findById(orphan.getId()).orElseThrow();
        assertEquals(999_999L, reloaded.getCampaignId());
        assertEquals(888_888L, reloaded.getUserId());
    }

    /** RP-C5 — {@code reason} is nullable, which is the normal shape of a SUBMITTED or APPROVED claim. */
    @Test
    void claim_with_null_reason_persists_and_reads_back_null() {
        ClaimCampaign saved = save(1150L, 1L, ClaimCampaignStatus.SUBMITTED, null);
        entityManager.flush();
        entityManager.clear();

        assertNull(claimCampaignRepository.findById(saved.getId()).orElseThrow().getReason());
    }

    // ---------------------------------------------------------------------------------- helpers

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
