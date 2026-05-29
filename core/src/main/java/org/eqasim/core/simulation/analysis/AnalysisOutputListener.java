package org.eqasim.core.simulation.analysis;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import org.eqasim.core.analysis.DistanceUnit;
import org.eqasim.core.analysis.activities.ActivityListener;
import org.eqasim.core.analysis.activities.EnrichedActivityListener;
import org.eqasim.core.analysis.activities.EnrichedActivityWriter;
import org.eqasim.core.analysis.legs.LegListener;
import org.eqasim.core.analysis.legs.LegWriter;
import org.eqasim.core.analysis.pt.PublicTransportLegListener;
import org.eqasim.core.analysis.pt.PublicTransportLegWriter;
import org.eqasim.core.analysis.trips.TripListener;
import org.eqasim.core.analysis.trips.EnrichedTripListener;
import org.eqasim.core.analysis.trips.EnrichedTripWriter;
import org.eqasim.core.components.config.EqasimConfigGroup;
import org.eqasim.core.components.travel_time.RecordedTravelTime;
import org.eqasim.core.components.travel_time.TravelTimeRecorder;
import org.eqasim.core.simulation.termination.TerminationState;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.controler.events.IterationStartsEvent;
import org.matsim.core.controler.events.ShutdownEvent;
import org.matsim.core.controler.listener.IterationEndsListener;
import org.matsim.core.controler.listener.IterationStartsListener;
import org.matsim.core.controler.listener.ShutdownListener;

import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * Analysis output listener - ZERO OVERHEAD VERSION.
 * 
 * Handlers are ONLY registered when termination is imminent:
 * - All termination criteria are 0.0, OR
 * - We're at the configured lastIteration
 * 
 * The TerminationState flag is set by EqasimTerminationCriterion in 
 * mayTerminateAfterIteration(), which is called BEFORE IterationStartsEvent.
 * 
 * This means:
 * - NO overhead during normal iterations (handlers NOT registered)
 * - Data collected ONLY on the actual final iteration(s)
 * - Reports written at shutdown
 */
@Singleton
public class AnalysisOutputListener implements IterationStartsListener, IterationEndsListener, ShutdownListener {
	private static final String TRIPS_MOCO_FILE_NAME = "eqasim_trips_moco.csv.gz";
	private static final String LEGS_FILE_NAME = "eqasim_legs.csv.gz";
	private static final String PT_FILE_NAME = "eqasim_pt.csv";
	private static final String ACTIVITIES_MOCO_FILE_NAME = "eqasim_activities_moco.csv.gz";
	private static final String TRAVEL_TIMES_FILE_NAME = "eqasim_travel_times.bin";

	private final OutputDirectoryHierarchy outputDirectory;

	private final TripListener tripAnalysisListener;
	private final EnrichedTripListener enrichedTripAnalysisListener;
	private final LegListener legAnalysisListener;
	private final PublicTransportLegListener ptAnalysisListener;
	private final ActivityListener activityAnalysisListener;
	private final EnrichedActivityListener enrichedActivityAnalysisListener;
	private final TravelTimeRecorder travelTimeRecorder;
	private final TerminationState terminationState;

	private final int analysisInterval;
	private final int travelTimeInterval;

	private final DistanceUnit scenarioDistanceUnit;
	private final DistanceUnit analysisDistanceUnit;
	
	private boolean handlersRegistered = false;

	@Inject
	public AnalysisOutputListener(EqasimConfigGroup config, OutputDirectoryHierarchy outputDirectory,
			TripListener tripListener, EnrichedTripListener enrichedTripListener, LegListener legListener, 
			PublicTransportLegListener ptListener, ActivityListener activityAnalysisListener, 
			EnrichedActivityListener enrichedActivityAnalysisListener, TravelTimeRecorder travelTimeRecorder,
			TerminationState terminationState) {
		this.outputDirectory = outputDirectory;

		this.scenarioDistanceUnit = config.getDistanceUnit();
		this.analysisDistanceUnit = config.getAnalysisDistanceUnit();

		this.analysisInterval = config.getAnalysisInterval();
		this.travelTimeInterval = config.getTravelTimeRecordingInterval();

		this.tripAnalysisListener = tripListener;
		this.enrichedTripAnalysisListener = enrichedTripListener;
		this.legAnalysisListener = legListener;
		this.ptAnalysisListener = ptListener;
		this.activityAnalysisListener = activityAnalysisListener;
		this.enrichedActivityAnalysisListener = enrichedActivityAnalysisListener;
		this.travelTimeRecorder = travelTimeRecorder;
		this.terminationState = terminationState;
	}

	@Override
	public void notifyIterationStarts(IterationStartsEvent event) {
		// ZERO OVERHEAD: Only register handlers when termination is imminent.
		// TerminationState.isAllCriteriaZero() is set by EqasimTerminationCriterion
		// in mayTerminateAfterIteration(), which is called BEFORE this event.
		// 
		// It returns true ONLY when:
		// - All termination criteria are 0.0, OR
		// - We're at lastIteration
		
		if (!terminationState.isAllCriteriaZero()) {
			// Not the final iteration - NO handlers, NO overhead
			return;
		}
		
		// This might be the final iteration - register handlers
		if (analysisInterval > 0) {
			tripAnalysisListener.reset(event.getIteration());
			enrichedTripAnalysisListener.reset(event.getIteration());
			legAnalysisListener.reset(event.getIteration());
			ptAnalysisListener.reset(event.getIteration());
			activityAnalysisListener.reset(event.getIteration());
			enrichedActivityAnalysisListener.reset(event.getIteration());
			
			event.getServices().getEvents().addHandler(tripAnalysisListener);
			event.getServices().getEvents().addHandler(enrichedTripAnalysisListener);
			event.getServices().getEvents().addHandler(legAnalysisListener);
			event.getServices().getEvents().addHandler(ptAnalysisListener);
			event.getServices().getEvents().addHandler(activityAnalysisListener);
			event.getServices().getEvents().addHandler(enrichedActivityAnalysisListener);
			handlersRegistered = true;
		}

		if (travelTimeInterval > 0 && terminationState.isAllCriteriaZero()) {
			travelTimeRecorder.reset(event.getIteration());
			event.getServices().getEvents().addHandler(travelTimeRecorder);
		}
	}

	@Override
	public void notifyIterationEnds(IterationEndsEvent event) {
		// Unregister handlers if they were registered
		if (handlersRegistered) {
			event.getServices().getEvents().removeHandler(tripAnalysisListener);
			event.getServices().getEvents().removeHandler(enrichedTripAnalysisListener);
			event.getServices().getEvents().removeHandler(legAnalysisListener);
			event.getServices().getEvents().removeHandler(ptAnalysisListener);
			event.getServices().getEvents().removeHandler(activityAnalysisListener);
			event.getServices().getEvents().removeHandler(enrichedActivityAnalysisListener);
			
			if (travelTimeInterval > 0) {
				event.getServices().getEvents().removeHandler(travelTimeRecorder);
			}
			handlersRegistered = false;
		}
		
		// Delete the ITERS/it.X directory to save storage
		deleteIterationDirectory(event.getIteration());
	}
	
	private void deleteIterationDirectory(int iteration) {
		try {
			File iterDir = new File(outputDirectory.getIterationPath(iteration));
			if (iterDir.exists() && iterDir.isDirectory()) {
				deleteDirectoryRecursively(iterDir);
			}
		} catch (Exception e) {
			System.err.println("Warning: Could not delete iteration directory for iteration " + iteration + ": " + e.getMessage());
		}
	}
	
	private void deleteDirectoryRecursively(File directory) throws IOException {
		if (directory.isDirectory()) {
			File[] files = directory.listFiles();
			if (files != null) {
				for (File file : files) {
					deleteDirectoryRecursively(file);
				}
			}
		}
		Files.deleteIfExists(directory.toPath());
	}

	@Override
	public void notifyShutdown(ShutdownEvent event) {
		// Write analysis files directly to output directory at shutdown.
		// This is called when the simulation ACTUALLY terminates.
		// Data is in memory from the final iteration's event handlers.
		
		if (analysisInterval > 0) {
			try {
				System.out.println("Writing eqasim analysis files for final iteration " + event.getIteration() + "...");
				
				new EnrichedTripWriter(enrichedTripAnalysisListener.getTripItems(), scenarioDistanceUnit, analysisDistanceUnit)
						.write(outputDirectory.getOutputFilename(TRIPS_MOCO_FILE_NAME));
				
				new LegWriter(legAnalysisListener.getLegItems(), scenarioDistanceUnit, analysisDistanceUnit)
						.write(outputDirectory.getOutputFilename(LEGS_FILE_NAME));
				
				new PublicTransportLegWriter(ptAnalysisListener.getTripItems())
						.write(outputDirectory.getOutputFilename(PT_FILE_NAME));
				
				new EnrichedActivityWriter(enrichedActivityAnalysisListener.getActivityItems())
						.write(outputDirectory.getOutputFilename(ACTIVITIES_MOCO_FILE_NAME));
				
				System.out.println("  Done.");
				
			} catch (IOException e) {
				throw new RuntimeException("Failed to write eqasim analysis files at shutdown", e);
			}
		}
		
		if (travelTimeInterval > 0) {
			try {
				RecordedTravelTime.writeBinary(
						outputDirectory.getOutputFilename(TRAVEL_TIMES_FILE_NAME),
						this.travelTimeRecorder.getTravelTime());
			} catch (IOException | InterruptedException e) {
				throw new RuntimeException("Failed to write travel times at shutdown", e);
			}
		}
	}
}
