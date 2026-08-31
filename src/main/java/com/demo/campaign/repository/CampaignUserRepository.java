package com.demo.campaign.repository;

import com.demo.campaign.entity.CampaignUser;
import org.springframework.data.repository.CrudRepository;
import org.springframework.transaction.annotation.Transactional;

@Transactional(readOnly = true)
public interface CampaignUserRepository extends CrudRepository<CampaignUser, Long> {
}
