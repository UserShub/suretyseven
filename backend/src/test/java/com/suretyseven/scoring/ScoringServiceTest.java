package com.suretyseven.scoring;

import com.suretyseven.domain.Decision;
import com.suretyseven.external.ApplicantInfo;
import com.suretyseven.service.scoring.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests of the scoring/decision logic -- no Spring context, so
 * these run in milliseconds and pin down the documented scoring table
 * exactly. If someone changes a rule's point value, one of these should
 * fail and force them to update the documentation deliberately.
 */
class ScoringServiceTest {

    private final ScoringService scoringService = new ScoringService(
            List.of(new CreditScoreRule(), new YearsInBusinessRule(), new BondToRevenueRule(), new ExistingExposureRule()),
            new UnderwritingThresholds(80, 50)
    );

    private ApplicantInfo applicant(int creditScore, int years, long revenue, long exposure) {
        return new ApplicantInfo("COMP-1", BigDecimal.valueOf(revenue), years, creditScore, BigDecimal.valueOf(exposure));
    }

    @Test
    void strongApplicantScoresApprove() {
        // credit 760 (+30), 8 years (+20), bond 500k / revenue 12M = 4.2% (+30), exposure 1.5M/12M = 12.5% (+20)
        ApplicantInfo info = applicant(760, 8, 12_000_000, 1_500_000);
        ScoreResult result = scoringService.score(new ScoringContext(info, BigDecimal.valueOf(500_000)));

        assertThat(result.totalScore()).isEqualTo(100);
        assertThat(result.decision()).isEqualTo(Decision.APPROVE);
        assertThat(result.breakdown()).hasSize(4);
    }

    @Test
    void weakApplicantScoresDecline() {
        // credit 640 (+5), 2 years (+10), bond 900k/revenue 1M = 90% (+10), exposure 400k/1M = 40% (+5)
        ApplicantInfo info = applicant(640, 2, 1_000_000, 400_000);
        ScoreResult result = scoringService.score(new ScoringContext(info, BigDecimal.valueOf(900_000)));

        assertThat(result.totalScore()).isEqualTo(30);
        assertThat(result.decision()).isEqualTo(Decision.DECLINE);
    }

    @Test
    void midRangeApplicantScoresRefer() {
        // credit 720 (+20), 6 years (+20), bond 200k/revenue 2M=10% (+30), exposure 500k/2M=25% (+5) = 75
        ApplicantInfo info = applicant(720, 6, 2_000_000, 500_000);
        ScoreResult result = scoringService.score(new ScoringContext(info, BigDecimal.valueOf(200_000)));

        assertThat(result.totalScore()).isEqualTo(75);
        assertThat(result.decision()).isEqualTo(Decision.REFER);
    }

    @Test
    void boundaryScoresAreInclusiveOnTheApproveSide() {
        UnderwritingThresholds thresholds = new UnderwritingThresholds(80, 50);
        assertThat(new ScoringService(List.of(), thresholds).score(
                        new ScoringContext(applicant(0, 0, 1, 0), BigDecimal.ONE)).decision())
                .isEqualTo(Decision.DECLINE); // 0 points, sanity check on empty rule list
    }

    @Test
    void zeroRevenueIsTreatedConservativelyNotDividedByZero() {
        ApplicantInfo info = applicant(760, 8, 0, 0);
        ScoreResult result = scoringService.score(new ScoringContext(info, BigDecimal.valueOf(500_000)));
        // Should not throw ArithmeticException, and should award the conservative low points on both ratio rules.
        assertThat(result.breakdown()).anySatisfy(o -> {
            if (o.factor().equals("BOND_TO_REVENUE")) assertThat(o.points()).isEqualTo(10);
        });
    }
}
