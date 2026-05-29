package org.eqasim.core.simulation.policies.impl.mobility_coins;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.util.LinkedList;
import java.util.List;

import org.matsim.core.utils.io.IOUtils;

public class MobilityCoinsWriter {
    private final File outputPath;
    private final List<Entry> entries = new LinkedList<>();

    public MobilityCoinsWriter(File outputPath) {
        this.outputPath = outputPath;
    }

    public void writeMarketPrice(Entry entry) {
        try {
            BufferedWriter writer = IOUtils.getBufferedWriter(outputPath.toString());

            writer.write(String.join(";", new String[] {
                    "iteration", //
                    // === SIMULATION IDENTIFICATION (first columns for easy filtering) ===
                    "allocationScheme", //
                    "reductionPercentage", //
                    "tripDroppingEnabled", //
                    "initialMarketPrice", //
                    // === CONFIGURATION PARAMETERS ===
                    "emissions_gco2_per_km_car", //
                    "emissions_gco2_per_km_transit", //
                    "cost_coins_per_gco2", //
                    "incentive_coins_per_km_bicycle", //
                    "incentive_coins_per_km_walking", //
            "number_of_persons", //
            "number_of_eligible_persons", //
            "avg_coins_per_person", //
            "avg_coins_per_person_wo_exempt", //
                    "baseline_coins_100pct", //
                    "total_coins_allocated", //
                    "excess_coins", //
                    "excessRevenue", //
                    "shortage_coins", //
                    "shortage_minus_excess", //
                    "global_coins_balance", //
                    "error", //
                    "total_coins_in_market", //
                    "total_coins_used_up", //
                    "total_coins_spent", //
                    "total_coins_rewarded", //
                    "target_emission_coins", //
                    "effective_allocation", //
                    "target_coins", //
                    "market_price", //
                    "calculated_market_price", //
                    "smoothed_market_price", //
                    "pidOutput", //
                    "droppedLeisureTrips" //
            }) + "\n");
            entries.add(entry);

            for (Entry e : entries) {
                writer.write(String.join(";", new String[] {
                        String.valueOf(e.iteration), //
                        // === SIMULATION IDENTIFICATION ===
                        e.allocationScheme, //
                        String.valueOf(e.reductionPercentage), //
                        String.valueOf(e.tripDroppingEnabled), //
                        String.valueOf(e.initialMarketPrice), //
                        // === CONFIGURATION PARAMETERS ===
                        String.valueOf(e.emissionsGco2PerKmCar), //
                        String.valueOf(e.emissionsGco2PerKmTransit), //
                        String.valueOf(e.costCoinsPerGco2), //
                        String.valueOf(e.incentiveCoinsPerKmBicycle), //
                        String.valueOf(e.incentiveCoinsPerKmWalking), //
                        String.valueOf(e.numberOfPersons), //
                        String.valueOf(e.numberOfEligiblePersons), //
                        String.valueOf(e.avgCoinsPerPerson), //
                        String.valueOf(e.avgCoinsPerPersonWoExempt), //
                        String.valueOf(e.baselineCoins100Pct), //
                        String.valueOf(e.totalCoinsAllocated), //
                        String.valueOf(e.excessCoins), //
                        String.valueOf(e.excessRevenue), //
                        String.valueOf(e.shortageCoins), //
                        String.valueOf(e.shortageMinusExcess), //
                        String.valueOf(e.globalCoinsBalance), //
                        String.valueOf(e.error), //
                        String.valueOf(e.totalCoinsInMarket), //
                        String.valueOf(e.totalCoinsDelta), //
                        String.valueOf(e.totalCoinsSpent), //
                        String.valueOf(e.totalCoinsRewarded), //
                        String.valueOf(e.targetEmissionCoins), //
                        String.valueOf(e.effectiveAllocation), //
                        String.valueOf(e.targetCoins), //
                        String.valueOf(e.marketPrice), //
                        String.valueOf(e.calculatedMarketPrice), //
                        String.valueOf(e.smoothedMarketPrice), //
                        String.valueOf(e.pidOutput), //
                        String.valueOf(e.droppedLeisureTrips) //
                }) + "\n");
            }

            writer.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public record Entry(
            int iteration,
            // Simulation identification
            String allocationScheme,
            double reductionPercentage,
            boolean tripDroppingEnabled,
            double initialMarketPrice,
            // Configuration parameters
            double emissionsGco2PerKmCar,
            double emissionsGco2PerKmTransit,
            double costCoinsPerGco2,
            double incentiveCoinsPerKmBicycle,
            double incentiveCoinsPerKmWalking,
            // Population metrics
            int numberOfPersons,
            int numberOfEligiblePersons,
            double avgCoinsPerPerson,
            double avgCoinsPerPersonWoExempt,
            double baselineCoins100Pct,
            double totalCoinsAllocated,
            // Market metrics
            double excessCoins,
            double excessRevenue,
            double shortageCoins,
            double shortageMinusExcess,
            double globalCoinsBalance,
            double error,
            double totalCoinsInMarket,
            double totalCoinsDelta,
            double totalCoinsSpent,
            double totalCoinsRewarded,
            // Emission-based dynamic allocation
            double targetEmissionCoins,
            double effectiveAllocation,
            double targetCoins,
            // Market price tracking
            double marketPrice,
            double calculatedMarketPrice,
            double smoothedMarketPrice,
            double pidOutput,
            int droppedLeisureTrips
    ) {
    }
}
