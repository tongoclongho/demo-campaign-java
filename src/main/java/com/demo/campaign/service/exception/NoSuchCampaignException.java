package com.demo.campaign.service.exception;

public class NoSuchCampaignException extends RuntimeException {
    public NoSuchCampaignException(String message) {
        super(message);
    }
}
