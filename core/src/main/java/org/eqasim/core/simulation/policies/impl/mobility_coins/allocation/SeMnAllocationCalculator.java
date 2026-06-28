package org.eqasim.core.simulation.policies.impl.mobility_coins.allocation;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;

import java.util.HashMap;
import java.util.Map;

/**
 * Spatial Equity and Mobility Needs (SE_MN) allocation.
 *
 * Score: S = W1*Zone_Home + W2*Zone_Work + W3*Zone_Education + W4*PT + W5*Travel_Distance + W6*Travel_Time
 *
 *   Zone_Home/Work/Education – Zonenwert aus agent_params.csv (NaN=0), min-max normalisiert
 *                              (höhere Zonennummer = weiter außen = höherer Score)
 *   PT                       – pt_average_raptor, min-max normalisiert, invertiert
 *                              (niedrigerer Raptor-Wert = besserer ÖPNV = niedrigerer Score)
 *   Travel_Distance          – Summe Distanz (car+car_passenger+pt) aus agent_params.csv, normalisiert
 *   Travel_Time              – Summe Reisezeit (car+car_passenger+pt) aus agent_params.csv, normalisiert
 *
 * Coins je Agent: coins_a = totalCoins × (S_a / Σ S_a)
 */
public class SeMnAllocationCalculator implements AllocationCalculator {

    private static final Logger logger = LogManager.getLogger(SeMnAllocationCalculator.class);

    private final Map<String, AgentParametersPrecomputer.AgentParams> agentParams;
    private final double w1, w2, w3, w4, w5, w6;

    public SeMnAllocationCalculator(
            Map<String, AgentParametersPrecomputer.AgentParams> agentParams,
            double w1, double w2, double w3, double w4, double w5, double w6) {
        this.agentParams = agentParams;
        this.w1 = w1; this.w2 = w2; this.w3 = w3;
        this.w4 = w4; this.w5 = w5; this.w6 = w6;
    }

    @Override
    public Map<Id<Person>, Double> calculateAllocations(Population population, double totalCoins) {

        // --- Pass 1: Rohwerte extrahieren, Min/Max bestimmen ---
        // raw[]: [zoneHome, zoneWork, zoneEdu, ptRaptor (NaN wenn null), distM, timeS]
        Map<String, double[]> raw = new HashMap<>();

        double minZH = Double.MAX_VALUE, maxZH = -Double.MAX_VALUE;
        double minZW = Double.MAX_VALUE, maxZW = -Double.MAX_VALUE;
        double minZE = Double.MAX_VALUE, maxZE = -Double.MAX_VALUE;
        double minPt = Double.MAX_VALUE, maxPt = -Double.MAX_VALUE;
        double minDist = Double.MAX_VALUE, maxDist = -Double.MAX_VALUE;
        double minTime = Double.MAX_VALUE, maxTime = -Double.MAX_VALUE;
        boolean hasPtData = false;

        for (Person person : population.getPersons().values()) {
            String pid = person.getId().toString();
            AgentParametersPrecomputer.AgentParams ap = agentParams.get(pid);
            if (ap == null) continue;

            double zH = parseZone(ap.homeZone);
            double zW = parseZone(ap.workZone);
            double zE = parseZone(ap.educationZone);
            double pt = ap.ptAverageRaptor != null ? (double) ap.ptAverageRaptor : Double.NaN;
            double d  = ap.travelDistanceM;
            double t  = ap.travelTimeS;

            raw.put(pid, new double[]{zH, zW, zE, pt, d, t});

            if (zH < minZH) minZH = zH; if (zH > maxZH) maxZH = zH;
            if (zW < minZW) minZW = zW; if (zW > maxZW) maxZW = zW;
            if (zE < minZE) minZE = zE; if (zE > maxZE) maxZE = zE;
            if (!Double.isNaN(pt)) {
                hasPtData = true;
                if (pt < minPt) minPt = pt; if (pt > maxPt) maxPt = pt;
            }
            if (d < minDist) minDist = d; if (d > maxDist) maxDist = d;
            if (t < minTime) minTime = t; if (t > maxTime) maxTime = t;
        }

        if (!hasPtData) {
            logger.warn("pt_average_raptor ist für alle Agenten null – PT-Komponente neutral (0.5).");
        }

        // --- Pass 2: Scores berechnen ---
        Map<Id<Person>, Double> scores = new HashMap<>();
        double totalScore = 0.0;

        for (Person person : population.getPersons().values()) {
            String pid = person.getId().toString();
            double[] r = raw.get(pid);
            if (r == null) { scores.put(person.getId(), 0.0); continue; }

            double zoneHomeScore  = normalize(r[0], minZH, maxZH);
            double zoneWorkScore  = normalize(r[1], minZW, maxZW);
            double zoneEduScore   = normalize(r[2], minZE, maxZE);
            double ptScore        = hasPtData && !Double.isNaN(r[3])
                                    ? 1.0 - normalize(r[3], minPt, maxPt)
                                    : 0.5;
            double distScore      = normalize(r[4], minDist, maxDist);
            double timeScore      = normalize(r[5], minTime, maxTime);

            double score = w1*zoneHomeScore + w2*zoneWorkScore + w3*zoneEduScore
                         + w4*ptScore + w5*distScore + w6*timeScore;
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

    private double parseZone(String zone) {
        if (zone == null || zone.isBlank() || "NaN".equalsIgnoreCase(zone)) return 0.0;
        try { return Double.parseDouble(zone.trim()); }
        catch (NumberFormatException e) { return 0.0; }
    }

    private double normalize(double value, double min, double max) {
        if (max <= min) return 0.5;
        return (value - min) / (max - min);
    }

    private void logStats(Map<Id<Person>, Double> allocations) {
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE, sum = 0.0;
        for (double v : allocations.values()) {
            if (v < min) min = v; if (v > max) max = v; sum += v;
        }
        logger.info("SE_MN Verteilung: {} Agenten | min={} max={} avg={} total={}",
                allocations.size(),
                String.format("%.3f", min), String.format("%.3f", max),
                String.format("%.3f", sum / allocations.size()),
                String.format("%.1f", sum));
    }

    @Override
    public String getDescription() {
        return String.format(
                "Spatial Equity + Mobility Needs (SE_MN): S = %.1f*ZoneHome + %.1f*ZoneWork + %.1f*ZoneEdu + %.1f*PT_inv + %.1f*Dist + %.1f*Time",
                w1, w2, w3, w4, w5, w6);
    }
}
