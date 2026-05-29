package org.eqasim.core.simulation.termination;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.matsim.core.controler.TerminationCriterion;

import com.google.common.base.Verify;
import com.google.common.collect.ImmutableMap;

public class EqasimTerminationCriterion implements TerminationCriterion {
	private final Map<String, TerminationIndicatorSupplier> indicators;
	private final Map<String, TerminationCriterionCalculator> criteria;

	private final List<TerminationData> history = new LinkedList<>();

	private final int firstIteration;
	private final int lastIteration;
	private final int horizon;

	private final TerminationWriter writer;
	private final TerminationState terminationState;

	public EqasimTerminationCriterion(int firstIteration, int lastIteration, int horizon,
			Map<String, TerminationIndicatorSupplier> indicators, Map<String, TerminationCriterionCalculator> criteria,
			TerminationWriter writer, TerminationState terminationState) {
		this.firstIteration = firstIteration;
		this.lastIteration = lastIteration;
		this.horizon = horizon;

		this.indicators = indicators;
		this.criteria = criteria;

		this.writer = writer;
		this.terminationState = terminationState;
	}

	@Override
	public boolean mayTerminateAfterIteration(int iteration) {
		// Called at the BEGINNING of an iteration, BEFORE IterationStartsEvent.
		// This is the perfect place to signal analysis listeners whether to register handlers.

		boolean allCriteriaZero = false;

		if (iteration > firstIteration) {
			// Check termination criteria (based on previous iteration's data)
			TerminationData terminationData = prepareTerminationData(iteration);
			history.add(terminationData);
			writer.write(history);

			allCriteriaZero = terminationData.criteria.values().stream().mapToDouble(d -> d).sum() == 0.0;
		}

		// Signal to analysis listeners: register handlers ONLY when termination is imminent
		// This is either when all criteria are 0.0 OR we're at the lastIteration.
		// ZERO OVERHEAD: Handlers NOT registered during normal iterations.
		boolean shouldRegisterHandlers = allCriteriaZero || (iteration >= lastIteration);
		terminationState.setAllCriteriaZero(shouldRegisterHandlers);

		// Return whether we MAY terminate (for MATSim's doTerminate check)
		return allCriteriaZero || (iteration >= lastIteration);
	}

	@Override
	public boolean doTerminate(int iteration) {
		// Called at the END of an iteration IF mayTerminateAfterIteration returned true.
		// This is called AFTER IterationEndsEvent.

		// Obtain data for the current iteration
		TerminationData terminationData = prepareTerminationData(iteration);
		boolean doTerminate = terminationData.criteria.values().stream().mapToDouble(d -> d).sum() == 0.0;

		if (iteration >= lastIteration) {
			doTerminate = true;
		}

		if (doTerminate) {
			// Add final iteration data and write
			history.add(terminationData);
			writer.write(history);
		}

		return doTerminate;
	}

	private TerminationData prepareTerminationData(int iteration) {
		// Obtain indicator values
		ImmutableMap.Builder<String, Double> indicatorValues = ImmutableMap.builder();

		for (var item : indicators.entrySet()) {
			indicatorValues.put(item.getKey(), item.getValue().getValue());
		}

		IterationData iterationData = new IterationData(iteration - 1, indicatorValues.build());

		// Obtain criterion values
		ImmutableMap.Builder<String, Double> criterionValues = ImmutableMap.builder();

		for (var item : criteria.entrySet()) {
			criterionValues.put(item.getKey(), item.getValue().calculate(history, iterationData));
		}

		TerminationData terminationData = new TerminationData(iteration - 1, iterationData.indicators,
				criterionValues.build());

		return terminationData;
	}

	public void replay(List<TerminationData> replayData) {
		for (int k = 0; k < replayData.size(); k++) {
			TerminationData item = replayData.get(k);

			if (item.iteration == firstIteration) {
				Verify.verify(k == replayData.size() - 1,
						"Replay data should contain iterations until (inclusive) firstIteration");
				return;
			}
		}

		throw new IllegalStateException("Did not find iteration " + firstIteration + " in replay data");
	}
}
