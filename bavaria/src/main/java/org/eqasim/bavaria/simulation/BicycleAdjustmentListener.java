package org.eqasim.bavaria.simulation;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.Population;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.events.IterationStartsEvent;
import org.matsim.core.controler.listener.IterationStartsListener;
import org.matsim.core.router.TripStructureUtils;

import com.google.inject.Provides;
import com.google.inject.Singleton;

public class BicycleAdjustmentListener implements IterationStartsListener {
    private final static String MODE = "bicycle";
    private final static String ATTRIBUTE = "bicycleCongestionFactor";

    private final Population population;
    private final OutputDirectoryHierarchy outputDirectoryHierarchy;
    private double referenceTrips = Double.NaN;
    private BufferedWriter csvWriter = null;

    private BicycleAdjustmentListener(Population population, OutputDirectoryHierarchy outputDirectoryHierarchy) {
        this.population = population;
        this.outputDirectoryHierarchy = outputDirectoryHierarchy;
    }

    @Override
    public void notifyIterationStarts(IterationStartsEvent event) {
        // Initialize CSV writer on first iteration
        if (csvWriter == null) {
            try {
                String outputPath = outputDirectoryHierarchy.getOutputPath();
                File csvFile = new File(outputPath, "bicycle_congestion_log.csv");
                csvWriter = new BufferedWriter(new FileWriter(csvFile));
                csvWriter.write("iteration,bicycle_trips,reference_trips,ratio,congestion_factor,sample_travel_time_factor\n");
            } catch (IOException e) {
                System.err.println("Warning: Could not create bicycle congestion log file: " + e.getMessage());
            }
        }

        // count the number of bicycle trips that will be executed in the iteration
        double currentTrips = 0.0;

        for (Person person : population.getPersons().values()) {
            Plan plan = person.getSelectedPlan();

            for (Leg leg : TripStructureUtils.getLegs(plan)) {
                if (leg.getMode().equals(MODE)) {
                    currentTrips += 1.0;
                }
            }
        }

        // if this is the first iteration, save it as a reference value
        if (Double.isNaN(referenceTrips)) {
            referenceTrips = currentTrips;
        }

        // calculate a congestion factor based on the ratio with the reference
        // REDUCED CONGESTION VERSION - more realistic BPR-like function with lower power and coefficient
        double ratio = currentTrips / referenceTrips;
        double congestionFactor = 1.0 + 0.15 * Math.pow(Math.max(0.0, ratio - 1.0), 2.0);

        // Log data to CSV
        if (csvWriter != null) {
            try {
                csvWriter.write(String.format("%d,%.0f,%.0f,%.4f,%.6f,%.2f%%\n",
                    event.getIteration(),
                    currentTrips,
                    referenceTrips,
                    ratio,
                    congestionFactor,
                    (congestionFactor - 1.0) * 100.0
                ));
                csvWriter.flush();
            } catch (IOException e) {
                System.err.println("Warning: Could not write to bicycle congestion log: " + e.getMessage());
            }
        }

        // now apply the congestion factor to all bicycle trips
        for (Person person : population.getPersons().values()) {
            Plan plan = person.getSelectedPlan();

            for (Leg leg : TripStructureUtils.getLegs(plan)) {
                if (leg.getMode().equals(MODE)) {
                    // first get the currently applied factor (if there is already one)
                    // it might be that the trip just got routed in this iteration, then we don't
                    // have any factor applied yet
                    Double appliedFactor = (Double) leg.getAttributes().getAttribute(ATTRIBUTE);
                    appliedFactor = appliedFactor == null ? 1.0 : appliedFactor;

                    // reset the travel time
                    double nominalTravelTime = leg.getTravelTime().seconds() / appliedFactor;

                    // apply the new factor
                    double updatedTravelTime = congestionFactor * nominalTravelTime;

                    // save information
                    leg.setTravelTime(updatedTravelTime);
                    leg.getRoute().setTravelTime(updatedTravelTime);
                    leg.getAttributes().putAttribute(ATTRIBUTE, congestionFactor);
                }
            }
        }
    }

    public void closeCSVWriter() {
        if (csvWriter != null) {
            try {
                csvWriter.close();
                String outputPath = outputDirectoryHierarchy.getOutputPath();
                File csvFile = new File(outputPath, "bicycle_congestion_log.csv");
                System.out.println("Bicycle congestion log saved to: " + csvFile.getAbsolutePath());
            } catch (IOException e) {
                System.err.println("Warning: Error closing bicycle congestion log file: " + e.getMessage());
            }
        }
    }

    static public void install(Controler controller) {
        controller.addOverridingModule(new AbstractModule() {
            @Override
            public void install() {
                addControlerListenerBinding().to(BicycleAdjustmentListener.class);
            }

            @Provides
            @Singleton
            BicycleAdjustmentListener provideListener(Population population, OutputDirectoryHierarchy outputDirectoryHierarchy) {
                BicycleAdjustmentListener listener = new BicycleAdjustmentListener(population, outputDirectoryHierarchy);
                // Add shutdown hook to close CSV file properly
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    listener.closeCSVWriter();
                }));
                return listener;
            }
        });
    }
}
