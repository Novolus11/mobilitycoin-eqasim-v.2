package org.eqasim.core.simulation.policies.impl.mobility_coins.allocation;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;

import java.util.HashMap;
import java.util.Map;

/**
 * Sufficientarianism (SUFFI) allocation.
 *
 * Score: S = baseScore + W1*ptNeed + W2*carNeed + W3*incomeNeed
 *
 *   baseScore  = 1/3 × (W1 + W2 + W3)            — Grundzuteilung für alle Agenten
 *
 *   ptNeed     (binär, aus pt_average_raptor):
 *                1 = Agent hat schlechte ÖPNV-Erreichbarkeit (Wert ≤ max/3)
 *                0 = ausreichende Erreichbarkeit
 *
 *   carNeed    (binär, aus car_availability):
 *                1 = kein Auto ("none")
 *                0 = Auto vorhanden ("all")
 *
 *   incomeNeed (binär, aus income-Mittelpunkt):
 *                1 = niedriges Einkommen (Mittelpunkt ≤ max/3)
 *                0 = ausreichendes Einkommen
 *
 * Schwellenwerte für ptNeed und incomeNeed:
 *   26,21 % des Maximalwerts aller Agenten.
 *   Agenten mit Wert ≤ diesem Schwellenwert erhalten den Bedürfnis-Bonus.
 *
 * Coins je Agent: coins_a = totalCoins × (S_a / Σ S_a)
 */
public class SuffiAllocationCalculator implements AllocationCalculator {

    private static final Logger logger = LogManager.getLogger(SuffiAllocationCalculator.class);

    private final Map<String, AgentParametersPrecomputer.AgentParams> agentParams;
    private final double w1, w2, w3;

    public SuffiAllocationCalculator(
            Map<String, AgentParametersPrecomputer.AgentParams> agentParams,
            double w1, double w2, double w3) {
        this.agentParams = agentParams;
        this.w1 = w1; this.w2 = w2; this.w3 = w3;
    }

    @Override
    public Map<Id<Person>, Double> calculateAllocations(Population population, double totalCoins) {

        double baseScore = (1.0 / 3.0) * (w1 + w2 + w3);

        // --- Pass 1: Maximalwerte für PT und Income ermitteln ---
        double maxPtRaptor      = 0.0;
        double maxIncomeMidpoint = 0.0;

        for (Person person : population.getPersons().values()) {
            AgentParametersPrecomputer.AgentParams ap = agentParams.get(person.getId().toString());
            if (ap == null) continue;
            if (ap.ptAverageRaptor != null) {
                maxPtRaptor = Math.max(maxPtRaptor, ap.ptAverageRaptor);
            }
            double mid = HeSocioAllocationCalculator.parseMidpoint(ap.income);
            maxIncomeMidpoint = Math.max(maxIncomeMidpoint, mid);
        }

        double ptThreshold     = maxPtRaptor      * 0.2621;
        double incomeThreshold = maxIncomeMidpoint * 0.2621;

        logger.info("SUFFI: baseScore={} | PT-Schwellenwert ≤ {} (max={}) | Income-Schwellenwert ≤ {} EUR (max={} EUR)",
                String.format("%.3f", baseScore),
                String.format("%.1f", ptThreshold),    String.format("%.1f", maxPtRaptor),
                String.format("%.0f", incomeThreshold), String.format("%.0f", maxIncomeMidpoint));

        // --- Pass 2: Scores berechnen ---
        Map<Id<Person>, Double> scores = new HashMap<>();
        double totalScore = 0.0;

        for (Person person : population.getPersons().values()) {
            String pid = person.getId().toString();
            AgentParametersPrecomputer.AgentParams ap = agentParams.get(pid);

            double score;
            if (ap == null) {
                score = baseScore;
            } else {
                // ptNeed: 1 wenn ÖPNV-Erreichbarkeit ≤ 1/3 des Maximums, sonst 0
                double ptNeed = (ap.ptAverageRaptor != null && ap.ptAverageRaptor <= ptThreshold)
                        ? 1.0 : 0.0;

                // carNeed: 1 wenn kein Auto, 0 wenn Auto vorhanden
                double carNeed = "none".equals(ap.carAvailability) ? 1.0 : 0.0;

                // incomeNeed: 1 wenn Einkommen-Mittelpunkt ≤ 1/3 des Maximums, sonst 0
                double mid        = HeSocioAllocationCalculator.parseMidpoint(ap.income);
                double incomeNeed = (mid > 0 && mid <= incomeThreshold) ? 1.0 : 0.0;

                score = baseScore + w1 * ptNeed + w2 * carNeed + w3 * incomeNeed;
            }
            scores.put(person.getId(), score);
            totalScore += score;
        }

        // --- Pass 3: Proportional zu Gesamtscore verteilen ---
        Map<Id<Person>, Double> allocations = new HashMap<>();
        if (totalScore <= 0) {
            logger.warn("SUFFI: Gesamtscore ist 0 – Fallback auf gleichmäßige Verteilung.");
            double coinsPerPerson = totalCoins / population.getPersons().size();
            for (Person person : population.getPersons().values()) {
                allocations.put(person.getId(), coinsPerPerson);
            }
        } else {
            for (Person person : population.getPersons().values()) {
                double s = scores.getOrDefault(person.getId(), 0.0);
                allocations.put(person.getId(), totalCoins * (s / totalScore));
            }
        }

        logStats(allocations);
        return allocations;
    }

    private void logStats(Map<Id<Person>, Double> allocations) {
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE, sum = 0.0;
        for (double v : allocations.values()) {
            if (v < min) min = v; if (v > max) max = v; sum += v;
        }
        logger.info("SUFFI Verteilung: {} Agenten | min={} max={} avg={} total={}",
                allocations.size(),
                String.format("%.3f", min), String.format("%.3f", max),
                String.format("%.3f", sum / allocations.size()),
                String.format("%.1f", sum));
    }

    @Override
    public String getDescription() {
        return String.format(
                "Sufficientarianism (SUFFI): S = %.3f(base) + %.1f*ptNeed + %.1f*carNeed + %.1f*incomeNeed",
                (1.0 / 3.0) * (w1 + w2 + w3), w1, w2, w3);
    }
}
