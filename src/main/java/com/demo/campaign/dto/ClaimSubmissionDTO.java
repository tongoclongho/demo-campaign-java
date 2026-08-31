package com.demo.campaign.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ClaimSubmissionDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 8124358620310887802L;

    private LocalDateTime claimDate;
    private Long campaignId;
    private Long userId;
    private Integer userPoints;

}
