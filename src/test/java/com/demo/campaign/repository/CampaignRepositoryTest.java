package com.demo.campaign.repository;

import com.demo.campaign.entity.Campaign;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Import(TestcontainersConfiguration.class)
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class CampaignRepositoryTest {

    @Autowired
    private CampaignRepository campaignRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void existingCampaignCode_for_seeded_code_then_1() {
        assertEquals(1, campaignRepository.existingCampaignCode("BLACK_FRIDAY"));
        assertEquals(1, campaignRepository.existingCampaignCode("HAPPY_FRIDAY"));
    }

    @Test
    void existingCampaignCode_for_unknown_code_then_0() {
        assertEquals(0, campaignRepository.existingCampaignCode("NO_SUCH_CAMPAIGN"));
    }


    @Test
    void existingCampaignCode_is_case_sensitive() {
        assertEquals(0, campaignRepository.existingCampaignCode("black_friday"));
        assertEquals(0, campaignRepository.existingCampaignCode("Black_Friday"));
    }

    @Test
    void save_duplicate_code_then_DataIntegrityViolationException() {
        Campaign duplicate = Campaign.builder().code("BLACK_FRIDAY").totalCapacity(9).build();

        DataIntegrityViolationException ex = assertThrows(DataIntegrityViolationException.class, () -> {
            campaignRepository.save(duplicate);
            entityManager.flush();
        });

        assertTrue(rootMessage(ex).contains("uk_campaign_code"),
                () -> "expected the unique constraint name in: " + rootMessage(ex));
    }


    @Test
    void save_campaign_with_null_id_then_id_is_generated() {
        Campaign campaign = Campaign.builder().code("RP_CR5").totalCapacity(4).build();
        assertNull(campaign.getId(), "precondition: the id is unset before saving");

        Campaign saved = campaignRepository.save(campaign);
        entityManager.flush();

        assertNotNull(saved.getId());
    }


    @Test
    void totalCapacity_round_trips_through_the_snake_case_column() {
        Campaign saved = campaignRepository.save(
                Campaign.builder().code("RP_CR6").totalCapacity(42).build());
        entityManager.flush();
        entityManager.clear();

        Campaign reloaded = campaignRepository.findById(saved.getId()).orElseThrow();
        assertEquals(42, reloaded.getTotalCapacity());

        Object rawColumn = entityManager.getEntityManager()
                .createNativeQuery("SELECT total_capacity FROM campaign WHERE id = :id")
                .setParameter("id", saved.getId())
                .getSingleResult();
        assertEquals(42, ((Number) rawColumn).intValue(),
                "totalCapacity must land in the total_capacity column, not TOTALCAPACITY");
    }

    private static String rootMessage(Throwable throwable) {
        Throwable cursor = throwable;
        StringBuilder messages = new StringBuilder();
        while (cursor != null) {
            messages.append(cursor.getMessage()).append(" | ");
            cursor = cursor.getCause();
        }
        return messages.toString();
    }
}
