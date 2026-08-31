package com.demo.campaign.service.exception;

public class CampaignClaimedAlreadyException extends RuntimeException {

    public CampaignClaimedAlreadyException(String message) {
        super(message);
    }
}
