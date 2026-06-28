package org.eqasim.core.simulation.policies.impl.mobility_coins.allocation;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;

import java.util.HashMap;
import java.util.Map;

/**
 * Horizontal Equity Socioeconomic (HE_SOCIO) allocation.
 *
 * Score: S = W1*V_Income + W2*V_Home + W3*V_Household
 *
 * Keine Normalisierung – V-Werte sind direkte Tabellenwerte 1–5:
 *
 *   V_Income   (Mittelpunkt des income-Strings):
 *              5 = 500–1250 EUR/Monat
 *              4 = 1250–2000 EUR/Monat
 *              3 = 2000–3000 EUR/Monat
 *              2 = 3000–4000 EUR/Monat
 *              1 = ≥ 4000 EUR/Monat
 *
 *   V_Home     (home_zone):
 *              1 = Zone 1
 *              2 = Zone 2
 *              3 = Zone 3–5
 *              4 = Zone 6–8
 *              5 = Zone 9–10
 *
 *   V_Household (household_size):
 *              1 = 1 Person
 *              2 = 2 Personen
 *              3 = 3 Personen
 *              4 = 4 Personen
 *              5 = 5+ Personen
 *
 * Unbekannte / NaN-Werte → V=0 (kein Beitrag zum Score).
 * Coins je Agent: coins_a = totalCoins × (S_a / Σ S_a)
 */
public class HeSocioAllocationCalculator implements AllocationCalculator {

    private static final Logger logger = LogManager.getLogger(HeSocioAllocationCalculator.class);

    private final Map<String, AgentParametersPrecomputer.AgentParams> agentParams;
    private final double w1, w2, w3;

    public HeSocioAllocationCalculator(
            Map<String, AgentParametersPrecomputer.AgentParams> agentParams,
            double w1, double w2, double w3) {
        this.agentParams = agentParams;
        this.w1 = w1; this.w2 = w2; this.w3 = w3;
    }

    @Override
    public Map<Id<Person>, Double> calculateAllocations(Population population, double totalCoins) {

        // Pass 1: Scores berechnen (keine Normalisierung – direkte V-Werte)
        Map<Id<Person>, Double> scores = new HashMap<>();
        double totalScore = 0.0;

        for (Person person : population.getPersons().values()) {
            String pid = person.getId().toString();
            AgentParametersPrecomputer.AgentParams ap = agentParams.get(pid);

            double score;
            if (ap == null) {
                score = 0.0;
            } else {
                int vIncome    = incomeToV(ap.income);
                int vHome      = zoneToV(ap.homeZone);
                int vHousehold = householdToV(ap.householdSize);
                score = w1 * vIncome + w2 * vHome + w3 * vHousehold;
            }
            scores.put(person.getId(), score);
            totalScore += score;
        }

        // Pass 2: Proportional zu Gesamtscore verteilen
        Map<Id<Person>, Double> allocations = new HashMap<>();
        if (totalScore <= 0) {
            logger.warn("HE_SOCIO: Gesamtscore ist 0 – Fallback auf gleichmäßige Verteilung.");
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

    // -------------------------------------------------------------------------
    // Tabellen-Mapping
    // -------------------------------------------------------------------------

    /**
     * V_Income: Mittelpunkt des Einkommens-Strings wird den Tabellenbereichen zugeordnet.
     * Offenes oberes Ende (z.B. "5600-") → untere Grenze als Näherung.
     * Unbekannt / leer / NaN → 0.
     */
    static int incomeToV(String income) {
        if (income == null || income.isBlank()) return 0;
        double mid = parseMidpoint(income);
        if (mid <= 0)     return 0;
        if (mid < 1250)   return 5;   // 500–1250
        if (mid < 2000)   return 4;   // 1250–2000
        if (mid < 3000)   return 3;   // 2000–3000
        if (mid < 4000)   return 2;   // 3000–4000
        return 1;                     // ≥ 4000
    }

    /** Parst Mittelpunkt eines "lo-hi"-Strings; offenes Ende → lo. */
    static double parseMidpoint(String income) {
        if (income == null || income.isBlank()) return 0.0;
        String s    = income.trim();
        int    dash = s.lastIndexOf('-');
        if (dash <= 0) {
            try { return Double.parseDouble(s); } catch (NumberFormatException e) { return 0.0; }
        }
        String loPart = s.substring(0, dash).trim();
        String hiPart = s.substring(dash + 1).trim();
        try {
            double lo = Double.parseDouble(loPart);
            if (hiPart.isEmpty()) return lo;
            return (lo + Double.parseDouble(hiPart)) / 2.0;
        } catch (NumberFormatException e) { return 0.0; }
    }

    /**
     * V_Home: Zonennummer → Tabellenwert.
     * NaN / unbekannt → 0.
     */
    static int zoneToV(String zone) {
        if (zone == null || zone.isBlank() || "NaN".equalsIgnoreCase(zone)) return 0;
        int z;
        try { z = Integer.parseInt(zone.trim()); } catch (NumberFormatException e) { return 0; }
        if (z == 1)              return 1;
        if (z == 2)              return 2;
        if (z >= 3 && z <= 5)   return 3;
        if (z >= 6 && z <= 8)   return 4;
        if (z >= 9 && z <= 10)  return 5;
        return 0;
    }

    /**
     * V_Household: Haushaltsgröße → Tabellenwert.
     * Unbekannt / leer → 0.
     */
    static int householdToV(String size) {
        if (size == null || size.isBlank()) return 0;
        String s = size.trim();
        if ("5+".equals(s)) return 5;
        int n;
        try { n = Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
        if (n <= 1) return 1;
        if (n == 2) return 2;
        if (n == 3) return 3;
        if (n == 4) return 4;
        return 5; // ≥ 5
    }

    private void logStats(Map<Id<Person>, Double> allocations) {
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE, sum = 0.0;
        for (double v : allocations.values()) {
            if (v < min) min = v; if (v > max) max = v; sum += v;
        }
        logger.info("HE_SOCIO Verteilung: {} Agenten | min={} max={} avg={} total={}",
                allocations.size(),
                String.format("%.3f", min), String.format("%.3f", max),
                String.format("%.3f", sum / allocations.size()),
                String.format("%.1f", sum));
    }

    @Override
    public String getDescription() {
        return String.format(
                "Horizontal Equity Socioeconomic (HE_SOCIO): S = %.1f*V_Income + %.1f*V_Home + %.1f*V_Household",
                w1, w2, w3);
    }
}
