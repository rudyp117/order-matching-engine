package dev.tape.engine;

import org.junit.jupiter.api.Test;

class MatchingEngineTest {
    @Test void stateIsPublishedOnlyAfterCommit() { EngineScenarios.stateIsPublishedOnlyAfterCommit(); }
    @Test void restingAndNoCross() throws Exception { EngineScenarios.restingAndNoCross(); }
    @Test void makerPricingBothDirections() throws Exception { EngineScenarios.makerPricingBothDirections(); }
    @Test void bestPriceBeforeEarlierWorsePrice() throws Exception { EngineScenarios.bestPriceBeforeEarlierWorsePrice(); }
    @Test void fifoRetainedAfterPartialFill() throws Exception { EngineScenarios.fifoRetainedAfterPartialFill(); }
    @Test void completeFillNeverRests() throws Exception { EngineScenarios.completeFillNeverRests(); }
    @Test void partialMakerAndTaker() throws Exception { EngineScenarios.partialMakerAndTaker(); }
    @Test void multiLevelSweep() throws Exception { EngineScenarios.multiLevelSweep(); }
    @Test void sellSweepDescendingBids() throws Exception { EngineScenarios.sellSweepDescendingBids(); }
    @Test void cancelPartialAndRemoveLevel() throws Exception { EngineScenarios.cancelPartialAndRemoveLevel(); }
    @Test void cancelMiddlePreservesFifo() throws Exception { EngineScenarios.cancelMiddlePreservesFifo(); }
    @Test void cancelMissingFilledAndCancelled() throws Exception { EngineScenarios.cancelMissingFilledAndCancelled(); }
    @Test void invalidInputsAndBounds() throws Exception { EngineScenarios.invalidInputsAndBounds(); }
    @Test void duplicateIdsEvenAfterFill() throws Exception { EngineScenarios.duplicateIdsEvenAfterFill(); }
    @Test void rejectSequenceGapsAndReordering() throws Exception { EngineScenarios.rejectSequenceGapsAndReordering(); }
    @Test void copyIsIsolatedAndSnapshotsImmutable() throws Exception { EngineScenarios.copyIsIsolatedAndSnapshotsImmutable(); }
    @Test void stableReplayAndTradeHistory() throws Exception { EngineScenarios.stableReplayAndTradeHistory(); }
    @Test void restartRestoresBookStatusesTrades() throws Exception { EngineScenarios.restartRestoresBookStatusesTrades(); }
    @Test void corruptReplayFailsLoudly() throws Exception { EngineScenarios.corruptReplayFailsLoudly(); }
    @Test void rollbackAndFence() throws Exception { EngineScenarios.rollbackAndFence(); }
    @Test void ambiguousCommitRecoveredByReplay() throws Exception { EngineScenarios.ambiguousCommitRecoveredByReplay(); }
    @Test void duplicateGeneratorIsRejectedBeforePersistence() throws Exception { EngineScenarios.duplicateGeneratorIsRejectedBeforePersistence(); }
    @Test void concurrentSubmissionsAreSerialized() throws Exception { EngineScenarios.concurrentSubmissionsAreSerialized(); }
    @Test void seededConservationAndAggregatedDepth() throws Exception { EngineScenarios.seededConservationAndAggregatedDepth(); }
    @Test void wideAggregationAndBoundedViews() throws Exception { EngineScenarios.wideAggregationAndBoundedViews(); }
}
