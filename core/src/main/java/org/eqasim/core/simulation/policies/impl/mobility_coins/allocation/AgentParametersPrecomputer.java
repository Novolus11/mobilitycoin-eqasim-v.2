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
import org.matsim.core.population.PersonUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.utils.collections.QuadTree;
import org.matsim.facilities.ActivityFacilities;
import org.matsim.facilities.ActivityFacility;
import org.matsim.core.config.groups.FacilitiesConfigGroup;
import org.matsim.households.Household;
import org.matsim.households.Households;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;

import ch.sbb.matsim.routing.pt.raptor.OccupancyData;
import ch.sbb.matsim.routing.pt.raptor.RaptorParameters;
import ch.sbb.matsim.routing.pt.raptor.RaptorStaticConfig;
import ch.sbb.matsim.routing.pt.raptor.RaptorUtils;
import ch.sbb.matsim.routing.pt.raptor.SwissRailRaptor;
import ch.sbb.matsim.routing.pt.raptor.SwissRailRaptorCore;
import ch.sbb.matsim.routing.pt.raptor.SwissRailRaptorData;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Berechnet alle Agenten-Parameter die für die MoCo-Allocation-Schemes benötigt werden
 * und schreibt sie in eine einheitliche CSV-Datei. Jedes Allocation-Scheme liest
 * seine benötigten Spalten aus dieser CSV – keine separate Vorverarbeitung je Scheme.
 *
 * Einmalige Generierung (standalone):
 *   java -Xmx16g -cp bavaria-1.5.0.jar \
 *        org.eqasim.core.simulation.policies.impl.mobility_coins.allocation.AgentParametersPrecomputer \
 *        --config bavaria_1pct_config.xml \
 *        --output agent_params.csv
 *
 * Spalten (alle Schemes benutzen diese eine Datei):
 *   Direkt aus Population-Attributen:
 *     person_id, household_id, age,
 *     has_driving_license, has_pt_subscription, car_availability,
 *     bicycle_availability, income, high_income
 *   Aus Plan abgeleitet:
 *     employed        – true wenn Person Arbeitsaktivitäten hat
 *     education       – true wenn Person Bildungsaktivitäten hat
 *     homeoffice      – "true"/"false"/"NaN" (NaN wenn nicht erwerbstätig)
 *     household_size  – Anzahl Personen mit gleicher householdId
 *   Räumlich berechnet:
 *     pt_home         – PT-Haltestellen im 500 m-Radius um Heimkoordinate
 *     pt_work         – PT-Haltestellen im 500 m-Radius um Arbeitskoordinate (leer wenn keine)
 *     pt_education    – PT-Haltestellen im 500 m-Radius um Bildungskoordinate (leer wenn keine)
 *     home_zone       – Zone der Heimkoordinate (TODO: erfordert Zonen-Shapefile)
 *     work_zone       – Zone der Arbeitskoordinate (TODO)
 *     education_zone  – Zone der Bildungskoordinate (TODO)
 */
public class AgentParametersPrecomputer {

    private static final Logger logger = LogManager.getLogger(AgentParametersPrecomputer.class);

    /** Radius für PT-Haltestellenzählung (Meter). */
    public static final double RADIUS_M = 500.0;

    private final Population population;
    private final QuadTree<TransitStopFacility> stopQuadTree;

    /**
     * ActivityFacilities für Koordinaten-Fallback (Priorität 2).
     * Im Bavaria-Szenario nicht aktiv (facilitiesSource=none), daher meist null.
     */
    private final ActivityFacilities activityFacilities;

    /**
     * Network für Koordinaten-Fallback über Link-Mittelpunkt (Priorität 3).
     * Im Bavaria-Szenario sind Aktivitäten über linkId verankert – dieser Fallback
     * ist daher die Hauptquelle für Koordinaten wenn act.getCoord() null ist.
     */
    private final Network network;

    /**
     * Haushaltsgröße je Person, vorberechnet aus Households-Objekt (bevorzugt)
     * oder aus householdId-Attributzählung (Fallback).
     */
    private final Map<Id<Person>, String> personHouseholdSizes;

    /** TransitSchedule für Stop-Koordinaten-Lookup in der RAPTOR-Ergebnisauswertung. */
    private final TransitSchedule transitSchedule;

    /**
     * Gemeinsam genutztes SwissRailRaptorData-Objekt (read-only, thread-safe).
     * Null wenn im Simulation-Kontext verwendet (kein Config-Objekt verfügbar).
     */
    private final SwissRailRaptorData raptorData;

    /**
     * Thread-lokale SwissRailRaptor-Instanzen.
     * SwissRailRaptorCore hat mutablen Zustand → jeder Thread braucht seine eigene Instanz.
     */
    private final ThreadLocal<SwissRailRaptor> threadLocalRaptor;

    /** RAPTOR-Parameter (read-only nach Erstellung, thread-safe). */
    private final RaptorParameters raptorParameters;

    /** Cache: Starthaltestellenmenge → Anzahl erreichbarer Haltestellen (thread-safe). */
    private final Map<String, Integer> raptorCache = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Durchschnittliche PT-Reisezeit aus dem Baseline-Simulationslauf (Sekunden, 3 Nachkommastellen).
     * 0.0 wenn keine eqasim_trips_moco.csv verfügbar – RAPTOR-Spalten bleiben dann leer.
     */
    private double avgPtTravelTimeSeconds = 0.0;

    /**
     * Auf ganze Sekunden gerundete durchschnittliche PT-Reisezeit (= Math.round(avgPtTravelTimeSeconds)).
     * Dieser Wert wird für alle RAPTOR-Berechnungen verwendet und als Kommentarzeile in die
     * agent_params.csv geschrieben, damit er jederzeit nachschaubar ist.
     */
    private long avgPtTravelTimeRoundedSeconds = 0L;

    /** Für die Verwendung innerhalb der Simulation (Objekte bereits geladen). */
    public AgentParametersPrecomputer(TransitSchedule transitSchedule, Population population) {
        this.population           = population;
        this.stopQuadTree         = buildStopQuadTree(transitSchedule);
        this.activityFacilities   = null;
        this.network              = null;
        this.transitSchedule      = transitSchedule;
        this.personHouseholdSizes = buildHouseholdSizesFromPopulation(population);
        this.raptorData           = null;
        this.threadLocalRaptor    = null;
        this.raptorParameters     = null;
    }

    /** Für die Standalone-Verwendung (lädt alles aus dem Scenario). */
    public AgentParametersPrecomputer(Scenario scenario) {
        this.population           = scenario.getPopulation();
        this.stopQuadTree         = buildStopQuadTree(scenario.getTransitSchedule());
        this.activityFacilities   = scenario.getActivityFacilities();
        this.network              = scenario.getNetwork();
        this.transitSchedule      = scenario.getTransitSchedule();
        this.personHouseholdSizes = buildHouseholdSizesFromHouseholds(scenario.getHouseholds(), scenario.getPopulation());
        this.raptorData           = buildRaptorData(scenario);
        this.threadLocalRaptor    = buildThreadLocalRaptor(this.raptorData, scenario.getConfig());
        this.raptorParameters     = RaptorUtils.createParameters(scenario.getConfig());
    }

    /**
     * Standalone-Verwendung mit externer Pipeline-Haushaltsgrössen-CSV.
     * Die CSV muss die Spalten "household_id" und "household_size" enthalten
     * (z.B. bavaria_1pct_households.csv aus dem Pipeline-Output).
     * Die Original-Haushaltsgrößen bleiben so auch nach dem 1%-Downsampling erhalten.
     */
    public AgentParametersPrecomputer(Scenario scenario, String householdsCsvPath) {
        this.population           = scenario.getPopulation();
        this.stopQuadTree         = buildStopQuadTree(scenario.getTransitSchedule());
        this.activityFacilities   = scenario.getActivityFacilities();
        this.network              = scenario.getNetwork();
        this.transitSchedule      = scenario.getTransitSchedule();
        this.personHouseholdSizes = buildHouseholdSizesFromCsv(householdsCsvPath, scenario.getPopulation());
        this.raptorData           = buildRaptorData(scenario);
        this.threadLocalRaptor    = buildThreadLocalRaptor(this.raptorData, scenario.getConfig());
        this.raptorParameters     = RaptorUtils.createParameters(scenario.getConfig());
    }

    /** Baut SwissRailRaptorData (read-only, thread-safe, einmalig erstellt). */
    private static SwissRailRaptorData buildRaptorData(Scenario scenario) {
        logger.info("Initialisiere SwissRailRaptorData für RAPTOR-Erreichbarkeitsberechnung ...");
        RaptorStaticConfig staticConfig = RaptorUtils.createStaticConfig(scenario.getConfig());
        staticConfig.setOptimization(RaptorStaticConfig.RaptorOptimization.OneToAllRouting);
        SwissRailRaptorData data = SwissRailRaptorData.create(
                scenario.getTransitSchedule(), scenario.getTransitVehicles(),
                staticConfig, scenario.getNetwork(), new OccupancyData());
        logger.info("SwissRailRaptorData bereit.");
        return data;
    }

    /**
     * Baut eine ThreadLocal-Fabrik für SwissRailRaptor-Instanzen.
     * SwissRailRaptorCore hat mutablen internen Zustand → jeder Thread braucht
     * seine eigene Instanz; SwissRailRaptorData wird geteilt (read-only).
     */
    private static ThreadLocal<SwissRailRaptor> buildThreadLocalRaptor(
            SwissRailRaptorData data, org.matsim.core.config.Config config) {
        return ThreadLocal.withInitial(() -> new SwissRailRaptor.Builder(data, config).build());
    }

    /**
     * Lädt Haushaltsgrößen aus der Pipeline-Output-CSV (z.B. bavaria_1pct_households.csv).
     * Erwartet Spalten "household_id" und "household_size".
     * Vorteil: enthält die Original-Haushaltsgrößen vor dem 1%-Downsampling.
     */
    private static Map<Id<Person>, String> buildHouseholdSizesFromCsv(
            String csvPath, Population population) {

        Map<String, String> hhSizes = new HashMap<>();

        try (BufferedReader reader = new BufferedReader(new FileReader(csvPath))) {
            String header = reader.readLine();
            if (header == null) {
                logger.warn("Leere Households-CSV: {}", csvPath);
                return new HashMap<>();
            }
            String[] cols = header.split(";", -1);
            int hhIdIdx = -1, sizeIdx = -1;
            for (int i = 0; i < cols.length; i++) {
                String col = cols[i].trim();
                if ("household_id".equals(col))  hhIdIdx = i;
                if ("household_size".equals(col)) sizeIdx  = i;
            }
            if (hhIdIdx < 0 || sizeIdx < 0) {
                logger.warn("Households-CSV fehlt Spalte 'household_id' oder 'household_size': {}", csvPath);
                return new HashMap<>();
            }
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] parts = line.split(";", -1);
                if (parts.length <= Math.max(hhIdIdx, sizeIdx)) continue;
                String hhId    = parts[hhIdIdx].trim();
                String sizeStr = parts[sizeIdx].trim();
                // Rohwert direkt speichern – "5+" wird nicht verändert
                if (!sizeStr.isEmpty()) hhSizes.put(hhId, sizeStr);
            }
            logger.info("Haushaltsgrößen aus CSV geladen: {} Einträge aus {}.", hhSizes.size(), csvPath);
        } catch (IOException e) {
            logger.error("Fehler beim Lesen der Households-CSV: {}", csvPath, e);
            return new HashMap<>();
        }

        // householdId-Attribut der Person → Größe aus CSV
        Map<Id<Person>, String> result = new HashMap<>();
        for (Person p : population.getPersons().values()) {
            Object hhId = p.getAttributes().getAttribute("householdId");
            if (hhId != null) {
                String size = hhSizes.get(hhId.toString());
                if (size != null) result.put(p.getId(), size);
            }
        }
        return result;
    }

    /**
     * Haushaltsgröße aus Households-Objekt (bavarian Scenario: bavaria_1pct_households.xml.gz).
     * Fallback auf householdId-Attributzählung wenn keine Household-Daten verfügbar.
     */
    private static Map<Id<Person>, String> buildHouseholdSizesFromHouseholds(
            Households households, Population population) {

        if (households != null && !households.getHouseholds().isEmpty()) {
            Map<Id<Person>, String> sizes = new HashMap<>();
            for (Household hh : households.getHouseholds().values()) {
                String size = String.valueOf(hh.getMemberIds().size());
                for (Id<Person> pId : hh.getMemberIds()) {
                    sizes.put(pId, size);
                }
            }
            logger.info("Haushaltsgrößen aus Households-Objekt: {} Haushalte.", households.getHouseholds().size());
            return sizes;
        }

        logger.warn("Keine Household-Daten gefunden – Fallback auf householdId-Attributzählung.");
        return buildHouseholdSizesFromPopulation(population);
    }

    /** Haushaltsgröße aus householdId-Attribut der Personen (Fallback). */
    private static Map<Id<Person>, String> buildHouseholdSizesFromPopulation(Population population) {
        Map<String, Integer> idCounts = new HashMap<>();
        for (Person p : population.getPersons().values()) {
            Object hhId = p.getAttributes().getAttribute("householdId");
            if (hhId != null) idCounts.merge(hhId.toString(), 1, Integer::sum);
        }
        Map<Id<Person>, String> result = new HashMap<>();
        for (Person p : population.getPersons().values()) {
            Object hhId = p.getAttributes().getAttribute("householdId");
            if (hhId != null) {
                Integer count = idCounts.get(hhId.toString());
                if (count != null) result.put(p.getId(), String.valueOf(count));
            }
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // QuadTree-Aufbau
    // -------------------------------------------------------------------------

    private static QuadTree<TransitStopFacility> buildStopQuadTree(TransitSchedule schedule) {
        logger.info("Baue QuadTree über PT-Haltestellen ...");
        Collection<TransitStopFacility> stops = schedule.getFacilities().values();
        if (stops.isEmpty()) {
            throw new IllegalStateException(
                    "TransitSchedule enthält keine Haltestellen – Scenario mit Transit-Schedule laden.");
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
        double buf = RADIUS_M + 1.0;
        QuadTree<TransitStopFacility> tree =
                new QuadTree<>(minX - buf, minY - buf, maxX + buf, maxY + buf);
        for (TransitStopFacility stop : stops) {
            tree.put(stop.getCoord().getX(), stop.getCoord().getY(), stop);
        }
        logger.info("QuadTree bereit: {} Haltestellen.", tree.size());
        return tree;
    }

    // -------------------------------------------------------------------------
    // Hauptberechnung
    // -------------------------------------------------------------------------

    /**
     * Berechnet AgentParams für alle Personen in der Population.
     *
     * @return Map: Personen-ID → AgentParams
     */
    public Map<Id<Person>, AgentParams> compute() {
        return compute(1);
    }

    /**
     * Berechnet AgentParams für alle Personen, optional parallelisiert.
     *
     * @param numThreads Anzahl paralleler Threads (1 = single-threaded)
     */
    public Map<Id<Person>, AgentParams> compute(int numThreads) {
        int total = population.getPersons().size();
        java.util.concurrent.atomic.AtomicInteger done = new java.util.concurrent.atomic.AtomicInteger(0);
        Map<Id<Person>, AgentParams> results = new java.util.concurrent.ConcurrentHashMap<>();

        java.util.function.Consumer<Person> processOne = person -> {
            results.put(person.getId(), computeForPerson(person));
            int d = done.incrementAndGet();
            if (d % 5_000 == 0) {
                logger.info("Fortschritt: {}/{} ({}%)", d, total, (int)(100.0 * d / total));
            }
        };

        if (numThreads <= 1) {
            population.getPersons().values().forEach(processOne);
        } else {
            logger.info("Parallele Berechnung mit {} Threads ...", numThreads);
            java.util.concurrent.ForkJoinPool pool = new java.util.concurrent.ForkJoinPool(numThreads);
            try {
                pool.submit(() ->
                    population.getPersons().values().parallelStream().forEach(processOne)
                ).get();
            } catch (java.util.concurrent.ExecutionException | InterruptedException e) {
                throw new RuntimeException("Fehler bei paralleler AgentParams-Berechnung.", e);
            } finally {
                pool.shutdown();
            }
        }

        logger.info("AgentParams berechnet für {} Personen.", results.size());
        return results;
    }

    private AgentParams computeForPerson(Person person) {

        // --- Direkte Personenattribute ---
        String  personId    = person.getId().toString();
        String  householdId = getStringAttr(person, "householdId", "");
        int     age         = getAge(person);

        // Führerschein: Bavaria-Population speichert Attribut "hasLicense" als String "yes"/"no".
        // PersonUtils.getLicense() liest "license" (anderer Name) – daher direkter Zugriff.
        String  licenseAttr       = (String) person.getAttributes().getAttribute("hasLicense");
        boolean hasDrivingLicense = "yes".equalsIgnoreCase(licenseAttr);

        boolean hasPtSubscription   = getBoolAttrSafe(person, "hasPtSubscription");
        String  carAvailability     = getStringAttr(person, "carAvailability", "");
        String  bicycleAvailability = getStringAttr(person, "bicycleAvailability", "");
        String  income              = getStringAttr(person, "householdIncome", "");

        // highIncome: exakt wie BavariaPredictorUtils.isHighIncome()
        Boolean highIncomeAttr = (Boolean) person.getAttributes().getAttribute("highIncome");
        boolean highIncome     = highIncomeAttr != null && highIncomeAttr;

        // Haushaltsgröße: aus vorberechneter Households-Map (bevorzugt aus Households-Objekt)
        // Wert kann "2", "3", "4", "5+" etc. sein – wird als String durchgereicht
        String householdSize = personHouseholdSizes.getOrDefault(person.getId(), "");

        // --- Plan: Aktivitäten analysieren ---
        Plan plan = person.getSelectedPlan();

        boolean hasWorkActivity      = false;
        boolean hasEducationActivity = false;
        Coord   homeCoord            = null;
        Coord   workCoord            = null;
        Coord   educationCoord       = null;

        if (plan != null) {
            for (var element : plan.getPlanElements()) {
                if (!(element instanceof Activity)) continue;
                Activity act   = (Activity) element;
                String   type  = act.getType();
                // Koordinate direkt oder via ActivityFacility holen
                Coord    coord = getCoord(act);

                if (type.startsWith("home")) {
                    if (homeCoord == null && coord != null) homeCoord = coord;
                } else if (type.startsWith("work")) {
                    // Beschäftigung aus Plan ableiten (unabhängig von Koordinaten)
                    hasWorkActivity = true;
                    if (workCoord == null && coord != null) workCoord = coord;
                } else if (type.startsWith("education")) {
                    hasEducationActivity = true;
                    if (educationCoord == null && coord != null) educationCoord = coord;
                }
            }
        }

        // employed: Person hat Arbeitsaktivitäten im Plan
        boolean employed  = hasWorkActivity;

        // education: Person hat Bildungsaktivitäten im Plan
        boolean education = hasEducationActivity;

        // homeoffice-Logik:
        //   employed=false → "NaN" (nicht erwerbstätig, Konzept nicht anwendbar)
        //   employed=true, keine Arbeitskoordinaten vorhanden → "true"  (Arbeit findet zuhause statt)
        //   employed=true, Arbeitskoordinaten vorhanden       → "false"
        String homeoffice;
        if (!employed) {
            homeoffice = "NaN";
        } else if (workCoord == null) {
            homeoffice = "true";
        } else {
            homeoffice = "false";
        }

        // PT-Haltestellen im 500 m-Radius
        // pt_work und pt_education sind null wenn keine entsprechenden Koordinaten vorliegen
        int     ptHome      = countStopsWithin(homeCoord);
        Integer ptWork      = (workCoord      != null) ? countStopsWithin(workCoord)      : null;
        Integer ptEducation = (educationCoord != null) ? countStopsWithin(educationCoord) : null;

        // pt_average: gerundeter Durchschnitt der vorhandenen Werte (NaN-Werte ausgeschlossen)
        int ptSum   = ptHome;
        int ptCount = 1; // ptHome ist immer vorhanden
        if (ptWork      != null) { ptSum += ptWork;      ptCount++; }
        if (ptEducation != null) { ptSum += ptEducation; ptCount++; }
        int ptAverage = (int) Math.round((double) ptSum / ptCount);

        // Zonen (TODO: Implementierung erfordert Zonen-Shapefile / Lookup-Tabelle)
        String homeZone      = "";
        String workZone      = "";
        String educationZone = "";

        // RAPTOR-basierte PT-Erreichbarkeit:
        // Anzahl eindeutiger Haltestellen erreichbar innerhalb avgPtTravelTimeRoundedSeconds ab 08:00
        // ausgehend von allen Haltestellen im 500-m-Radius (Starthaltestellen mitgezählt).
        // null (→ NaN) wenn keine Koordinate vorhanden oder RAPTOR nicht initialisiert.
        boolean raptorReady = threadLocalRaptor != null && avgPtTravelTimeRoundedSeconds > 0;
        Integer ptHomeRaptor      = raptorReady
                                    ? countReachableStopsViaRaptor(homeCoord) : null;
        Integer ptWorkRaptor      = (raptorReady && workCoord != null)
                                    ? countReachableStopsViaRaptor(workCoord) : null;
        Integer ptEducationRaptor = (raptorReady && educationCoord != null)
                                    ? countReachableStopsViaRaptor(educationCoord) : null;

        Integer ptAverageRaptor = null;
        if (ptHomeRaptor != null) {
            int rSum = ptHomeRaptor, rCnt = 1;
            if (ptWorkRaptor      != null) { rSum += ptWorkRaptor;      rCnt++; }
            if (ptEducationRaptor != null) { rSum += ptEducationRaptor; rCnt++; }
            ptAverageRaptor = (int) Math.round((double) rSum / rCnt);
        }

        return new AgentParams(
                personId, householdId, age, employed, homeoffice, education,
                hasDrivingLicense, hasPtSubscription, carAvailability, bicycleAvailability,
                income, highIncome, householdSize,
                ptHome, ptWork, ptEducation, ptAverage,
                ptHomeRaptor, ptWorkRaptor, ptEducationRaptor, ptAverageRaptor,
                homeZone, workZone, educationZone
        );
    }

    /**
     * Gibt die Koordinate einer Aktivität zurück – drei Fallback-Stufen:
     *   1. Direkte Koordinate auf der Aktivität (act.getCoord())
     *   2. Koordinate über ActivityFacility (facilityId) – im Bavaria-Szenario nicht aktiv
     *   3. Koordinate über Network-Link-Mittelpunkt (linkId) – Hauptquelle im Bavaria-Szenario
     */
    private Coord getCoord(Activity act) {
        // Stufe 1: direkte Koordinate
        if (act.getCoord() != null) return act.getCoord();

        // Stufe 2: Facility-Koordinate (nur wenn Facilities geladen)
        if (activityFacilities != null && act.getFacilityId() != null) {
            ActivityFacility facility = activityFacilities.getFacilities().get(act.getFacilityId());
            if (facility != null) return facility.getCoord();
        }

        // Stufe 3: Mittelpunkt des Network-Links (Bavaria-Szenario: Aktivitäten referenzieren Links)
        if (network != null && act.getLinkId() != null) {
            Link link = network.getLinks().get(act.getLinkId());
            if (link != null) return link.getCoord();
        }

        return null;
    }

    /**
     * Zählt alle eindeutigen PT-Haltestellen erreichbar innerhalb von {@code timeBudgetS} Sekunden
     * ab 08:00 Uhr, ausgehend von allen Haltestellen im {@link #RADIUS_M}-Meter-Radius um coord.
     *
     * Vorgehen:
     *   1. Alle Haltestellen im 500-m-Radius ermitteln (koordinatenbasiert dedupliziert).
     *   2. calcTree() von diesen Starthaltestellen aus (gleichzeitig, entspricht der Vereinigung).
     *   3. Alle RAPTOR-Ergebnisse mit Reisezeit ≤ timeBudgetS zur Ergebnismenge hinzufügen.
     *   4. Die Starthaltestellen selbst immer mitzählen (Abfahrtspunkt, Zeit = 0).
     *   5. Deduplizierung der Ergebnismenge nach Koordinate (wie countStopsWithin).
     *
     * Cache: gleiche Menge an Starthaltestellen → Ergebnis wird gecacht, kein doppelter RAPTOR-Lauf.
     * Gibt 0 zurück wenn coord null, raptor nicht initialisiert oder kein Zeitbudget vorhanden.
     */
    private int countReachableStopsViaRaptor(Coord coord) {
        if (coord == null || threadLocalRaptor == null || avgPtTravelTimeRoundedSeconds <= 0) return 0;

        Collection<TransitStopFacility> nearby =
                stopQuadTree.getDisk(coord.getX(), coord.getY(), RADIUS_M);

        // Starthaltestellen: koordinatenbasiert dedupliziert (wie countStopsWithin)
        Map<String, TransitStopFacility> uniqueStartStops = new LinkedHashMap<>();
        for (TransitStopFacility stop : nearby) {
            Coord c = stop.getCoord();
            uniqueStartStops.putIfAbsent(c.getX() + "_" + c.getY(), stop);
        }
        if (uniqueStartStops.isEmpty()) return 0;

        // Cache-Key: sortierte Starthaltestellenkoordinaten
        String cacheKey = String.join("|", new TreeSet<>(uniqueStartStops.keySet()));

        // computeIfAbsent: thread-safe, kein doppelter RAPTOR-Lauf für denselben Key
        return raptorCache.computeIfAbsent(cacheKey, key -> {
            SwissRailRaptor raptor = threadLocalRaptor.get(); // Thread-lokale Instanz
            double departureTime = 8.0 * 3600.0;             // 08:00 Uhr

            Map<Id<TransitStopFacility>, SwissRailRaptorCore.TravelInfo> tree =
                    raptor.calcTree(uniqueStartStops.values(), departureTime, raptorParameters, null);

            Set<String> reachableCoords = new HashSet<>();

            // Starthaltestellen immer mitzählen (Reisezeit = 0)
            for (TransitStopFacility s : uniqueStartStops.values()) {
                reachableCoords.add(s.getCoord().getX() + "_" + s.getCoord().getY());
            }

            // RAPTOR-Ergebnisse: alle Haltestellen mit Reisezeit ≤ Y Sekunden
            Map<Id<TransitStopFacility>, TransitStopFacility> allFacilities =
                    transitSchedule.getFacilities();
            for (Map.Entry<Id<TransitStopFacility>, SwissRailRaptorCore.TravelInfo> entry : tree.entrySet()) {
                if (entry.getValue().ptArrivalTime - departureTime <= avgPtTravelTimeRoundedSeconds) {
                    TransitStopFacility stop = allFacilities.get(entry.getKey());
                    if (stop != null) {
                        reachableCoords.add(stop.getCoord().getX() + "_" + stop.getCoord().getY());
                    }
                }
            }

            return reachableCoords.size();
        });
    }

    /**
     * Zählt eindeutige physische PT-Haltestellen im {@link #RADIUS_M}-Meter-Radius um coord.
     * Deduplizierung nach exakter Koordinate – identisch zu fairness_indicators.py:
     *   unique_coords = list(set(stop_coords.values()))
     * Mehrere TransitStopFacilities an derselben (x,y)-Position (z.B. verschiedene Linien,
     * Richtungen) zählen so als eine physische Haltestelle.
     */
    private int countStopsWithin(Coord coord) {
        if (coord == null) return 0;
        Collection<TransitStopFacility> nearby =
                stopQuadTree.getDisk(coord.getX(), coord.getY(), RADIUS_M);

        Set<String> uniqueCoords = new HashSet<>();
        for (TransitStopFacility stop : nearby) {
            Coord c = stop.getCoord();
            uniqueCoords.add(c.getX() + "_" + c.getY());
        }
        return uniqueCoords.size();
    }

    // -------------------------------------------------------------------------
    // Hilfsmethoden – Attributzugriff
    // -------------------------------------------------------------------------

    private static int getAge(Person person) {
        Integer age = PersonUtils.getAge(person);
        if (age != null) return age;
        Object obj = person.getAttributes().getAttribute("age");
        if (obj instanceof Integer) return (Integer) obj;
        if (obj != null) {
            try { return Integer.parseInt(obj.toString()); } catch (NumberFormatException ignored) {}
        }
        return 0;
    }

    private static String getStringAttr(Person person, String key, String fallback) {
        Object obj = person.getAttributes().getAttribute(key);
        return obj != null ? obj.toString() : fallback;
    }

    /** Liest Boolean-Attribut exakt wie BavariaPredictorUtils: cast zu Boolean, null → false. */
    private static boolean getBoolAttrSafe(Person person, String key) {
        Boolean val = (Boolean) person.getAttributes().getAttribute(key);
        return val != null && val;
    }

    // -------------------------------------------------------------------------
    // CSV schreiben
    // -------------------------------------------------------------------------

    /** Header-Zeile der generierten CSV – spiegelt exakt die gewünschten Spalten wider. */
    public static final String CSV_HEADER =
            "person_id;household_id;age;employed;homeoffice;education;" +
            "has_driving_license;has_pt_subscription;car_availability;bicycle_availability;" +
            "income;high_income;household_size;" +
            "pt_home;pt_work;pt_education;pt_average;" +
            "pt_home_raptor;pt_work_raptor;pt_education_raptor;pt_average_raptor;" +
            "home_zone;work_zone;education_zone";

    public static void writeResults(Map<Id<Person>, AgentParams> results, String outputPath,
                                    long avgPtTravelTimeRoundedS) throws IOException {
        logger.info("Schreibe AgentParams nach {} ...", outputPath);

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputPath))) {
            if (avgPtTravelTimeRoundedS > 0) {
                writer.write("# avg_pt_travel_time_rounded_s=" + avgPtTravelTimeRoundedS);
                writer.newLine();
            }
            writer.write(CSV_HEADER);
            writer.newLine();

            List<AgentParams> sorted = results.values().stream()
                    .sorted(Comparator.comparing(p -> p.personId))
                    .collect(Collectors.toList());

            for (AgentParams p : sorted) {
                writer.write(p.toCsvRow());
                writer.newLine();
            }
        }
        logger.info("Geschrieben: {} Zeilen nach {}.", results.size(), outputPath);
    }

    // -------------------------------------------------------------------------
    // CSV lesen (wird von Allocation-Calculatoren aufgerufen)
    // -------------------------------------------------------------------------

    /**
     * Liest die von {@link #writeResults} erzeugte CSV und gibt eine Map
     * person_id → AgentParams zurück. Spaltenreihenfolge im Header ist beliebig.
     *
     * @param csvPath Pfad zur agent_params.csv
     * @return Map: person_id (String) → AgentParams
     * @throws IOException wenn die Datei nicht gelesen werden kann
     */
    public static Map<String, AgentParams> readResults(String csvPath) throws IOException {
        Map<String, AgentParams> result = new LinkedHashMap<>();

        try (BufferedReader reader = new BufferedReader(new FileReader(csvPath))) {
            // Kommentarzeilen (# avg_pt_travel_time_rounded_s=...) überspringen,
            // erste Nicht-Kommentarzeile ist der Header
            String header = null;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("#") || line.isBlank()) continue;
                header = line;
                break;
            }
            if (header == null) throw new IOException("Leere CSV-Datei: " + csvPath);

            String[] cols = header.split(";", -1);
            Map<String, Integer> idx = new HashMap<>();
            for (int i = 0; i < cols.length; i++) {
                idx.put(cols[i].trim(), i);
            }

            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] parts = line.split(";", -1);
                AgentParams p = AgentParams.fromCsvRow(parts, idx);
                result.put(p.personId, p);
            }
        }
        logger.info("AgentParams gelesen: {} Personen aus {}.", result.size(), csvPath);
        return result;
    }

    // -------------------------------------------------------------------------
    // AgentParams – Datenklasse mit allen Feldern eines Agenten
    // -------------------------------------------------------------------------

    public static class AgentParams {

        // Direkte Personenattribute
        public final String  personId;
        public final String  householdId;
        public final int     age;
        public final boolean employed;
        public final String  homeoffice;          // "true" / "false" / "NaN"
        public final boolean education;
        public final boolean hasDrivingLicense;
        public final boolean hasPtSubscription;
        public final String  carAvailability;
        public final String  bicycleAvailability;
        public final String  income;
        public final boolean highIncome;
        public final String  householdSize;  // z.B. "2", "3", "5+"

        // Räumlich berechnete Werte
        public final int     ptHome;
        public final Integer ptWork;              // null wenn keine Arbeitsaktivität
        public final Integer ptEducation;         // null wenn keine Bildungsaktivität
        public final int     ptAverage;           // gerundeter Durchschnitt (NaN-Werte ausgeschlossen)

        // RAPTOR-basierte PT-Erreichbarkeit (Logik TODO – aktuell NaN)
        public final Integer ptHomeRaptor;
        public final Integer ptWorkRaptor;
        public final Integer ptEducationRaptor;
        public final Integer ptAverageRaptor;

        public final String  homeZone;
        public final String  workZone;
        public final String  educationZone;

        public AgentParams(
                String personId, String householdId, int age,
                boolean employed, String homeoffice, boolean education,
                boolean hasDrivingLicense, boolean hasPtSubscription,
                String carAvailability, String bicycleAvailability,
                String income, boolean highIncome, String householdSize,
                int ptHome, Integer ptWork, Integer ptEducation, int ptAverage,
                Integer ptHomeRaptor, Integer ptWorkRaptor, Integer ptEducationRaptor, Integer ptAverageRaptor,
                String homeZone, String workZone, String educationZone) {
            this.personId            = personId;
            this.householdId         = householdId;
            this.age                 = age;
            this.employed            = employed;
            this.homeoffice          = homeoffice;
            this.education           = education;
            this.hasDrivingLicense   = hasDrivingLicense;
            this.hasPtSubscription   = hasPtSubscription;
            this.carAvailability     = carAvailability;
            this.bicycleAvailability = bicycleAvailability;
            this.income              = income;
            this.highIncome          = highIncome;
            this.householdSize       = householdSize;
            this.ptHome              = ptHome;
            this.ptWork              = ptWork;
            this.ptEducation         = ptEducation;
            this.ptAverage           = ptAverage;
            this.ptHomeRaptor        = ptHomeRaptor;
            this.ptWorkRaptor        = ptWorkRaptor;
            this.ptEducationRaptor   = ptEducationRaptor;
            this.ptAverageRaptor     = ptAverageRaptor;
            this.homeZone            = homeZone;
            this.workZone            = workZone;
            this.educationZone       = educationZone;
        }

        /** Serialisiert diese Zeile als Semikolon-getrennten CSV-String (kein Zeilenumbruch). */
        public String toCsvRow() {
            return String.join(";",
                    personId,
                    householdId,
                    String.valueOf(age),
                    String.valueOf(employed),
                    homeoffice,                                                  // "true"/"false"/"NaN"
                    String.valueOf(education),
                    String.valueOf(hasDrivingLicense),
                    String.valueOf(hasPtSubscription),
                    carAvailability,
                    bicycleAvailability,
                    income,
                    String.valueOf(highIncome),
                    householdSize,
                    String.valueOf(ptHome),
                    ptWork      != null ? String.valueOf(ptWork)      : "NaN",
                    ptEducation != null ? String.valueOf(ptEducation) : "NaN",
                    String.valueOf(ptAverage),
                    ptHomeRaptor      != null ? String.valueOf(ptHomeRaptor)      : "NaN",
                    ptWorkRaptor      != null ? String.valueOf(ptWorkRaptor)      : "NaN",
                    ptEducationRaptor != null ? String.valueOf(ptEducationRaptor) : "NaN",
                    ptAverageRaptor   != null ? String.valueOf(ptAverageRaptor)   : "NaN",
                    homeZone,
                    workZone,
                    educationZone
            );
        }

        /** Deserialisiert eine CSV-Zeile anhand des Header-Index. */
        public static AgentParams fromCsvRow(String[] parts, Map<String, Integer> idx) {
            return new AgentParams(
                    col(parts, idx, "person_id"),
                    col(parts, idx, "household_id"),
                    colInt(parts, idx, "age"),
                    colBool(parts, idx, "employed"),
                    col(parts, idx, "homeoffice"),                   // String, nicht Boolean
                    colBool(parts, idx, "education"),
                    colBool(parts, idx, "has_driving_license"),
                    colBool(parts, idx, "has_pt_subscription"),
                    col(parts, idx, "car_availability"),
                    col(parts, idx, "bicycle_availability"),
                    col(parts, idx, "income"),
                    colBool(parts, idx, "high_income"),
                    col(parts, idx, "household_size"),
                    colInt(parts, idx, "pt_home"),
                    colIntNullable(parts, idx, "pt_work"),
                    colIntNullable(parts, idx, "pt_education"),
                    colInt(parts, idx, "pt_average"),
                    colIntNullable(parts, idx, "pt_home_raptor"),
                    colIntNullable(parts, idx, "pt_work_raptor"),
                    colIntNullable(parts, idx, "pt_education_raptor"),
                    colIntNullable(parts, idx, "pt_average_raptor"),
                    col(parts, idx, "home_zone"),
                    col(parts, idx, "work_zone"),
                    col(parts, idx, "education_zone")
            );
        }

        private static String col(String[] parts, Map<String, Integer> idx, String name) {
            Integer i = idx.get(name);
            return (i != null && i < parts.length) ? parts[i].trim() : "";
        }

        private static int colInt(String[] parts, Map<String, Integer> idx, String name) {
            try { return Integer.parseInt(col(parts, idx, name)); }
            catch (NumberFormatException e) { return 0; }
        }

        private static Integer colIntNullable(String[] parts, Map<String, Integer> idx, String name) {
            String s = col(parts, idx, name);
            if (s.isEmpty() || "NaN".equals(s)) return null;
            try { return Integer.parseInt(s); }
            catch (NumberFormatException e) { return null; }
        }

        private static boolean colBool(String[] parts, Map<String, Integer> idx, String name) {
            return Boolean.parseBoolean(col(parts, idx, name));
        }
    }

    // -------------------------------------------------------------------------
    // Durchschnittliche PT-Reisezeit aus Baseline-Trips
    // -------------------------------------------------------------------------

    /**
     * Liest eqasim_trips_moco.csv aus dem Baseline-Simulationslauf und berechnet
     * den Mittelwert der travel_time aller PT-Trips (in Sekunden).
     * A→B mit Umstieg = ein Trip (trip-Ebene, nicht leg-Ebene).
     */
    public static double computeAvgPtTravelTime(String csvPath) throws IOException {
        double sum   = 0.0;
        int    count = 0;

        try (BufferedReader reader = new BufferedReader(new FileReader(csvPath))) {
            String header = reader.readLine();
            if (header == null) return 0.0;

            String[] cols = header.split(";", -1);
            int modeIdx = -1, ttIdx = -1;
            for (int i = 0; i < cols.length; i++) {
                if ("mode".equals(cols[i].trim()))        modeIdx = i;
                if ("travel_time".equals(cols[i].trim())) ttIdx   = i;
            }
            if (modeIdx < 0 || ttIdx < 0) {
                logger.warn("eqasim_trips_moco.csv: Spalte 'mode' oder 'travel_time' nicht gefunden.");
                return 0.0;
            }

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] parts = line.split(";", -1);
                if (parts.length <= Math.max(modeIdx, ttIdx)) continue;
                if (!"pt".equals(parts[modeIdx].trim())) continue;
                try {
                    sum += Double.parseDouble(parts[ttIdx].trim());
                    count++;
                } catch (NumberFormatException ignored) {}
            }
        }

        if (count == 0) return 0.0;
        return sum / count;
    }

    // -------------------------------------------------------------------------
    // Standalone-Einstiegspunkt
    // -------------------------------------------------------------------------

    public static void main(String[] args) {
        String configPath = null;
        String outputPath = "agent_params.csv";
        int    numThreads = 1;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--config":  configPath = args[++i]; break;
                case "--output":  outputPath = args[++i]; break;
                case "--threads": numThreads = Integer.parseInt(args[++i]); break;
                case "--help":    printHelp(); return;
            }
        }

        if (configPath == null) {
            System.err.println("Fehler: --config ist erforderlich.");
            printHelp();
            System.exit(1);
        }

        // Pflicht-Prüfungen: beide Dateien müssen im selben Ordner wie die Config liegen.
        // Wird VOR dem Laden des Scenarios geprüft, damit der Abbruch sofort sichtbar ist.

        String autoHouseholdsCsv = configPath.replaceAll("_config\\.xml$", "_households.csv");
        java.io.File householdsFile = new java.io.File(autoHouseholdsCsv);
        if (!householdsFile.exists()) {
            System.err.println(
                    "\n!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!" +
                    "\n!!!Achtung!!! \"" + householdsFile.getName() + "\" muss noch in den \"scenario\"-Ordner eingefügt werden!" +
                    "\n  Erwarteter Pfad: " + householdsFile.getAbsolutePath() +
                    "\n  Quelle: Pipeline-Output (z.B. bavaria_1pct_households.csv)" +
                    "\n  Vorgang wird abgebrochen." +
                    "\n!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
            System.exit(1);
        }

        java.io.File configFile = new java.io.File(configPath);
        java.io.File tripsFile  = new java.io.File(configFile.getParentFile(), "eqasim_trips_moco.csv");
        if (!tripsFile.exists()) {
            System.err.println(
                    "\n!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!" +
                    "\n!!!Achtung!!! \"eqasim_trips_moco.csv\" muss noch in den \"scenario\"-Ordner eingefügt werden!" +
                    "\n  Erwarteter Pfad: " + tripsFile.getAbsolutePath() +
                    "\n  Quelle: Baseline-Simulationsoutput (eqasim_trips_moco.csv)" +
                    "\n  Vorgang wird abgebrochen." +
                    "\n!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
            System.exit(1);
        }

        try {
            logger.info("Lade Scenario aus {} ...", configPath);
            Config config = ConfigUtils.loadConfig(configPath);
            // Facilities erzwingen: Bavaria-Config hat facilitiesSource=none, aber inputFacilitiesFile
            // ist gesetzt. Mit fromFile werden echte Aktivitätskoordinaten geladen statt Link-Mittelpunkte.
            // Identisch zur Methode in fairness_indicators.py (activities.gpkg).
            config.facilities().setFacilitiesSource(FacilitiesConfigGroup.FacilitiesSource.fromFile);
            Scenario scenario = ScenarioUtils.loadScenario(config);
            logger.info("Geladen: {} Personen.", scenario.getPopulation().getPersons().size());

            logger.info("Haushaltsgrößen-CSV gefunden: {}", autoHouseholdsCsv);
            AgentParametersPrecomputer precomputer = new AgentParametersPrecomputer(scenario, autoHouseholdsCsv);

            // eqasim_trips_moco.csv: Existenz wurde bereits vor dem Scenario-Laden geprüft.
            logger.info("Baseline-Trips-CSV gefunden: {}", tripsFile.getAbsolutePath());
            precomputer.avgPtTravelTimeSeconds        = computeAvgPtTravelTime(tripsFile.getAbsolutePath());
            precomputer.avgPtTravelTimeRoundedSeconds = Math.round(precomputer.avgPtTravelTimeSeconds);

            Map<Id<Person>, AgentParams> results = precomputer.compute(numThreads);
            writeResults(results, outputPath, precomputer.avgPtTravelTimeRoundedSeconds);

            logger.info("Fertig. Ausgabe: {}", outputPath);

            double x = precomputer.avgPtTravelTimeSeconds;
            long   y = precomputer.avgPtTravelTimeRoundedSeconds;
            System.out.println();
            System.out.println("------------------------------------------------------------------------");
            System.out.printf("Die durchschnittliche Reisezeit pro Trip beträgt %.3f s (Entspricht %.1f min).%n",
                    x, x / 60.0);
            System.out.printf("Die Berechnungen für die Parameter \"pt_home_raptor\", \"pt_work_raptor\" und" +
                    " \"pt_education_raptor\" erfolgen mit einer durchschnittlichen Reisezeit von" +
                    " %d s pro Trip (Entspricht %.1f min)%n", y, y / 60.0);
            System.out.println("------------------------------------------------------------------------");
        } catch (Exception e) {
            logger.error("Fehler bei der AgentParameter-Vorberechnung.", e);
            System.exit(1);
        }
    }

    private static void printHelp() {
        System.out.println("AgentParametersPrecomputer – berechnet alle MoCo-Agenten-Parameter");
        System.out.println();
        System.out.println("Verwendung:");
        System.out.println("  java -Xmx16g -cp bavaria-1.5.0.jar \\");
        System.out.println("       " + AgentParametersPrecomputer.class.getName());
        System.out.println("       --config <bavaria_1pct_config.xml>");
        System.out.println("       --output <agent_params.csv>");
        System.out.println();
        System.out.println("Pflicht-Dateien im scenario/-Ordner (Abbruch wenn fehlend):");
        System.out.println("  <name>_households.csv  – Pipeline-Output, Spalten household_id;household_size");
        System.out.println("  eqasim_trips_moco.csv       – Baseline-Simulationsoutput, Spalten mode;travel_time");
        System.out.println();
        System.out.println("Ausgabespalten:");
        System.out.println("  " + CSV_HEADER);
    }
}
