package com.example.slot;

import ai.timefold.solver.core.api.score.buildin.hardsoft.HardSoftScore;
import ai.timefold.solver.core.api.solver.SolutionManager;
import ai.timefold.solver.core.api.solver.Solver;
import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.api.solver.event.BestSolutionChangedEvent;
import ai.timefold.solver.core.config.solver.SolverConfig;
import com.example.slot.config.SolverConfigFactory;
import com.example.slot.domain.Order;
import com.example.slot.domain.ShiftBucket;
import com.example.slot.domain.SlotSchedule;
import com.example.slot.solver.ProgressTracker;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Enhanced App with real-time progress tracking during optimization.
 * Uses SolverManager to monitor intermediate solutions.
 */
public class App {

    // Configuration for CSV output
    private static final String CSV_BASE_DIR = "csv_output";
    private static final String RUN_VERSION = "v3"; // Different version for clustered data generation
    private static final String CSV_OUTPUT_DIR = CSV_BASE_DIR + "/" + RUN_VERSION;

    private static final String FILE_INPUT_ORDERS = CSV_OUTPUT_DIR + "/input_orders.csv";
    private static final String FILE_INPUT_RIDERS = CSV_OUTPUT_DIR + "/input_riders.csv";
    private static final String FILE_OUTPUT_ASSIGNMENTS = CSV_OUTPUT_DIR + "/output_assignments.csv";
    private static final String FILE_OUTPUT_SHIFT_SUMMARY = CSV_OUTPUT_DIR + "/output_shift_summary.csv";
    private static final String FILE_OUTPUT_DAILY_SUMMARY = CSV_OUTPUT_DIR + "/output_daily_summary.csv";
    private static final String FILE_PROGRESS_LOG = CSV_OUTPUT_DIR + "/progress_log.csv";

    private static final ProgressTracker progressTracker = new ProgressTracker();
    private static final AtomicReference<SlotSchedule> bestSolutionSoFar = new AtomicReference<>();
    private static final List<String> progressLog = new ArrayList<>();

    private static String csvEscape(String s) {
        if (s == null) return "";
        boolean needQuotes = s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r");
        String escaped = s.replace("\"", "\"\"");
        return needQuotes ? "\"" + escaped + "\"" : escaped;
    }

    private static double safePercent(int numerator, int denominator) {
        if (denominator <= 0) return 0.0;
        return (numerator * 100.0) / denominator;
    }

    private static void ensureCsvDirectoryExists() {
        try {
            Path csvDir = Paths.get(CSV_OUTPUT_DIR);
            if (!Files.exists(csvDir)) {
                Files.createDirectories(csvDir);
                System.out.println("Created CSV output directory: " + CSV_OUTPUT_DIR);
            }
        } catch (IOException e) {
            System.err.println("Failed to create CSV output directory: " + e.getMessage());
        }
    }

    public static void main(String[] args) {

        var schedule = generateSampleProblem();

        System.out.println("🚀 Starting TimeFold optimization with progress tracking...");
        System.out.println("📊 Problem: " + schedule.getOrderList().size() + " orders, " +
                schedule.getShiftList().size() + " shift buckets");
        System.out.println("⏱️  Phases: Construction → Hill Climbing → Tabu Search → Hill Climbing");
        System.out.println("─".repeat(80));


        // Ensure CSV output directory exists
        ensureCsvDirectoryExists();

        // Export input to CSV
        exportInputToCSV(schedule);
        prettyPrintInput(schedule);

        // Solve with progress tracking
        SlotSchedule solution = solveWithProgress(schedule);

        // Export output to CSV
        exportOutputToCSV(solution);
        exportProgressLog();
        prettyPrintOutput(solution);

        if (!solution.getScore().isFeasible()) {
            System.err.println("Solution is not feasible. Try increasing capacities or reducing orders.");
            System.exit(1);
        }
    }

    /**
     * Solves the problem using SolverManager with real-time progress tracking.
     */
    private static SlotSchedule solveWithProgress(SlotSchedule schedule) {

//        SolverConfig solverConfig = SolverConfigFactory.createConfig();

        // Create progress-optimized solver configuration


        SolverConfig solverConfig = SolverConfigFactory.createProgressConfig();
        SolverFactory<SlotSchedule> solverFactory = SolverFactory.create(solverConfig);

        Solver<SlotSchedule> solver = solverFactory.buildSolver();
        // Add progress listener
        solver.addEventListener(App::handleBestSolution);


        System.out.println("Starting optimization with progress tracking...");
        System.out.println("Orders: " + schedule.getOrderList().size() + ", Shift buckets: " + schedule.getShiftList().size());

        progressTracker.startTracking();
        long startTime = System.currentTimeMillis();

        // Start solving
        SlotSchedule solution = solver.solve(schedule);

        // Final report
        long endTime = System.currentTimeMillis();
        double totalSeconds = (endTime - startTime) / 1000.0;

        System.out.println("─".repeat(80));
        System.out.println("🏁 OPTIMIZATION COMPLETED");
        System.out.printf("⏱️  Total time: %.2f seconds%n", totalSeconds);
        System.out.printf("📊 Final score: %s%n", solution.getScore());

        long assignedOrders = solution.getOrderList().stream()
                .filter(order -> order.getAssignedShift() != null)
                .count();
        double assignmentRate = (assignedOrders * 100.0) / solution.getOrderList().size();

        System.out.printf("🎯 Assignment rate: %d/%d orders (%.1f%%)%n",
                assignedOrders, solution.getOrderList().size(), assignmentRate);

        if (solution.getScore().isFeasible()) {
            System.out.println("✅ Solution is feasible!");
        } else {
            System.out.println("❌ Solution is not feasible");
        }

        // Score breakdown
        System.out.println("\n📈 SCORE BREAKDOWN:");
        SolutionManager<SlotSchedule, ?> solutionManager = SolutionManager.create(solverFactory);
        var scoreExplanation = solutionManager.explain(solution);
        System.out.println(scoreExplanation.toString());

        System.out.println("─".repeat(80));

        return solution;
    }


    /**
     * Handles each new best solution found during optimization.
     * This method is called every time the solver finds a better solution.
     */
    private static void handleBestSolution(BestSolutionChangedEvent<SlotSchedule> solutionChangedEvent) {

        SlotSchedule solution = solutionChangedEvent.getNewBestSolution();

        // Update the best solution reference
        bestSolutionSoFar.set(solution);

        // Track progress
        progressTracker.trackSolution(solution);

        // Log progress data for CSV export
        long elapsedTime = System.currentTimeMillis();
        long assignedOrders = solution.getOrderList().stream()
                .filter(order -> order.getAssignedShift() != null)
                .count();
        double assignmentRate = (assignedOrders * 100.0) / solution.getOrderList().size();

        String logEntry = String.format(Locale.ROOT, "%d,%s,%d,%d,%.2f",
                elapsedTime, solution.getScore(), assignedOrders,
                solution.getOrderList().size(), assignmentRate);
        progressLog.add(logEntry);

        // Optional: Export intermediate result every N solutions (uncomment if needed)
        if (progressLog.size() % 20 == 0) {
            exportIntermediateResults(solution);
        }
    }

    /**
     * Exports intermediate solution to files.
     */
    @SuppressWarnings("unused")
    private static void exportIntermediateResults(SlotSchedule solution) {
        try {
            String intermediateFile = CSV_OUTPUT_DIR + "/intermediate_solution_" + progressLog.size() + ".csv";
            try (FileWriter writer = new FileWriter(intermediateFile)) {
                writer.write("order_id,assigned_shift_id,assigned_rider_id,assigned_date\n");
                for (Order order : solution.getOrderList()) {
                    String assignedShift = order.getAssignedShift() != null ? order.getAssignedShift().getId() : "UNASSIGNED";
                    String assignedRider = order.getAssignedShift() != null ? order.getAssignedShift().getRiderId() : "UNASSIGNED";
                    String assignedDate = order.getAssignedShift() != null ? order.getAssignedShift().getDate().toString() : "UNASSIGNED";
                    writer.write(String.format("%s,%s,%s,%s\n",
                            csvEscape(order.getId()), csvEscape(assignedShift),
                            csvEscape(assignedRider), csvEscape(assignedDate)));
                }
            }
        } catch (IOException e) {
            System.err.println("Error exporting intermediate result: " + e.getMessage());
        }
    }

    /**
     * Exports the progress log to CSV for analysis.
     */
    private static void exportProgressLog() {
        try (FileWriter writer = new FileWriter(FILE_PROGRESS_LOG)) {
            writer.write("timestamp,score,assigned_orders,total_orders,assignment_rate_percent\n");
            for (String entry : progressLog) {
                writer.write(entry + "\n");
            }
            System.out.println("Progress log exported to: " + FILE_PROGRESS_LOG);
        } catch (IOException e) {
            System.err.println("Error exporting progress log: " + e.getMessage());
        }
    }

    public static SlotSchedule generateSampleProblem() {
        // Bangalore city center (base coordinates)
        double baseLat = 12.9716;
        double baseLon = 77.5946;

        LocalDate base = LocalDate.now().withMonth(8).withDayOfMonth(3); // Aug 3
        int days = 5;
        int ridersPerDay = 25; // Increased to 25 riders per day
        int capacityPerRiderPerDay = 25; // Reduced to 25 orders per rider/day for more realistic load
        double movableThreshold = 0.8; // 80% movable per rider/day  
        int orderCount = 500;

        Random random = new Random(123);

        // Define realistic geographic clusters based on Bangalore's layout
        GeographicCluster[] clusters = createRealisticClusters(baseLat, baseLon);

        // Build shifts with strategically placed depots
        List<ShiftBucket> shifts = createRiderShifts(days, ridersPerDay, capacityPerRiderPerDay,
                movableThreshold, clusters, base, random);

        // Build orders with realistic clustering patterns
        List<Order> orders = createClusteredOrders(orderCount, clusters, days, base, random);

        return new SlotSchedule(orders, shifts);
    }

    private static GeographicCluster[] createRealisticClusters(double baseLat, double baseLon) {
        return new GeographicCluster[]{
                // Central Business District - high density, office hours
                new GeographicCluster("CBD", baseLat + 0.01, baseLon + 0.005, 2.0, 0.25,
                        ClusterType.BUSINESS, 9, 17),

                // Tech Hub (Electronic City direction) - medium density, flexible hours
                new GeographicCluster("TechHub", baseLat - 0.08, baseLon + 0.02, 4.0, 0.20,
                        ClusterType.TECH, 10, 19),

                // Residential North - medium density, evening preferred
                new GeographicCluster("ResidentialNorth", baseLat + 0.06, baseLon - 0.01, 3.5, 0.18,
                        ClusterType.RESIDENTIAL, 17, 20),

                // Industrial Area - low density, morning preferred, heavy items
                new GeographicCluster("Industrial", baseLat - 0.03, baseLon - 0.08, 6.0, 0.10,
                        ClusterType.INDUSTRIAL, 8, 16),

                // Airport Area - very low density, scattered, time critical
                new GeographicCluster("Airport", baseLat + 0.15, baseLon + 0.12, 8.0, 0.08,
                        ClusterType.LOGISTICS, 6, 22),

                // Suburban East - medium-low density, weekend orders
                new GeographicCluster("SuburbanEast", baseLat + 0.02, baseLon + 0.15, 5.0, 0.12,
                        ClusterType.SUBURBAN, 14, 18),

                // Outliers - very scattered, emergency/priority orders
                new GeographicCluster("Outliers", baseLat, baseLon, 25.0, 0.07,
                        ClusterType.SCATTERED, 8, 20)
        };
    }

    private static List<ShiftBucket> createRiderShifts(int days, int ridersPerDay, int capacity,
                                                       double movableThreshold, GeographicCluster[] clusters,
                                                       LocalDate base, Random random) {
        List<ShiftBucket> shifts = new ArrayList<>(days * ridersPerDay);
        String[] skillTypes = {"ELECTRICAL", "PLUMBING", "GENERAL", "HEAVY_LIFTING"};

        for (int d = 0; d < days; d++) {
            LocalDate date = base.plusDays(d);
            for (int r = 0; r < ridersPerDay; r++) {
                String riderId = "R" + (r + 1);
                String id = "SHIFT-" + date + "-" + riderId;

                // Place riders strategically near cluster centers with some spreading
                GeographicCluster depotCluster = clusters[r % (clusters.length - 1)]; // Exclude outliers for depots
                double[] depot = generateClusterPoint(depotCluster, random, 0.8); // Closer to center for depots

                // Assign skills based on rider specialization
                Set<String> riderSkills = assignRiderSkills(r, skillTypes, random);

                // Vehicle constraints vary by rider type
                VehicleSpecs specs = getVehicleSpecs(r, ridersPerDay, random);

                shifts.add(new ShiftBucket(id, riderId, date, capacity, movableThreshold,
                        depot[0], depot[1], riderSkills, specs.maxWeight, specs.maxVolume, specs.bufferRatio));
            }
        }
        return shifts;
    }

    private static List<Order> createClusteredOrders(int orderCount, GeographicCluster[] clusters,
                                                     int days, LocalDate base, Random random) {
        List<Order> orders = new ArrayList<>(orderCount);
        String[] skillTypes = {"ELECTRICAL", "PLUMBING", "GENERAL", "HEAVY_LIFTING"};

        for (int i = 0; i < orderCount; i++) {
            String id = "ORDER-" + (i + 1);

            // Select cluster based on probability weights
            GeographicCluster cluster = selectClusterByWeight(clusters, random);
            double[] location = generateClusterPoint(cluster, random, 1.0);

            // Time windows based on cluster type and order characteristics
            Set<LocalDate> allowedDays = generateTimeWindow(cluster, days, base, random);

            // Time preferences based on cluster characteristics
            LocalTime[] timePrefs = generateTimePreferences(cluster, random);

            // Order characteristics based on cluster type
            OrderSpecs specs = generateOrderSpecs(cluster, random);

            // Required skills based on cluster and order type
            Set<String> requiredSkills = generateRequiredSkills(cluster, skillTypes, random);

            orders.add(new Order(id, allowedDays, location[0], location[1],
                    timePrefs[0], timePrefs[1], specs.weight, specs.volume, requiredSkills));
        }
        return orders;
    }

    private static GeographicCluster selectClusterByWeight(GeographicCluster[] clusters, Random random) {
        double totalWeight = 0;
        for (GeographicCluster cluster : clusters) {
            totalWeight += cluster.probability;
        }

        double target = random.nextDouble() * totalWeight;
        double current = 0;

        for (GeographicCluster cluster : clusters) {
            current += cluster.probability;
            if (current >= target) {
                return cluster;
            }
        }
        return clusters[clusters.length - 1]; // Fallback
    }

    private static double[] generateClusterPoint(GeographicCluster cluster, Random random, double centralityFactor) {
        // Generate point within cluster using weighted distribution toward center
        double r = cluster.radiusKm * Math.pow(random.nextDouble(), centralityFactor);
        double theta = random.nextDouble() * 2 * Math.PI;

        double dLat = (r * Math.cos(theta)) / 110.574;
        double dLon = (r * Math.sin(theta)) / (111.320 * Math.cos(Math.toRadians(cluster.centerLat)));

        return new double[]{cluster.centerLat + dLat, cluster.centerLon + dLon};
    }

    private static Set<LocalDate> generateTimeWindow(GeographicCluster cluster, int days, LocalDate base, Random random) {
        Set<LocalDate> allowed = new HashSet<>();

        if (cluster.type == ClusterType.BUSINESS) {
            // Business: prefer single weekdays
            int day = random.nextInt(Math.min(days, 5)); // Weekdays only
            allowed.add(base.plusDays(day));
        } else if (cluster.type == ClusterType.LOGISTICS) {
            // Logistics: very flexible, often multi-day
            int windowSize = 1 + random.nextInt(3);
            int startDay = random.nextInt(days - windowSize + 1);
            for (int i = 0; i < windowSize; i++) {
                allowed.add(base.plusDays(startDay + i));
            }
        } else if (cluster.type == ClusterType.RESIDENTIAL) {
            // Residential: prefer flexible 2-day windows
            if (random.nextDouble() < 0.7) {
                int startDay = random.nextInt(days - 1);
                allowed.add(base.plusDays(startDay));
                allowed.add(base.plusDays(startDay + 1));
            } else {
                allowed.add(base.plusDays(random.nextInt(days)));
            }
        } else {
            // Default: random 1-3 day window
            int windowSize = 1 + random.nextInt(3);
            int startDay = random.nextInt(days - windowSize + 1);
            for (int i = 0; i < windowSize; i++) {
                allowed.add(base.plusDays(startDay + i));
            }
        }

        return allowed;
    }

    private static LocalTime[] generateTimePreferences(GeographicCluster cluster, Random random) {
        int startHour = cluster.preferredStartHour + random.nextInt(3) - 1; // ±1 hour variance
        startHour = Math.max(6, Math.min(22, startHour)); // Clamp to reasonable hours

        int duration = 2 + random.nextInt(4); // 2-5 hour window
        int endHour = Math.min(23, startHour + duration);

        return new LocalTime[]{LocalTime.of(startHour, 0), LocalTime.of(endHour, 0)};
    }

    private static OrderSpecs generateOrderSpecs(GeographicCluster cluster, Random random) {
        double weight, volume;

        volume = switch (cluster.type) {
            case INDUSTRIAL -> {
                weight = 15 + random.nextDouble() * 35; // 15-50kg (reduced from 20-100kg)
                yield 0.5 + random.nextDouble() * 2.0;
            }
            case BUSINESS -> {
                weight = 1 + random.nextDouble() * 8; // 1-9kg (documents, office supplies)
                yield 0.02 + random.nextDouble() * 0.3;
            }
            case LOGISTICS -> {
                weight = 20 + random.nextDouble() * 30; // 20-50kg (reduced from 30-100kg)
                yield 1.0 + random.nextDouble() * 3.0;
            }
            default -> {
                weight = 3 + random.nextDouble() * 15; // 3-18kg (reduced from 5-30kg)
                yield 0.1 + random.nextDouble() * 0.8;
            }
        };

        return new OrderSpecs(weight, volume);
    }

    private static Set<String> generateRequiredSkills(GeographicCluster cluster, String[] skillTypes, Random random) {
        Set<String> skills = new HashSet<>();

        double skillProbability = switch (cluster.type) {
            case INDUSTRIAL -> 0.6; // 60% require special skills
            case TECH -> 0.4; // 40% require special skills
            case BUSINESS -> 0.2; // 20% require special skills
            default -> 0.15; // 15% require special skills
        };

        if (random.nextDouble() < skillProbability) {
            if (cluster.type == ClusterType.INDUSTRIAL) {
                // Industrial orders more likely to need heavy lifting or electrical
                String[] preferredSkills = {"HEAVY_LIFTING", "ELECTRICAL"};
                skills.add(preferredSkills[random.nextInt(preferredSkills.length)]);
            } else {
                skills.add(skillTypes[random.nextInt(skillTypes.length)]);
            }
        }

        return skills;
    }

    private static Set<String> assignRiderSkills(int riderIndex, String[] skillTypes, Random random) {
        Set<String> skills = new HashSet<>();

        // Some riders are specialists, others are generalists
        if (riderIndex % 4 == 0) {
            // Specialist riders (25%) - fewer but focused skills
            skills.add(skillTypes[riderIndex % skillTypes.length]);
        } else {
            // Generalist riders (75%) - multiple skills
            int numSkills = 2 + random.nextInt(3); // 2-4 skills
            while (skills.size() < numSkills && skills.size() < skillTypes.length) {
                skills.add(skillTypes[random.nextInt(skillTypes.length)]);
            }
        }

        return skills;
    }

    private static VehicleSpecs getVehicleSpecs(int riderIndex, int totalRiders, Random random) {
        // Create variety in vehicle types with more generous capacities
        double percentile = (double) riderIndex / totalRiders;

        if (percentile < 0.3) {
            // Small vehicles (bikes, small vans) - 30%
            return new VehicleSpecs(80 + random.nextDouble() * 120, // 80-200kg (increased)
                    2.0 + random.nextDouble() * 3.0, // 2-5 cubic meters (increased)
                    0.05 + random.nextDouble() * 0.05); // 5-10% buffer (reduced)
        } else if (percentile < 0.7) {
            // Medium vehicles (vans) - 40%
            return new VehicleSpecs(200 + random.nextDouble() * 200, // 200-400kg (increased)
                    4.0 + random.nextDouble() * 4.0, // 4-8 cubic meters (increased)
                    0.05 + random.nextDouble() * 0.08); // 5-13% buffer (reduced)
        } else {
            // Large vehicles (trucks) - 30%
            return new VehicleSpecs(400 + random.nextDouble() * 300, // 400-700kg (increased)
                    8.0 + random.nextDouble() * 6.0, // 8-14 cubic meters (increased)
                    0.08 + random.nextDouble() * 0.12); // 8-20% buffer (reduced)
        }
    }

    /**
     * @param probability Relative weight for selection
     */ // Helper classes for data generation
    private record GeographicCluster(String name, double centerLat, double centerLon, double radiusKm,
                                     double probability, ClusterType type, int preferredStartHour,
                                     int preferredEndHour) {
    }

    private enum ClusterType {
        BUSINESS, RESIDENTIAL, INDUSTRIAL, TECH, LOGISTICS, SUBURBAN, SCATTERED
    }

    private record OrderSpecs(double weight, double volume) {
    }

    private record VehicleSpecs(double maxWeight, double maxVolume, double bufferRatio) {
    }

    private static void prettyPrintInput(SlotSchedule schedule) {
        System.out.println("=== INPUT ===");
        System.out.println("Orders: " + schedule.getOrderList().size());
        // Orders grouped by earliest day and window size
        Map<String, List<Order>> byWindow = schedule.getOrderList().stream()
                .sorted(Comparator.comparing(Order::getId))
                .collect(Collectors.groupingBy(o -> o.getEarliestAllowedDay() + "|days=" + o.getAllowedDays().size(),
                        TreeMap::new, Collectors.toList()));
        byWindow.forEach((k, list) -> System.out.println("Window " + k + " -> count=" + list.size()));
        System.out.println("=== END INPUT ===\n");
    }

    private static void prettyPrintOutput(SlotSchedule solution) {
        System.out.println("\n=== OUTPUT ===");
        HardSoftScore score = solution.getScore();
        System.out.println("Score: " + score);

        var byShift = solution.getOrderList().stream()
                .collect(Collectors.groupingBy(o -> o.getAssignedShift() == null ? "UNASSIGNED" : o.getAssignedShift().getId()));

        // Group shift summaries by day then rider
        DateTimeFormatter df = DateTimeFormatter.ISO_DATE;
        Map<String, List<ShiftBucket>> dayToShifts = solution.getShiftList().stream()
                .sorted(Comparator.comparing(ShiftBucket::getId))
                .collect(Collectors.groupingBy(s -> df.format(s.getDate()), LinkedHashMap::new, Collectors.toList()));

        // Summary: day i (date): total assigned orders across used riders
        var dayEntries = dayToShifts.entrySet().stream()
                .sorted(Comparator.comparing(e -> LocalDate.parse(e.getKey())))
                .toList();
        System.out.println("\nSummary:");
        for (int i = 0; i < dayEntries.size(); i++) {
            var entry = dayEntries.get(i);
            String day = entry.getKey();
            List<ShiftBucket> shifts = entry.getValue();
            int totalAssigned = shifts.stream()
                    .mapToInt(s -> byShift.getOrDefault(s.getId(), List.of()).size())
                    .sum();
            long ridersUsed = shifts.stream()
                    .filter(s -> !byShift.getOrDefault(s.getId(), List.of()).isEmpty())
                    .count();
            System.out.printf("  day %d (%s): %d orders across %d riders%n", i + 1, day, totalAssigned, ridersUsed);
        }

        var unassigned = byShift.getOrDefault("UNASSIGNED", List.of());
        if (!unassigned.isEmpty()) {
            System.out.println("\nUNASSIGNED (" + unassigned.size() + "): " + unassigned.stream().map(Order::getId).sorted().limit(50).collect(Collectors.joining(", ")) + (unassigned.size() > 50 ? " ..." : ""));
        }
        System.out.println("=== END OUTPUT ===");
    }

    private static void exportInputToCSV(SlotSchedule schedule) {
        try {
            // Export orders
            try (FileWriter writer = new FileWriter(FILE_INPUT_ORDERS)) {
                writer.write("order_id,allowed_days,latitude,longitude,preferred_start_time,preferred_end_time,weight,volume,required_skills\n");
                for (Order order : schedule.getOrderList()) {
                    String allowedDays = order.getAllowedDays().stream()
                            .sorted()
                            .map(LocalDate::toString)
                            .collect(Collectors.joining(";"));
                    String skills = order.getRequiredSkills().isEmpty() ? "none" : String.join(";", order.getRequiredSkills());
                    writer.write(String.format(Locale.ROOT, "%s,%s,%.6f,%.6f,%s,%s,%.2f,%.2f,%s\n",
                            csvEscape(order.getId()), csvEscape(allowedDays), order.getLatitude(), order.getLongitude(),
                            csvEscape(String.valueOf(order.getPreferredStartTime())), csvEscape(String.valueOf(order.getPreferredEndTime())),
                            order.getWeight(), order.getVolume(), csvEscape(skills)));
                }
            }

            // Export riders/shifts
            try (FileWriter writer = new FileWriter(FILE_INPUT_RIDERS)) {
                writer.write("shift_id,rider_id,date,capacity,effective_capacity,latitude,longitude,skills,max_weight,max_volume,buffer_ratio,movable_threshold\n");
                for (ShiftBucket shift : schedule.getShiftList()) {
                    String skills = shift.getRiderSkills().isEmpty() ? "none" : String.join(";", shift.getRiderSkills());
                    writer.write(String.format(Locale.ROOT, "%s,%s,%s,%d,%d,%.6f,%.6f,%s,%.2f,%.2f,%.2f,%.2f\n",
                            csvEscape(shift.getId()), csvEscape(shift.getRiderId()), csvEscape(String.valueOf(shift.getDate())),
                            shift.getCapacity(), shift.getEffectiveCapacity(),
                            shift.getStartLatitude(), shift.getStartLongitude(), csvEscape(skills),
                            shift.getMaxWeight(), shift.getMaxVolume(), shift.getBufferRatio(),
                            shift.getMovableOccupationRatioThreshold()));
                }
            }
            System.out.println("Input exported to:");
            System.out.println("  Orders: " + FILE_INPUT_ORDERS);
            System.out.println("  Riders: " + FILE_INPUT_RIDERS);
        } catch (IOException e) {
            System.err.println("Error exporting input CSV: " + e.getMessage());
        }
    }

    private static void exportOutputToCSV(SlotSchedule solution) {
        try {
            // Export assignments
            try (FileWriter writer = new FileWriter(FILE_OUTPUT_ASSIGNMENTS)) {
                writer.write("order_id,assigned_shift_id,assigned_rider_id,assigned_date,weight,volume,is_movable,required_skills\n");
                for (Order order : solution.getOrderList()) {
                    String assignedShift = order.getAssignedShift() != null ? order.getAssignedShift().getId() : "UNASSIGNED";
                    String assignedRider = order.getAssignedShift() != null ? order.getAssignedShift().getRiderId() : "UNASSIGNED";
                    String assignedDate = order.getAssignedShift() != null ? order.getAssignedShift().getDate().toString() : "UNASSIGNED";
                    String skills = order.getRequiredSkills().isEmpty() ? "none" : String.join(";", order.getRequiredSkills());
                    writer.write(String.format(Locale.ROOT, "%s,%s,%s,%s,%.2f,%.2f,%s,%s\n",
                            csvEscape(order.getId()), csvEscape(assignedShift), csvEscape(assignedRider), csvEscape(assignedDate),
                            order.getWeight(), order.getVolume(), order.isMovable(), csvEscape(skills)));
                }
            }

            // Export shift utilization summary
            try (FileWriter writer = new FileWriter(FILE_OUTPUT_SHIFT_SUMMARY)) {
                writer.write("shift_id,rider_id,date,capacity,effective_capacity,assigned_count,movable_count,movable_limit,total_weight,max_weight,total_volume,max_volume,utilization_percent\n");
                var byShift = solution.getOrderList().stream()
                        .collect(Collectors.groupingBy(Order::getAssignedShift));

                for (ShiftBucket shift : solution.getShiftList()) {
                    var assigned = byShift.getOrDefault(shift, List.of());
                    long movableCount = assigned.stream().filter(Order::isMovable).count();
                    int movableLimit = (int) Math.floor(shift.getMovableOccupationRatioThreshold() * shift.getCapacity());
                    double totalWeight = assigned.stream().mapToDouble(Order::getWeight).sum();
                    double totalVolume = assigned.stream().mapToDouble(Order::getVolume).sum();
                    double utilization = safePercent(assigned.size(), shift.getEffectiveCapacity());

                    writer.write(String.format(Locale.ROOT, "%s,%s,%s,%d,%d,%d,%d,%d,%.2f,%.2f,%.2f,%.2f,%.1f\n",
                            csvEscape(shift.getId()), csvEscape(shift.getRiderId()), csvEscape(String.valueOf(shift.getDate())),
                            shift.getCapacity(), shift.getEffectiveCapacity(), assigned.size(),
                            movableCount, movableLimit, totalWeight, shift.getMaxWeight(),
                            totalVolume, shift.getMaxVolume(), utilization));
                }
            }

            // Export daily summary
            try (FileWriter writer = new FileWriter(FILE_OUTPUT_DAILY_SUMMARY)) {
                writer.write("date,total_orders,total_riders_used,avg_utilization_percent,total_weight,total_volume\n");
                DateTimeFormatter df = DateTimeFormatter.ISO_DATE;
                var byShift = solution.getOrderList().stream()
                        .collect(Collectors.groupingBy(Order::getAssignedShift));

                Map<String, List<ShiftBucket>> dayToShifts = solution.getShiftList().stream()
                        .sorted(Comparator.comparing(ShiftBucket::getId))
                        .collect(Collectors.groupingBy(s -> df.format(s.getDate()), LinkedHashMap::new, Collectors.toList()));

                dayToShifts.forEach((day, shifts) -> {
                    int totalOrders = shifts.stream()
                            .mapToInt(s -> byShift.getOrDefault(s, List.of()).size())
                            .sum();
                    long ridersUsed = shifts.stream()
                            .filter(s -> !byShift.getOrDefault(s, List.of()).isEmpty())
                            .count();
                    double avgUtilization = shifts.stream()
                            .mapToDouble(s -> safePercent(byShift.getOrDefault(s, List.of()).size(), s.getEffectiveCapacity()))
                            .average().orElse(0.0);
                    double totalWeight = shifts.stream()
                            .flatMap(s -> byShift.getOrDefault(s, List.of()).stream())
                            .mapToDouble(Order::getWeight).sum();
                    double totalVolume = shifts.stream()
                            .flatMap(s -> byShift.getOrDefault(s, List.of()).stream())
                            .mapToDouble(Order::getVolume).sum();

                    try {
                        writer.write(String.format(Locale.ROOT, "%s,%d,%d,%.1f,%.2f,%.2f\n",
                                csvEscape(day), totalOrders, ridersUsed, avgUtilization, totalWeight, totalVolume));
                    } catch (IOException e) {
                        System.err.println("Error writing daily summary: " + e.getMessage());
                    }
                });
            }
            System.out.println("Output exported to:");
            System.out.println("  Assignments: " + FILE_OUTPUT_ASSIGNMENTS);
            System.out.println("  Shift summary: " + FILE_OUTPUT_SHIFT_SUMMARY);
            System.out.println("  Daily summary: " + FILE_OUTPUT_DAILY_SUMMARY);
        } catch (IOException e) {
            System.err.println("Error exporting output CSV: " + e.getMessage());
        }
    }
}
