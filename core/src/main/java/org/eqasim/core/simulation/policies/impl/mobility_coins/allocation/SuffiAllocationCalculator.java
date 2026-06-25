package org.eqasim.core.simulation.policies.impl.mobility_coins.allocation;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.pt.transitSchedule.api.TransitSchedule;

import java.util.HashMap;
import java.util.Map;

/**
 * Sufficientarianism (SUFFI) allocation scheme.
 *
 * PT stop counts (pt_home, pt_work, pt_education) werden beim ersten Aufruf von
 * calculateAllocations() automatisch berechnet und gecached. Die Berechnung
 * läuft einmalig beim Simulationsstart – kein separater Preprocessing-Schritt nötig.
 *
 * TODO: Formel einfügen sobald die SUFFI-Logik feststeht.
 */
public class SuffiAllocationCalculator implements AllocationCalculator {

    private static final Logger logger = LogManager.getLogger(SuffiAllocationCalculator.class);

    private final TransitSchedule transitSchedule;

    // PT stop counts, berechnet beim ersten calculateAllocations()-Aufruf
    private Map<Id<Person>, PtStopsPrecomputer.PtStopCounts> ptStopCounts = null;

    public SuffiAllocationCalculator(TransitSchedule transitSchedule) {
        this.transitSchedule = transitSchedule;
    }

    @Override
    public Map<Id<Person>, Double> calculateAllocations(Population population, double totalCoins) {
        // Einmalige Vorberechnung beim ersten Aufruf
        if (ptStopCounts == null) {
            logger.info("SUFFI: Berechne PT-Haltestellen im 500m-Radius für alle Agenten ...");
            PtStopsPrecomputer precomputer = new PtStopsPrecomputer(transitSchedule, population);
            ptStopCounts = precomputer.compute();
            logger.info("SUFFI: PT-Haltestellen-Berechnung abgeschlossen ({} Agenten).", ptStopCounts.size());
        }

        Map<Id<Person>, Double> allocations = new HashMap<>();

        // TODO: SUFFI-Formel implementieren
        // Verfügbare Werte je Agent:
        //   ptStopCounts.get(personId).ptHome       – Haltestellen im 500m-Radius ums Zuhause
        //   ptStopCounts.get(personId).ptWork       – Haltestellen im 500m-Radius um den Arbeitsort
        //   ptStopCounts.get(personId).ptEducation  – Haltestellen im 500m-Radius um den Bildungsort

        return allocations;
    }

    @Override
    public String getDescription() {
        return "Sufficientarianism (SUFFI): TODO";
    }
}
