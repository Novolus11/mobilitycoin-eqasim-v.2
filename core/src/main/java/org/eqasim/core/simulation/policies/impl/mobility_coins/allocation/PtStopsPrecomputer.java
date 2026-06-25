package org.eqasim.core.simulation.policies.impl.mobility_coins.allocation;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.Population;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.collections.QuadTree;
import org.matsim.core.utils.geometry.CoordUtils;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Pre-computes per-agent PT stop counts within a 500 m radius for three activity types:
 *   pt_home       – stops near the home activity location
 *   pt_work       – stops near the work activity location (0 if no work activity)
 *   pt_education  – stops near the education activity location (0 if none)
 *
 * These values are written to a CSV and can be read by allocation calculators
 * that need them as inputs (e.g. SuffiAllocationCalculator).
 *
 * Usage (standalone):
 *   java -cp bavaria-1.5.0.jar \
 *        org.eqasim.core.simulation.policies.impl.mobility_coins.allocation.PtStopsPrecomputer \
 *        --config bavaria_1pct_config.xml \
 *        --output pt_stops.csv
 */
public class PtStopsPrecomputer {

    private static final Logger logger = LogManager.getLogger(PtStopsPrecomputer.class);

    /** Radius in metres used for all stop-count queries. */
    public static final double RADIUS_M = 500.0;

    private final Population population;
    private final QuadTree<TransitStopFacility> stopQuadTree;

    /** Verwendet innerhalb der Simulation (TransitSchedule + Population bereits geladen). */
    public PtStopsPrecomputer(TransitSchedule transitSchedule, Population population) {
        this.population = population;
        this.stopQuadTree = buildStopQuadTree(transitSchedule);
    }

    /** Verwendet als Standalone-Tool (lädt alles aus dem Scenario). */
    public PtStopsPrecomputer(Scenario scenario) {
        this.population = scenario.getPopulation();
        this.stopQuadTree = buildStopQuadTree(scenario.getTransitSchedule());
    }

    // -------------------------------------------------------------------------
    // QuadTree construction
    // -------------------------------------------------------------------------

    private static QuadTree<TransitStopFacility> buildStopQuadTree(TransitSchedule schedule) {
        logger.info("Building QuadTree over PT stops ...");

        Collection<TransitStopFacility> stops = schedule.getFacilities().values();
        if (stops.isEmpty()) {
            throw new IllegalStateException(
                    "Transit schedule contains no stops – make sure the scenario was loaded with a transit schedule.");
        }

        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
        double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;

        for (TransitStopFacility stop : stops) {
            Coord c = stop.getCoord();
            if (c.getX() < minX) minX = c.getX();
            if (c.getX() > maxX) maxX = c.getX();
            if (c.getY() < minY) minY = c.getY();
            if (c.getY() > maxY) maxY = c.getY();
        }

        // Add a small buffer so boundary points are safely inside the tree bounds
        double buffer = RADIUS_M + 1.0;
        QuadTree<TransitStopFacility> tree =
                new QuadTree<>(minX - buffer, minY - buffer, maxX + buffer, maxY + buffer);

        for (TransitStopFacility stop : stops) {
            tree.put(stop.getCoord().getX(), stop.getCoord().getY(), stop);
        }

        logger.info("QuadTree built with {} PT stops.", tree.size());
        return tree;
    }

    // -------------------------------------------------------------------------
    // Main computation
    // -------------------------------------------------------------------------

    /**
     * Compute pt_home / pt_work / pt_education for every person.
     *
     * @return Map: person ID → PtStopCounts record
     */
    public Map<Id<Person>, PtStopCounts> compute() {
        Map<Id<Person>, PtStopCounts> results = new LinkedHashMap<>();
        int total = population.getPersons().size();
        int done = 0;

        for (Person person : population.getPersons().values()) {
            results.put(person.getId(), computeForPerson(person));
            done++;
            if (done % 10_000 == 0) {
                logger.info("Progress: {}/{} ({:.1f} %)", done, total, 100.0 * done / total);
            }
        }

        logger.info("PT stop counts computed for {} persons.", results.size());
        return results;
    }

    private PtStopCounts computeForPerson(Person person) {
        Plan plan = person.getSelectedPlan();
        if (plan == null) {
            return new PtStopCounts(0, 0, 0);
        }

        Coord homeCoord = null;
        Coord workCoord = null;
        Coord educationCoord = null;

        for (var element : plan.getPlanElements()) {
            if (!(element instanceof Activity)) continue;
            Activity act = (Activity) element;
            String type = act.getType();
            Coord coord = act.getCoord();
            if (coord == null) continue;

            if (type.startsWith("home") && homeCoord == null) {
                homeCoord = coord;
            } else if (type.startsWith("work") && workCoord == null) {
                workCoord = coord;
            } else if (type.startsWith("education") && educationCoord == null) {
                educationCoord = coord;
            }
        }

        int ptHome      = countStopsWithin(homeCoord);
        int ptWork      = countStopsWithin(workCoord);
        int ptEducation = countStopsWithin(educationCoord);

        return new PtStopCounts(ptHome, ptWork, ptEducation);
    }

    /** Count PT stops within {@link #RADIUS_M} metres of {@code coord}; returns 0 if coord is null. */
    private int countStopsWithin(Coord coord) {
        if (coord == null) return 0;
        Collection<TransitStopFacility> nearby =
                stopQuadTree.getDisk(coord.getX(), coord.getY(), RADIUS_M);
        return nearby.size();
    }

    // -------------------------------------------------------------------------
    // CSV output
    // -------------------------------------------------------------------------

    public static void writeResults(Map<Id<Person>, PtStopCounts> results, String outputPath)
            throws IOException {
        logger.info("Writing PT stop counts to {} ...", outputPath);

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputPath))) {
            writer.write("person_id;pt_home;pt_work;pt_education\n");

            List<Map.Entry<Id<Person>, PtStopCounts>> sorted = results.entrySet().stream()
                    .sorted(Comparator.comparing(e -> e.getKey().toString()))
                    .collect(Collectors.toList());

            for (Map.Entry<Id<Person>, PtStopCounts> entry : sorted) {
                PtStopCounts c = entry.getValue();
                writer.write(entry.getKey().toString());
                writer.write(";");
                writer.write(String.valueOf(c.ptHome));
                writer.write(";");
                writer.write(String.valueOf(c.ptWork));
                writer.write(";");
                writer.write(String.valueOf(c.ptEducation));
                writer.write("\n");
            }
        }

        logger.info("Wrote {} rows to {}.", results.size(), outputPath);

        // Summary statistics
        IntSummaryStatistics homeStats  = results.values().stream().mapToInt(c -> c.ptHome).summaryStatistics();
        IntSummaryStatistics workStats  = results.values().stream().mapToInt(c -> c.ptWork).summaryStatistics();
        IntSummaryStatistics eduStats   = results.values().stream().mapToInt(c -> c.ptEducation).summaryStatistics();

        logger.info("pt_home      – min:{} max:{} avg:{:.2f}", homeStats.getMin(), homeStats.getMax(), homeStats.getAverage());
        logger.info("pt_work      – min:{} max:{} avg:{:.2f}", workStats.getMin(), workStats.getMax(), workStats.getAverage());
        logger.info("pt_education – min:{} max:{} avg:{:.2f}", eduStats.getMin(),  eduStats.getMax(),  eduStats.getAverage());
    }

    // -------------------------------------------------------------------------
    // Simple data record
    // -------------------------------------------------------------------------

    public static class PtStopCounts {
        public final int ptHome;
        public final int ptWork;
        public final int ptEducation;

        public PtStopCounts(int ptHome, int ptWork, int ptEducation) {
            this.ptHome      = ptHome;
            this.ptWork      = ptWork;
            this.ptEducation = ptEducation;
        }
    }

    // -------------------------------------------------------------------------
    // Standalone entry point
    // -------------------------------------------------------------------------

    public static void main(String[] args) {
        String configPath = null;
        String outputPath = "pt_stops.csv";

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--config":  configPath = args[++i]; break;
                case "--output":  outputPath = args[++i]; break;
                case "--help":    printHelp(); return;
            }
        }

        if (configPath == null) {
            System.err.println("Error: --config is required.");
            printHelp();
            System.exit(1);
        }

        try {
            logger.info("Loading scenario from {} ...", configPath);
            Config config = ConfigUtils.loadConfig(configPath);
            Scenario scenario = ScenarioUtils.loadScenario(config);
            logger.info("Loaded {} persons.", scenario.getPopulation().getPersons().size());

            PtStopsPrecomputer precomputer = new PtStopsPrecomputer(scenario);
            Map<Id<Person>, PtStopCounts> results = precomputer.compute();
            writeResults(results, outputPath);

            logger.info("Done.");
        } catch (Exception e) {
            logger.error("Error during PT stop precomputation.", e);
            System.exit(1);
        }
    }

    private static void printHelp() {
        System.out.println("PtStopsPrecomputer – count PT stops within 500 m of home/work/education");
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  java -Xmx16g -cp bavaria-1.5.0.jar \\");
        System.out.println("       " + PtStopsPrecomputer.class.getName());
        System.out.println("       --config <bavaria_1pct_config.xml>");
        System.out.println("       --output <pt_stops.csv>");
        System.out.println();
        System.out.println("Output columns: person_id;pt_home;pt_work;pt_education");
    }
}
