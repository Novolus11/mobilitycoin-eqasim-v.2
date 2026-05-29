package org.eqasim.core.simulation.analysis;

import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPOutputStream;

import org.eqasim.core.components.config.EqasimConfigGroup;
import org.eqasim.core.simulation.policies.impl.mobility_coins.logic.MobilityCoinsParameters;
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
import com.google.inject.Singleton;

/**
 * Link flow analysis - OPTIMIZED VERSION.
 * 
 * Only collects data when termination is possible (after termination horizon).
 */
@Singleton
public class LinkFlowMocoAnalysisListener implements IterationStartsListener, IterationEndsListener, ShutdownListener,
    VehicleEntersTrafficEventHandler, VehicleLeavesTrafficEventHandler, LinkEnterEventHandler, 
    PersonDepartureEventHandler, PersonArrivalEventHandler {

    private static final String OUTPUT_FILE_NAME = "link_flows_moco.csv.gz";
    private static final Set<String> ECOLOGICAL_MODES = Set.of("walk", "bike");
    private static final Set<String> CAR_MODES = Set.of("car");
    private static final Set<String> TRANSIT_MODES = Set.of("pt");
    
    private final OutputDirectoryHierarchy outputDirectory;
    private final Network network;
    private final MobilityCoinsParameters mobilityCoinsParameters;
    private final int analysisInterval;
    private final TerminationState terminationState;
    private boolean handlerRegistered = false;
    
    private final Map<Id<Link>, Map<String, Integer>> linkFlowsByMode = new ConcurrentHashMap<>();
    private final Map<Id<Link>, Double> linkCoinsSpent = new ConcurrentHashMap<>();
    private final Map<Id<Link>, Double> linkCoinsRewarded = new ConcurrentHashMap<>();
    private final Map<Id<Vehicle>, Id<Person>> vehicleToPersonMap = new ConcurrentHashMap<>();
    private final Map<Id<Vehicle>, String> vehicleModeMap = new ConcurrentHashMap<>();
    private final Map<Id<Person>, TripInfo> ongoingTrips = new ConcurrentHashMap<>();
    private Set<String> allModes = new HashSet<>();
    
    private static class TripInfo {
        String mode;
        Id<Link> departureLink;
        TripInfo(String mode) { this.mode = mode; }
    }

    @Inject
    public LinkFlowMocoAnalysisListener(EqasimConfigGroup config, OutputDirectoryHierarchy outputDirectory,
            Network network, MobilityCoinsParameters mobilityCoinsParameters,
            TerminationState terminationState) {
        this.outputDirectory = outputDirectory;
        this.network = network;
        this.mobilityCoinsParameters = mobilityCoinsParameters;
        this.analysisInterval = config.getAnalysisInterval();
        this.terminationState = terminationState;
        
        for (Id<Link> linkId : network.getLinks().keySet()) {
            linkFlowsByMode.put(linkId, new ConcurrentHashMap<>());
            linkCoinsSpent.put(linkId, 0.0);
            linkCoinsRewarded.put(linkId, 0.0);
        }
    }

    @Override
    public void notifyIterationStarts(IterationStartsEvent event) {
        // ZERO OVERHEAD: Only register handler when termination is IMMINENT.
        // isAllCriteriaZero() is true ONLY when:
        // - All termination criteria are 0.0, OR
        // - We're at lastIteration
        if (analysisInterval > 0 && terminationState.isAllCriteriaZero()) {
            resetData();
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
        if (analysisInterval > 0) {
            try {
                System.out.println("Writing link flows moco for final iteration " + event.getIteration() + "...");
                writeReport();
                System.out.println("  Done.");
            } catch (IOException e) {
                throw new RuntimeException("Failed to write link flows moco at shutdown", e);
            }
        }
    }

    @Override
    public void handleEvent(VehicleEntersTrafficEvent event) {
        vehicleToPersonMap.put(event.getVehicleId(), event.getPersonId());
        vehicleModeMap.put(event.getVehicleId(), event.getNetworkMode());
        allModes.add(event.getNetworkMode());
    }

    @Override
    public void handleEvent(VehicleLeavesTrafficEvent event) {
        vehicleToPersonMap.remove(event.getVehicleId());
        vehicleModeMap.remove(event.getVehicleId());
    }

    @Override
    public void handleEvent(LinkEnterEvent event) {
        String mode = vehicleModeMap.get(event.getVehicleId());
        if (mode != null) {
            linkFlowsByMode.get(event.getLinkId()).merge(mode, 1, Integer::sum);
            processMobilityCoinsForLink(event.getLinkId(), mode);
        }
    }

    @Override
    public void handleEvent(PersonDepartureEvent event) {
        TripInfo tripInfo = new TripInfo(event.getLegMode());
        tripInfo.departureLink = event.getLinkId();
        ongoingTrips.put(event.getPersonId(), tripInfo);
        allModes.add(event.getLegMode());
        if (ECOLOGICAL_MODES.contains(event.getLegMode()) || TRANSIT_MODES.contains(event.getLegMode())) {
            linkFlowsByMode.get(event.getLinkId()).merge(event.getLegMode(), 1, Integer::sum);
        }
    }

    @Override
    public void handleEvent(PersonArrivalEvent event) {
        TripInfo tripInfo = ongoingTrips.remove(event.getPersonId());
        if (tripInfo != null) {
            if (ECOLOGICAL_MODES.contains(tripInfo.mode)) {
                processTeleportedTripCoins(tripInfo.departureLink, event.getLinkId(), tripInfo.mode, true);
            } else if (TRANSIT_MODES.contains(tripInfo.mode)) {
                processTeleportedTripCoins(tripInfo.departureLink, event.getLinkId(), tripInfo.mode, false);
            }
        }
    }
    
    private void processMobilityCoinsForLink(Id<Link> linkId, String mode) {
        Link link = network.getLinks().get(linkId);
        if (link == null) return;
        double linkLength_km = link.getLength() / 1000.0;
        if (ECOLOGICAL_MODES.contains(mode)) {
            linkCoinsRewarded.merge(linkId, calculateRewards(mode, linkLength_km), Double::sum);
        } else {
            linkCoinsSpent.merge(linkId, calculateCharges(mode, linkLength_km), Double::sum);
        }
    }
    
    private void processTeleportedTripCoins(Id<Link> depLink, Id<Link> arrLink, String mode, boolean isEcological) {
        if (depLink == null || arrLink == null) return;
        Link departureLink = network.getLinks().get(depLink);
        Link arrivalLink = network.getLinks().get(arrLink);
        if (departureLink == null || arrivalLink == null) return;
        double tripDistance_km = (departureLink.getLength() + arrivalLink.getLength()) / 1000.0;
        double depRatio = departureLink.getLength() / (departureLink.getLength() + arrivalLink.getLength());
        double arrRatio = 1.0 - depRatio;
        if (isEcological) {
            double rewards = calculateRewards(mode, tripDistance_km);
            linkCoinsRewarded.merge(depLink, rewards * depRatio, Double::sum);
            linkCoinsRewarded.merge(arrLink, rewards * arrRatio, Double::sum);
        } else {
            double charges = calculateCharges(mode, tripDistance_km);
            linkCoinsSpent.merge(depLink, charges * depRatio, Double::sum);
            linkCoinsSpent.merge(arrLink, charges * arrRatio, Double::sum);
        }
    }
    
    private double calculateRewards(String mode, double km) {
        return "bike".equals(mode) ? km * mobilityCoinsParameters.incentive_coins_per_km_bicycle :
               "walk".equals(mode) ? km * mobilityCoinsParameters.incentive_coins_per_km_walking : 0.0;
    }
    
    private double calculateCharges(String mode, double km) {
        if (CAR_MODES.contains(mode)) {
            return km * mobilityCoinsParameters.emissions_gco2_per_km_car * mobilityCoinsParameters.cost_coins_per_gco2;
        } else if (TRANSIT_MODES.contains(mode)) {
            return km * mobilityCoinsParameters.emissions_gco2_per_km_transit * mobilityCoinsParameters.cost_coins_per_gco2;
        }
        return 0.0;
    }
    
    private void resetData() {
        linkFlowsByMode.values().forEach(Map::clear);
        linkCoinsSpent.replaceAll((k, v) -> 0.0);
        linkCoinsRewarded.replaceAll((k, v) -> 0.0);
        vehicleToPersonMap.clear();
        vehicleModeMap.clear();
        ongoingTrips.clear();
        allModes.clear();
    }
    
    private void writeReport() throws IOException {
        allModes.addAll(Set.of("car", "bike", "walk", "pt"));
        List<String> sortedModes = new ArrayList<>(allModes);
        Collections.sort(sortedModes);
        
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                new GZIPOutputStream(new FileOutputStream(outputDirectory.getOutputFilename(OUTPUT_FILE_NAME)))))) {
            List<String> header = new ArrayList<>(List.of("link_id", "link_length_m"));
            sortedModes.forEach(m -> header.add("flow_" + m));
            header.addAll(List.of("coins_spent", "coins_rewarded"));
            writer.write(String.join(";", header) + "\n");
            
            for (Id<Link> linkId : network.getLinks().keySet()) {
                Link link = network.getLinks().get(linkId);
                List<String> row = new ArrayList<>();
                row.add(linkId.toString());
                row.add(String.format(Locale.US, "%.2f", link.getLength()));
                for (String mode : sortedModes) {
                    row.add(String.valueOf(linkFlowsByMode.get(linkId).getOrDefault(mode, 0)));
                }
                row.add(String.format(Locale.US, "%.6f", linkCoinsSpent.getOrDefault(linkId, 0.0)));
                row.add(String.format(Locale.US, "%.6f", linkCoinsRewarded.getOrDefault(linkId, 0.0)));
                writer.write(String.join(";", row) + "\n");
            }
        }
    }

    @Override
    public void reset(int iteration) { resetData(); }
}
