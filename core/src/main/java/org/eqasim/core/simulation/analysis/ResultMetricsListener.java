package org.eqasim.core.simulation.analysis;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Locale;

import org.eqasim.core.simulation.termination.TerminationState;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.*;
import org.matsim.api.core.v01.events.handler.*;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.controler.events.IterationStartsEvent;
import org.matsim.core.controler.events.ShutdownEvent;
import org.matsim.core.controler.listener.IterationEndsListener;
import org.matsim.core.controler.listener.IterationStartsListener;
import org.matsim.core.controler.listener.ShutdownListener;
import org.matsim.vehicles.Vehicle;

import com.google.inject.Inject;

/**
 * Result metrics listener - OPTIMIZED VERSION.
 * 
 * Only collects data when termination is imminent (all criteria = 0.0).
 * This avoids the massive overhead of processing millions of events every iteration.
 */
public class ResultMetricsListener implements 
    IterationStartsListener, IterationEndsListener, ShutdownListener,
    PersonDepartureEventHandler, PersonArrivalEventHandler,
    LinkEnterEventHandler, LinkLeaveEventHandler,
    VehicleEntersTrafficEventHandler, VehicleLeavesTrafficEventHandler {
    
    private static final String OUTPUT_FILE_NAME = "resultMetrics.csv";
    private static final double CONGESTION_THRESHOLD = 1.3;
    private static final double TIME_BIN_SIZE = 0.25;
    
    private final OutputDirectoryHierarchy outputDirectory;
    private final Network network;
    private final TerminationState terminationState;
    private boolean handlerRegistered = false;
    
    private final Map<String, Integer> modalTrips = new ConcurrentHashMap<>();
    private final Map<String, Double> modalTravelTime = new ConcurrentHashMap<>();
    private final Map<String, Double> modalPassengerKm = new ConcurrentHashMap<>();
    private final Map<String, Double> modalPassengerHours = new ConcurrentHashMap<>();
    private final Map<Id<Link>, Map<String, Integer>> linkFlowsByMode = new ConcurrentHashMap<>();
    private final Map<Id<Link>, List<Double>> linkTravelTimes = new ConcurrentHashMap<>();
    private final Map<Id<Link>, Double> linkCongestionHours = new ConcurrentHashMap<>();
    private final Map<Id<Link>, Map<Integer, Double>> linkVolumeByHour = new ConcurrentHashMap<>();
    private final Map<Id<Link>, List<CongestionPeriod>> congestionPeriods = new ConcurrentHashMap<>();
    
    private final Map<Id<Person>, TripInfo> ongoingTrips = new ConcurrentHashMap<>();
    private final Map<Id<Vehicle>, Id<Person>> vehicleToPersonMap = new ConcurrentHashMap<>();
    private final Map<Id<Vehicle>, String> vehicleModeMap = new ConcurrentHashMap<>();
    private final Map<Id<Vehicle>, Double> vehicleEnterTimes = new ConcurrentHashMap<>();
    
    private static class TripInfo {
        String mode;
        double departureTime;
        Id<Link> originLink;
        TripInfo(String mode, double departureTime) {
            this.mode = mode;
            this.departureTime = departureTime;
        }
    }
    
    private static class CongestionPeriod {
        double startTime;
        double endTime;
        boolean isRecovered;
        CongestionPeriod(double startTime) {
            this.startTime = startTime;
            this.endTime = startTime;
            this.isRecovered = false;
        }
    }
    
    @Inject
    public ResultMetricsListener(OutputDirectoryHierarchy outputDirectory, Network network,
            TerminationState terminationState) {
        this.outputDirectory = outputDirectory;
        this.network = network;
        this.terminationState = terminationState;
        
        initializeDataStructures();
    }
    
    private void initializeDataStructures() {
        for (String mode : Set.of("car", "bike", "walk", "pt", "truck")) {
            modalTrips.put(mode, 0);
            modalTravelTime.put(mode, 0.0);
            modalPassengerKm.put(mode, 0.0);
            modalPassengerHours.put(mode, 0.0);
        }
        for (Id<Link> linkId : network.getLinks().keySet()) {
            linkFlowsByMode.put(linkId, new ConcurrentHashMap<>());
            linkTravelTimes.put(linkId, new ArrayList<>());
            linkCongestionHours.put(linkId, 0.0);
            linkVolumeByHour.put(linkId, new ConcurrentHashMap<>());
            congestionPeriods.put(linkId, new ArrayList<>());
        }
    }
    
    @Override
    public void notifyIterationStarts(IterationStartsEvent event) {
        // ZERO OVERHEAD: Only register handler when termination is IMMINENT.
        // isAllCriteriaZero() is true ONLY when:
        // - All termination criteria are 0.0, OR
        // - We're at lastIteration
        if (terminationState.isAllCriteriaZero()) {
            resetDataStructures();
            event.getServices().getEvents().addHandler(this);
            handlerRegistered = true;
        }
    }
    
    @Override
    public void notifyIterationEnds(IterationEndsEvent event) {
        if (handlerRegistered) {
            event.getServices().getEvents().removeHandler(this);
            handlerRegistered = false;
        }
    }
    
    @Override
    public void notifyShutdown(ShutdownEvent event) {
        // Write once at shutdown - this is the expensive operation
        try {
            System.out.println("Writing result metrics for final iteration " + event.getIteration() + "...");
            writeReport();
            System.out.println("  Done.");
        } catch (IOException e) {
            throw new RuntimeException("Failed to write result metrics at shutdown", e);
        }
    }
    
    /**
     * Reset all data structures - called at iteration start and available for standalone analysis.
     */
    public void resetDataStructures() {
        modalTrips.replaceAll((k, v) -> 0);
        modalTravelTime.replaceAll((k, v) -> 0.0);
        modalPassengerKm.replaceAll((k, v) -> 0.0);
        modalPassengerHours.replaceAll((k, v) -> 0.0);
        ongoingTrips.clear();
        vehicleToPersonMap.clear();
        vehicleModeMap.clear();
        vehicleEnterTimes.clear();
        for (Id<Link> linkId : network.getLinks().keySet()) {
            linkFlowsByMode.get(linkId).clear();
            linkTravelTimes.get(linkId).clear();
            linkCongestionHours.put(linkId, 0.0);
            linkVolumeByHour.get(linkId).clear();
            congestionPeriods.get(linkId).clear();
        }
    }
    
    @Override
    public void handleEvent(PersonDepartureEvent event) {
        TripInfo tripInfo = new TripInfo(event.getLegMode(), event.getTime());
        tripInfo.originLink = event.getLinkId();
        ongoingTrips.put(event.getPersonId(), tripInfo);
        modalTrips.merge(event.getLegMode(), 1, Integer::sum);
    }
    
    @Override
    public void handleEvent(PersonArrivalEvent event) {
        TripInfo tripInfo = ongoingTrips.remove(event.getPersonId());
        if (tripInfo != null) {
            double travelTime = (event.getTime() - tripInfo.departureTime) / 3600.0;
            Link originLink = network.getLinks().get(tripInfo.originLink);
            Link destinationLink = network.getLinks().get(event.getLinkId());
            double distance = 0.0;
            if (originLink != null && destinationLink != null) {
                distance = Math.sqrt(Math.pow(originLink.getCoord().getX() - destinationLink.getCoord().getX(), 2) +
                                   Math.pow(originLink.getCoord().getY() - destinationLink.getCoord().getY(), 2)) / 1000.0;
            }
            modalTravelTime.merge(tripInfo.mode, travelTime, Double::sum);
            modalPassengerKm.merge(tripInfo.mode, distance, Double::sum);
            modalPassengerHours.merge(tripInfo.mode, travelTime, Double::sum);
        }
    }
    
    @Override
    public void handleEvent(VehicleEntersTrafficEvent event) {
        vehicleToPersonMap.put(event.getVehicleId(), event.getPersonId());
        vehicleModeMap.put(event.getVehicleId(), event.getNetworkMode());
    }
    
    @Override
    public void handleEvent(VehicleLeavesTrafficEvent event) {
        vehicleToPersonMap.remove(event.getVehicleId());
        vehicleModeMap.remove(event.getVehicleId());
        vehicleEnterTimes.remove(event.getVehicleId());
    }
    
    @Override
    public void handleEvent(LinkEnterEvent event) {
        String mode = vehicleModeMap.get(event.getVehicleId());
        if (mode != null) {
            linkFlowsByMode.get(event.getLinkId()).merge(mode, 1, Integer::sum);
            int hour = (int) (event.getTime() / 3600.0);
            linkVolumeByHour.get(event.getLinkId()).merge(hour, 1.0, Double::sum);
        }
        vehicleEnterTimes.put(event.getVehicleId(), event.getTime());
    }
    
    @Override
    public void handleEvent(LinkLeaveEvent event) {
        Double enterTime = vehicleEnterTimes.remove(event.getVehicleId());
        if (enterTime != null) {
            double travelTime = event.getTime() - enterTime;
            Link link = network.getLinks().get(event.getLinkId());
            if (link != null) {
                double freeFlowTime = link.getLength() / link.getFreespeed();
                linkTravelTimes.get(event.getLinkId()).add(travelTime);
                if (travelTime >= CONGESTION_THRESHOLD * freeFlowTime) {
                    linkCongestionHours.merge(event.getLinkId(), TIME_BIN_SIZE, Double::sum);
                    updateCongestionPeriods(event.getLinkId(), event.getTime(), true);
                } else {
                    updateCongestionPeriods(event.getLinkId(), event.getTime(), false);
                }
            }
        }
    }
    
    private void updateCongestionPeriods(Id<Link> linkId, double time, boolean isCongested) {
        List<CongestionPeriod> periods = congestionPeriods.get(linkId);
        if (isCongested) {
            if (periods.isEmpty() || periods.get(periods.size() - 1).isRecovered) {
                periods.add(new CongestionPeriod(time));
            } else {
                periods.get(periods.size() - 1).endTime = time;
            }
        } else {
            if (!periods.isEmpty() && !periods.get(periods.size() - 1).isRecovered) {
                periods.get(periods.size() - 1).isRecovered = true;
                periods.get(periods.size() - 1).endTime = time;
            }
        }
    }
    
    /**
     * Write the report to the default output location.
     */
    public void writeReport() throws IOException {
        writeReport(outputDirectory.getOutputFilename(OUTPUT_FILE_NAME));
    }
    
    /**
     * Write the report to a custom path - for standalone analysis tool.
     */
    public void writeReport(String outputPath) throws IOException {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputPath))) {
            // Modal splits
            writer.write("=== NETWORK LEVEL METRICS ===\n\n");
            writer.write("1.1 Modal Splits (Trip Level)\nMode,Trips,Share\n");
            int totalTrips = modalTrips.values().stream().mapToInt(Integer::intValue).sum();
            for (var entry : modalTrips.entrySet()) {
                double share = totalTrips > 0 ? (double) entry.getValue() / totalTrips : 0.0;
                writer.write(String.format(Locale.US, "%s,%d,%.4f\n", entry.getKey(), entry.getValue(), share));
            }
            writer.write("\n1.2 Total Travel Time by Mode (hours)\nMode,Total_Travel_Time_Hours\n");
            for (var entry : modalTravelTime.entrySet()) {
                writer.write(String.format(Locale.US, "%s,%.2f\n", entry.getKey(), entry.getValue()));
            }
            writer.write("\n1.3 Total Passenger Kilometers by Mode\nMode,Total_Passenger_Km\n");
            for (var entry : modalPassengerKm.entrySet()) {
                writer.write(String.format(Locale.US, "%s,%.2f\n", entry.getKey(), entry.getValue()));
            }
            writer.write("\n=== LINK LEVEL METRICS ===\n\n");
            writer.write("2.1 Link Performance Summary\nLink_ID,Flow_Capacity_Ratio,Speed_Ratio,Delay_Factor,Congestion_Hours\n");
            for (Id<Link> linkId : network.getLinks().keySet()) {
                Link link = network.getLinks().get(linkId);
                if (link != null) {
                    int totalFlow = linkFlowsByMode.get(linkId).values().stream().mapToInt(Integer::intValue).sum();
                    double flowCapacityRatio = link.getCapacity() > 0 ? totalFlow / link.getCapacity() : 0.0;
                    List<Double> travelTimes = linkTravelTimes.get(linkId);
                    double avgTravelTime = travelTimes.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
                    double freeFlowTime = link.getLength() / link.getFreespeed();
                    double speedRatio = avgTravelTime > 0 ? freeFlowTime / avgTravelTime : 1.0;
                    double delayFactor = freeFlowTime > 0 ? avgTravelTime / freeFlowTime : 1.0;
                    writer.write(String.format(Locale.US, "%s,%.4f,%.4f,%.4f,%.2f\n", 
                        linkId.toString(), flowCapacityRatio, speedRatio, delayFactor, linkCongestionHours.get(linkId)));
                }
            }
        }
    }
}
