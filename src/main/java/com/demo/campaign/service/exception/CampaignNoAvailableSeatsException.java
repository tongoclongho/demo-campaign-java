package com.demo.campaign.service.exception;

public class CampaignNoAvailableSeatsException extends RuntimeException {

    public CampaignNoAvailableSeatsException(String message) {
        super(message);
    }
}
