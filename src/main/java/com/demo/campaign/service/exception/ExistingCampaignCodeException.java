package com.demo.campaign.service.exception;

public class ExistingCampaignCodeException extends RuntimeException {
    public ExistingCampaignCodeException(String message) {
        super(message);
    }
}
