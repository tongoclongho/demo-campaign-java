package com.demo.campaign;

import com.demo.campaign.entity.CampaignUser;
import com.demo.campaign.repository.CampaignUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RP-U* from docs/test_plan_repository.md — the seeded {@code campaign_user} rows and the two schema
 * rules the entity leans on ({@code uk_user_email}, {@code loyalty_points NOT NULL DEFAULT 0}).
 *
 * <p>Docker must be running.
 */
@Import(TestcontainersConfiguration.class)
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class CampaignUserRepositoryTest {

    @Autowired
    private CampaignUserRepository campaignUserRepository;

    @Autowired
    private TestEntityManager entityManager;

    /** RP-U1 — the Flyway seed gives alice 10 loyalty points. */
    @Test
    void findById_of_a_seeded_user_then_returns_it_with_seeded_loyaltyPoints() {
        CampaignUser alice = seededUser("alice@campaign.com");

        CampaignUser found = campaignUserRepository.findById(alice.getId()).orElseThrow();

        assertEquals("alice@campaign.com", found.getEmail());
        assertEquals(10, found.getLoyaltyPoints());
    }

    /** RP-U2 — {@code uk_user_email} rejects a second row with a seeded email. */
    @Test
    void save_duplicate_email_then_DataIntegrityViolationException() {
        CampaignUser duplicate = CampaignUser.builder().email("alice@campaign.com").loyaltyPoints(1).build();

        DataIntegrityViolationException ex = assertThrows(DataIntegrityViolationException.class, () -> {
            campaignUserRepository.save(duplicate);
            entityManager.flush();
        });

        assertTrue(rootMessage(ex).contains("uk_user_email"),
                () -> "expected the unique constraint name in: " + rootMessage(ex));
    }

    /**
     * RP-U3 — the entity always writes the column, so a set value is persisted verbatim; the schema
     * default {@code 0} is what keeps the NOT NULL column satisfiable for rows inserted outside JPA
     * (the Flyway seeds, `INSERT` statements in migrations).
     */
    @Test
    void loyaltyPoints_persisted_when_set_and_defaults_to_0_at_the_column_level() {
        CampaignUser withPoints = campaignUserRepository.save(
                CampaignUser.builder().email("rp-u3-points@campaign.com").loyaltyPoints(77).build());
        entityManager.flush();
        entityManager.clear();

        assertEquals(77, campaignUserRepository.findById(withPoints.getId()).orElseThrow().getLoyaltyPoints());

        // An insert that omits loyalty_points falls back to the column default rather than failing NOT NULL.
        entityManager.getEntityManager()
                .createNativeQuery("INSERT INTO campaign_user (email) VALUES (:email)")
                .setParameter("email", "rp-u3-default@campaign.com")
                .executeUpdate();

        Object defaulted = entityManager.getEntityManager()
                .createNativeQuery("SELECT loyalty_points FROM campaign_user WHERE email = :email")
                .setParameter("email", "rp-u3-default@campaign.com")
                .getSingleResult();
        assertEquals(0, ((Number) defaulted).intValue());
    }

    private CampaignUser seededUser(String email) {
        List<CampaignUser> users = new ArrayList<>();
        campaignUserRepository.findAll().forEach(users::add);
        return users.stream()
                .filter(user -> email.equals(user.getEmail()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected V2__create_test_data.sql to seed " + email));
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
