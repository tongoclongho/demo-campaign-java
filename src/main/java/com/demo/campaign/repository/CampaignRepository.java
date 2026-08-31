package com.demo.campaign.repository;

import com.demo.campaign.entity.Campaign;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

@Transactional(readOnly = true)
public interface CampaignRepository extends CrudRepository<Campaign, Long> {

    @Query(value = "SELECT COUNT(1) FROM campaign WHERE code = :campaignCode", nativeQuery = true)
    int existingCampaignCode(@Param("campaignCode") String campaignCode);


}
