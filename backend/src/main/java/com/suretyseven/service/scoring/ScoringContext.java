package com.suretyseven.service.scoring;

import com.suretyseven.external.ApplicantInfo;

import java.math.BigDecimal;

public record ScoringContext(ApplicantInfo applicant, BigDecimal bondAmount) {}
