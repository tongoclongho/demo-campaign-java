package com.demo.campaign.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

@Getter
@Setter
public class CreateClaimCampaign {
    private @NotNull Long campaignId;
    private @NotNull Long userId;

    @JsonIgnore
    private @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime claimDate = LocalDateTime.now();
}
