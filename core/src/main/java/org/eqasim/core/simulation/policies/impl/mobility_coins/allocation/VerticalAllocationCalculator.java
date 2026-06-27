package org.eqasim.core.simulation.policies.impl.mobility_coins.allocation;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;

import java.util.HashMap;
import java.util.Map;

/**
 * Vertical Equity (VERTICAL) allocation.
 *
 * Score: S = W1*Income + W2*PT + W3*Veh + W4*Household
 *
 *   Income    – Mittelpunkt der Einkommensrange aus "income", min-max normalisiert, invertiert
 *               (niedrigeres Einkommen → höherer Score → mehr Coins)
 *   PT        – pt_average_raptor, min-max normalisiert, invertiert
 *               (niedrigerer Raptor-Wert = besserer ÖPNV-Zugang → niedrigerer Score)
 *   Veh       – car_availability: "none"→1.0, "all"→0.0 (keine Normalisierung)
 *   Household – household_size ("5+"→5), min-max normalisiert
 *               (größerer Haushalt → höherer Score → mehr Coins)
 *
 * Coins je Agent: coins_a = totalCoins × (S_a / Σ S_a)
 */
public class VerticalAllocationCalculator implements AllocationCalculator {

    private static final Logger logger = LogManager.getLogger(VerticalAllocationCalculator.class);

    private final Map<String, AgentParametersPrecomputer.AgentParams> agentParams;
    private final double w1; // Gewicht Income
    private final double w2; // Gewicht PT
    private final double w3; // Gewicht Veh
    private final double w4; // Gewicht Household

    public VerticalAllocationCalculator(
            Map<String, AgentParametersPrecomputer.AgentParams> agentParams,
            double w1, double w2, double w3, double w4) {
        this.agentParams = agentParams;
        this.w1 = w1;
        this.w2 = w2;
        this.w3 = w3;
        this.w4 = w4;
    }

    @Override
    public Map<Id<Person>, Double> calculateAllocations(Population population, double totalCoins) {

        // --- Pass 1: Rohwerte extrahieren und Min/Max bestimmen ---
        Map<String, double[]> raw = new HashMap<>(); // personId → [income, ptRaptor (NaN wenn null), veh, household]

        double minIncome = Double.MAX_VALUE, maxIncome = -Double.MAX_VALUE;
        double minPt     = Double.MAX_VALUE, maxPt     = -Double.MAX_VALUE;
        double minHh     = Double.MAX_VALUE, maxHh     = -Double.MAX_VALUE;
        boolean hasPtData = false;

        for (Person person : population.getPersons().values()) {
            String pid = person.getId().toString();
            AgentParametersPrecomputer.AgentParams ap = agentParams.get(pid);
            if (ap == null) continue;

            double income    = parseIncomeMidpoint(ap.income);
            double ptRaptor  = ap.ptAverageRaptor != null ? (double) ap.ptAverageRaptor : Double.NaN;
            double veh       = "none".equalsIgnoreCase(ap.carAvailability) ? 1.0 : 0.0;
            double household = parseHouseholdSize(ap.householdSize);

            raw.put(pid, new double[]{income, ptRaptor, veh, household});

            if (income < minIncome) minIncome = income;
            if (income > maxIncome) maxIncome = income;
            if (!Double.isNaN(ptRaptor)) {
                hasPtData = true;
                if (ptRaptor < minPt) minPt = ptRaptor;
                if (ptRaptor > maxPt) maxPt = ptRaptor;
            }
            if (household < minHh) minHh = household;
            if (household > maxHh) maxHh = household;
        }

        if (!hasPtData) {
            logger.warn("pt_average_raptor ist für alle Agenten null – PT-Komponente wird neutral gesetzt (0.5).");
        }

        // --- Pass 2: Scores berechnen ---
        Map<Id<Person>, Double> scores = new HashMap<>();
        double totalScore = 0.0;

        for (Person person : population.getPersons().values()) {
            String pid = person.getId().toString();
            double[] r = raw.get(pid);
            if (r == null) {
                scores.put(person.getId(), 0.0);
                continue;
            }

            double incomeScore    = 1.0 - normalize(r[0], minIncome, maxIncome); // invertiert
            double ptScore        = hasPtData && !Double.isNaN(r[1])
                                    ? 1.0 - normalize(r[1], minPt, maxPt)        // invertiert
                                    : 0.5;                                        // neutral bei fehlenden Daten
            double vehScore       = r[2];                                         // bereits 0 oder 1
            double householdScore = normalize(r[3], minHh, maxHh);               // nicht invertiert

            double score = w1 * incomeScore + w2 * ptScore + w3 * vehScore + w4 * householdScore;
            scores.put(person.getId(), score);
            totalScore += score;
        }

        // --- Pass 3: Proportional zu Gesamtscore verteilen ---
        Map<Id<Person>, Double> allocations = new HashMap<>();
        if (totalScore <= 0) {
            logger.warn("Gesamtscore ist 0 – Fallback auf gleichmäßige Verteilung.");
            double coinsPerPerson = totalCoins / population.getPersons().size();
            for (Person person : population.getPersons().values()) {
                allocations.put(person.getId(), coinsPerPerson);
            }
        } else {
            for (Person person : population.getPersons().values()) {
                double score = scores.getOrDefault(person.getId(), 0.0);
                allocations.put(person.getId(), totalCoins * (score / totalScore));
            }
        }

        logStats(allocations);
        return allocations;
    }

    // -------------------------------------------------------------------------
    // Hilfsmethoden
    // -------------------------------------------------------------------------

    /** Parst den Mittelpunkt einer Einkommensrange ("x-y" → (x+y)/2, "5000+" → 6000). */
    private double parseIncomeMidpoint(String income) {
        if (income == null || income.isBlank() || "NaN".equals(income)) return 2500.0;
        income = income.trim();
        if (income.endsWith("+")) {
            try { return Double.parseDouble(income.replace("+", "").trim()) + 1000.0; }
            catch (NumberFormatException e) { return 6000.0; }
        }
        int dash = income.lastIndexOf('-');
        if (dash > 0) {
            try {
                double lo = Double.parseDouble(income.substring(0, dash).trim());
                double hi = Double.parseDouble(income.substring(dash + 1).trim());
                return (lo + hi) / 2.0;
            } catch (NumberFormatException e) { /* weiter */ }
        }
        try { return Double.parseDouble(income); }
        catch (NumberFormatException e) { return 2500.0; }
    }

    /** Parst household_size; "5+" wird als 5 interpretiert. */
    private double parseHouseholdSize(String householdSize) {
        if (householdSize == null || householdSize.isBlank()) return 1.0;
        if (householdSize.trim().endsWith("+")) return 5.0;
        try { return Double.parseDouble(householdSize.trim()); }
        catch (NumberFormatException e) { return 1.0; }
    }

    /** Min-Max-Normalisierung; bei min==max wird 0.5 zurückgegeben. */
    private double normalize(double value, double min, double max) {
        if (max <= min) return 0.5;
        return (value - min) / (max - min);
    }

    private void logStats(Map<Id<Person>, Double> allocations) {
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE, sum = 0.0;
        for (double v : allocations.values()) {
            if (v < min) min = v;
            if (v > max) max = v;
            sum += v;
        }
        logger.info("VERTICAL Verteilung: {} Agenten | min={} max={} avg={} total={}",
                allocations.size(),
                String.format("%.3f", min),
                String.format("%.3f", max),
                String.format("%.3f", sum / allocations.size()),
                String.format("%.1f", sum));
    }

    @Override
    public String getDescription() {
        return String.format(
                "Vertical Equity (VERTICAL): S = %.1f*Income_inv + %.1f*PT_inv + %.1f*Veh + %.1f*Household",
                w1, w2, w3, w4);
    }
}
