package org.eqasim.core.simulation.termination;

import com.google.inject.Singleton;

/**
 * Tracks whether the current iteration may be the final one.
 * 
 * This is set by EqasimTerminationCriterion in mayTerminateAfterIteration(),
 * which is called BEFORE IterationStartsEvent. This allows analysis listeners
 * to register handlers only when termination is imminent.
 * 
 * ZERO OVERHEAD: Handlers are only registered when:
 * - All termination criteria are 0.0 (termination imminent), OR
 * - We're at the configured lastIteration
 */
@Singleton
public class TerminationState {
    
    private volatile boolean allCriteriaZero = false;
    
    /**
     * Called by EqasimTerminationCriterion in mayTerminateAfterIteration().
     * Set to true when ALL termination criteria are 0.0.
     */
    public void setAllCriteriaZero(boolean allZero) {
        this.allCriteriaZero = allZero;
    }
    
    /**
     * Returns true if all termination criteria were 0.0 in the most recent check.
     * This indicates the simulation MAY terminate after the current iteration.
     */
    public boolean isAllCriteriaZero() {
        return allCriteriaZero;
    }
}
