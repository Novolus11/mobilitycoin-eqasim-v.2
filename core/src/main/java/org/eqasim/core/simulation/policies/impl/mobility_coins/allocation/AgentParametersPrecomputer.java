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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.geotools.api.feature.simple.SimpleFeature;
import org.geotools.api.feature.simple.SimpleFeatureType;
import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import org.geotools.api.referencing.operation.MathTransform;
import org.geotools.data.simple.SimpleFeatureCollection;
import org.geotools.feature.DefaultFeatureCollection;
import org.geotools.feature.simple.SimpleFeatureBuilder;
import org.geotools.feature.simple.SimpleFeatureTypeBuilder;
import org.geotools.geometry.jts.JTS;
import org.geotools.geopkg.FeatureEntry;
import org.geotools.geopkg.GeoPackage;
import org.geotools.referencing.CRS;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;

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

    /**
     * Löst Zonennummern (1-10) für Koordinaten in EPSG:25832 auf.
     * Null im Simulations-Kontext oder wenn bavaria_zones_setup nicht verfügbar.
     */
    private ZoneResolver zoneResolver = null;

    /**
     * Vorberechnete Baseline-Reisedaten je Person: pid → [distanceM, travelTimeS].
     * Befüllt durch {@link #readPerPersonTravel} aus eqasim_trips_moco.csv.
     * Leere Map (nicht null) wenn kein Baseline-Lauf verfügbar.
     */
    private Map<String, double[]> perPersonTravel = new HashMap<>();

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

        // employed: direkt aus Personenattribut (Bavaria-Population hat "employed" als Boolean-Attribut).
        // Fallback auf Plan-basierte Erkennung wenn Attribut fehlt.
        Object employedObj = person.getAttributes().getAttribute("employed");
        boolean employed = (employedObj != null)
                ? Boolean.parseBoolean(employedObj.toString())
                : hasWorkActivity;

        // education: Person hat Bildungsaktivitäten im Plan
        boolean education = hasEducationActivity;

        // homeoffice-Logik:
        //   employed=false → "NaN" (nicht erwerbstätig, Konzept nicht anwendbar)
        //   employed=true, keine Arbeitskoordinaten im Plan → "true"  (Arbeit findet zuhause statt)
        //   employed=true, Arbeitskoordinaten vorhanden     → "false"
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

        // Zonen: Zonennummer 1-10 für Heimat-/Arbeits-/Bildungskoordinate (NaN wenn keine Koordinate)
        String homeZone      = zoneResolver != null ? zoneResolver.resolveZone(homeCoord)      : "NaN";
        String workZone      = zoneResolver != null ? zoneResolver.resolveZone(workCoord)      : "NaN";
        String educationZone = zoneResolver != null ? zoneResolver.resolveZone(educationCoord) : "NaN";

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

        double[] travelData    = perPersonTravel.getOrDefault(personId, new double[2]);
        double travelDistanceM = travelData[0];
        double travelTimeS     = travelData[1];

        return new AgentParams(
                personId, householdId, age, employed, homeoffice, education,
                hasDrivingLicense, hasPtSubscription, carAvailability, bicycleAvailability,
                income, highIncome, householdSize,
                ptHome, ptWork, ptEducation, ptAverage,
                ptHomeRaptor, ptWorkRaptor, ptEducationRaptor, ptAverageRaptor,
                homeZone, workZone, educationZone,
                travelDistanceM, travelTimeS
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
            "home_zone;work_zone;education_zone;" +
            "travel_distance;travel_time";

    public static void writeResults(Map<Id<Person>, AgentParams> results, String outputPath,
                                    long avgPtTravelTimeRoundedS) throws IOException {
        logger.info("Schreibe AgentParams nach {} ...", outputPath);

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputPath))) {
            String header = CSV_HEADER;
            if (avgPtTravelTimeRoundedS > 0) {
                header += ";avg_pt_travel_time_rounded_s=" + avgPtTravelTimeRoundedS;
            }
            writer.write(header);
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
            // Zeile 1 ist der Header; etwaige Kommentarzeilen (#) werden übersprungen
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
        public final double  travelDistanceM; // Summe Distanz (car+car_passenger+pt) aus Baseline in Metern
        public final double  travelTimeS;     // Summe Reisezeit (car+car_passenger+pt) aus Baseline in Sekunden

        public AgentParams(
                String personId, String householdId, int age,
                boolean employed, String homeoffice, boolean education,
                boolean hasDrivingLicense, boolean hasPtSubscription,
                String carAvailability, String bicycleAvailability,
                String income, boolean highIncome, String householdSize,
                int ptHome, Integer ptWork, Integer ptEducation, int ptAverage,
                Integer ptHomeRaptor, Integer ptWorkRaptor, Integer ptEducationRaptor, Integer ptAverageRaptor,
                String homeZone, String workZone, String educationZone,
                double travelDistanceM, double travelTimeS) {
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
            this.travelDistanceM     = travelDistanceM;
            this.travelTimeS         = travelTimeS;
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
                    educationZone,
                    String.valueOf((long) Math.round(travelDistanceM)),
                    String.valueOf((long) Math.round(travelTimeS))
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
                    col(parts, idx, "education_zone"),
                    colDouble(parts, idx, "travel_distance"),
                    colDouble(parts, idx, "travel_time")
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

        private static double colDouble(String[] parts, Map<String, Integer> idx, String name) {
            String s = col(parts, idx, name);
            if (s.isEmpty() || "NaN".equals(s)) return 0.0;
            try { return Double.parseDouble(s); }
            catch (NumberFormatException e) { return 0.0; }
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

    /**
     * Liest eine Baseline-Trips/Legs-CSV und aggregiert Distanz (m) und Reisezeit (s)
     * je Person für die Modi car, car_passenger und pt.
     * Spalten: person_id (oder agent_id), mode,
     *          vehicle_distance|routed_distance|traveled_distance, travel_time.
     * Fehlende Spalten werden mit Warnung ignoriert (Wert bleibt 0).
     */
    public static Map<String, double[]> readPerPersonTravel(String csvPath) throws IOException {
        Map<String, double[]> result = new HashMap<>();

        try (BufferedReader reader = new BufferedReader(new FileReader(csvPath))) {
            String header = reader.readLine();
            if (header == null) return result;

            String[] cols = header.split(";", -1);
            int personIdIdx = -1, modeIdx = -1, distIdx = -1, timeIdx = -1;
            for (int i = 0; i < cols.length; i++) {
                switch (cols[i].trim()) {
                    case "person_id":
                    case "agent_id":           personIdIdx = i; break;
                    case "mode":               modeIdx     = i; break;
                    case "vehicle_distance":
                    case "routed_distance":
                    case "traveled_distance":  distIdx     = i; break;
                    case "travel_time":        timeIdx     = i; break;
                }
            }

            if (personIdIdx < 0 || modeIdx < 0) {
                logger.warn("readPerPersonTravel: Spalten 'person_id'/'mode' nicht in {} – travel_distance/travel_time=0.", csvPath);
                return result;
            }
            if (distIdx < 0) logger.warn("readPerPersonTravel: Keine Distanz-Spalte gefunden – travel_distance=0.");
            if (timeIdx < 0) logger.warn("readPerPersonTravel: Spalte 'travel_time' nicht gefunden – travel_time=0.");

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] parts = line.split(";", -1);
                if (parts.length <= Math.max(personIdIdx, modeIdx)) continue;
                String mode = parts[modeIdx].trim();
                if (!"car".equals(mode) && !"car_passenger".equals(mode) && !"pt".equals(mode)) continue;

                String pid = parts[personIdIdx].trim();
                double[] vals = result.computeIfAbsent(pid, k -> new double[2]);
                if (distIdx >= 0 && distIdx < parts.length) {
                    try { vals[0] += Double.parseDouble(parts[distIdx].trim()); }
                    catch (NumberFormatException ignored) {}
                }
                if (timeIdx >= 0 && timeIdx < parts.length) {
                    try { vals[1] += Double.parseDouble(parts[timeIdx].trim()); }
                    catch (NumberFormatException ignored) {}
                }
            }
        }

        logger.info("readPerPersonTravel: {} Agenten mit Baseline-Reisedaten aus {}.", result.size(), csvPath);
        return result;
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

        java.io.File configFile  = new java.io.File(configPath);
        java.io.File scenarioDir = configFile.getParentFile();
        java.io.File tripsFile   = new java.io.File(scenarioDir, "eqasim_trips_moco.csv");
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

        java.io.File zonesSetupDir = new java.io.File(scenarioDir, "bavaria_zones_setup");
        java.io.File zonesGpkgFile = new java.io.File(zonesSetupDir, "new_bavaria_zones.gpkg");
        java.io.File pythonScript  = new java.io.File(zonesSetupDir, "new_bavaria_zones.py");

        if (!zonesSetupDir.exists() || !zonesSetupDir.isDirectory()) {
            System.err.println(
                    "\n!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!" +
                    "\n!!!Achtung!!! Der Ordner \"bavaria_zones_setup\" fehlt im \"scenario\"-Ordner!" +
                    "\n  Erwarteter Pfad: " + zonesSetupDir.getAbsolutePath() +
                    "\n  Vorgang wird abgebrochen." +
                    "\n!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
            System.exit(1);
        }
        if (!pythonScript.exists()) {
            System.err.println(
                    "\n!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!" +
                    "\n!!!Achtung!!! \"new_bavaria_zones.py\" fehlt im \"bavaria_zones_setup\"-Ordner!" +
                    "\n  Erwarteter Pfad: " + pythonScript.getAbsolutePath() +
                    "\n  Vorgang wird abgebrochen." +
                    "\n!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
            System.exit(1);
        }

        // GPKG nur einmalig erstellen: Python-Skript automatisch ausführen wenn GPKG fehlt.
        // Nutzt conda -n bavaria, da geopandas nur in dieser Umgebung vorhanden ist.
        if (!zonesGpkgFile.exists()) {
            logger.info("Zonen-GPKG fehlt – führe Python-Skript aus: {}", pythonScript.getAbsolutePath());

            // Bekannte conda-Pfade auf diesem System
            String userProfile = System.getProperty("user.home");
            String[] condaCandidates = {
                userProfile + "\\anaconda3\\Scripts\\conda.exe",
                userProfile + "\\miniconda3\\Scripts\\conda.exe",
                "C:\\ProgramData\\anaconda3\\Scripts\\conda.exe",
                "C:\\ProgramData\\miniconda3\\Scripts\\conda.exe",
            };

            boolean pyOk = false;
            for (String condaPath : condaCandidates) {
                java.io.File condaExe = new java.io.File(condaPath);
                if (!condaExe.exists()) continue;
                logger.info("Versuche: {} run -n bavaria python {}", condaPath, pythonScript.getName());
                try {
                    ProcessBuilder pb = new ProcessBuilder(
                            condaPath, "run", "-n", "bavaria", "python", pythonScript.getName());
                    pb.directory(zonesSetupDir);
                    pb.redirectErrorStream(true);
                    pb.inheritIO();
                    int exitCode = pb.start().waitFor();
                    if (exitCode == 0 && zonesGpkgFile.exists()) {
                        logger.info("Python-Skript erfolgreich abgeschlossen (conda -n bavaria).");
                        pyOk = true;
                        break;
                    }
                } catch (Exception e) {
                    logger.warn("conda-Aufruf fehlgeschlagen ({}): {}", condaPath, e.getMessage());
                }
            }
            if (!pyOk || !zonesGpkgFile.exists()) {
                System.err.println(
                        "\n!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!" +
                        "\n!!!Achtung!!! GPKG konnte nicht erstellt werden!" +
                        "\n  Bitte Python-Skript manuell ausführen (conda -n bavaria):" +
                        "\n    cd " + zonesSetupDir.getAbsolutePath() +
                        "\n    conda run -n bavaria python new_bavaria_zones.py" +
                        "\n!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
                System.exit(1);
            }
        } else {
            logger.info("Zonen-GPKG bereits vorhanden – Python-Skript wird übersprungen.");
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

            // Per-Person-Reisedaten (Distanz + Zeit für car/car_passenger/pt) aus derselben Datei
            precomputer.perPersonTravel = readPerPersonTravel(tripsFile.getAbsolutePath());
            if (precomputer.perPersonTravel.isEmpty()) {
                logger.warn("Keine per-Person-Reisedaten gefunden – travel_distance/travel_time=0 für alle Agenten.");
            }

            // Bavaria-Zonen aus Python-generierter WKT-CSV laden
            precomputer.zoneResolver = ZoneResolver.create(zonesSetupDir.getAbsolutePath());

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
        System.out.println("  <name>_households.csv        – Pipeline-Output, Spalten household_id;household_size");
        System.out.println("  eqasim_trips_moco.csv        – Baseline-Simulationsoutput, Spalten mode;travel_time");
        System.out.println("  bavaria_zones_setup/         – Ordner mit Zonen-GeoJSON-Dateien");
        System.out.println("    München_Stadtgrenze.geojson  – Stadtgrenze München (EPSG:4326)");
        System.out.println("    Oberbayern_Grenze.geojson    – Grenze Oberbayern (EPSG:4326)");
        System.out.println();
        System.out.println("Ausgabespalten:");
        System.out.println("  " + CSV_HEADER);
    }

    // =========================================================================
    // ZoneResolver – Zonen-Lookup für Koordinaten in EPSG:25832
    // =========================================================================

    /**
     * Berechnet 10 Zonen für Oberbayern aus GeoJSON-Grenzlinien und weist
     * MATSim-Koordinaten (EPSG:25832) ihrer Zone zu.
     *
     * Zonen-Definition (alle Geometrien werden an Oberbayern-Grenze abgeschnitten):
     *   Zone  1 – 5km-Kreis um Marienplatz (München-Innenstadt)
     *   Zone  2 – Stadtgebiet München außerhalb Zone 1
     *   Zone  3 –  0-10km außerhalb Münchner Stadtgrenze
     *   Zone  4 – 10-20km außerhalb Stadtgrenze
     *   Zone  5 – 20-30km außerhalb Stadtgrenze
     *   Zone  6 – 30-40km außerhalb Stadtgrenze
     *   Zone  7 – 40-50km außerhalb Stadtgrenze
     *   Zone  8 – 50-60km außerhalb Stadtgrenze
     *   Zone  9 – 60-70km außerhalb Stadtgrenze
     *   Zone 10 – Übriges Oberbayern (>70km außerhalb Stadtgrenze)
     */
    /**
     * Lädt die 10 vorberechneten Zonen-Polygone aus der Python-generierten
     * zones_wkt.csv und weist Koordinaten (EPSG:25832) per PreparedGeometry zu.
     *
     * Die Zonen werden einmalig in Python (new_bavaria_zones.py) mit Shapely/GeoPandas
     * berechnet und als WKT-CSV exportiert. Java liest diese Datei und erstellt
     * PreparedGeometry-Objekte für schnelle, thread-sichere Punkt-in-Polygon-Tests.
     */
    static class ZoneResolver {

        private final GeometryFactory factory;
        // PreparedGeometry je Zone – thread-sicher nach Initialisierung (read-only)
        private final org.locationtech.jts.geom.prep.PreparedGeometry[] preparedZones;

        private ZoneResolver(Geometry[] zones, GeometryFactory factory) {
            this.factory = factory;
            this.preparedZones = new org.locationtech.jts.geom.prep.PreparedGeometry[10];
            for (int i = 0; i < zones.length; i++) {
                if (zones[i] != null && !zones[i].isEmpty()) {
                    preparedZones[i] = org.locationtech.jts.geom.prep.PreparedGeometryFactory.prepare(zones[i]);
                    logger.info("Zone {} geladen: {} m²", i + 1, (long) zones[i].getArea());
                } else {
                    logger.warn("Zone {}: Geometrie fehlt oder leer!", i + 1);
                    preparedZones[i] = null;
                }
            }
        }

        /**
         * Lädt die 10 Zonen-Polygone aus der Python-generierten new_bavaria_zones.gpkg.
         * Die GPKG enthält Layer "zones" mit Spalten "zone" (int, 1-10) und "geometry" (EPSG:25832).
         */
        static ZoneResolver create(String zonesSetupDir) throws Exception {
            java.io.File gpkgFile = new java.io.File(zonesSetupDir, "new_bavaria_zones.gpkg");
            logger.info("Lade Zonen aus GPKG: {}", gpkgFile.getAbsolutePath());

            GeometryFactory factory = new GeometryFactory();
            Geometry[] zones = new Geometry[10];

            // GeoPackage direkt öffnen (kein DataStoreFinder nötig – GeoPackage ist bereits importiert)
            GeoPackage gpkg = new GeoPackage(gpkgFile);
            try {
                for (FeatureEntry entry : gpkg.features()) {
                    if (!"zones".equals(entry.getTableName())) continue;
                    try (var reader = gpkg.reader(entry, null, null)) {
                        while (reader.hasNext()) {
                            SimpleFeature feat = reader.next();
                            Object zoneAttr = feat.getAttribute("zone");
                            if (zoneAttr == null) continue;
                            int z = ((Number) zoneAttr).intValue();
                            if (z >= 1 && z <= 10) {
                                zones[z - 1] = (Geometry) feat.getDefaultGeometry();
                            }
                        }
                    }
                }
            } finally {
                gpkg.close();
            }

            long loaded = java.util.Arrays.stream(zones)
                    .filter(g -> g != null && !g.isEmpty()).count();
            logger.info("{}/10 Zonen aus GPKG geladen.", loaded);

            return new ZoneResolver(zones, factory);
        }

        /** Gibt Zonennummer 1-10 zurück, oder "NaN" wenn keine Zone passt. */
        String resolveZone(Coord coord) {
            if (coord == null) return "NaN";
            Point point = factory.createPoint(new Coordinate(coord.getX(), coord.getY()));
            for (int i = 0; i < preparedZones.length; i++) {
                if (preparedZones[i] != null && preparedZones[i].covers(point)) {
                    return String.valueOf(i + 1);
                }
            }
            return "NaN";
        }
    }
}
