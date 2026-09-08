
ALTER TABLE claim_campaign ADD CONSTRAINT user_id_and_campaign_id_unique UNIQUE (user_id, campaign_id);

