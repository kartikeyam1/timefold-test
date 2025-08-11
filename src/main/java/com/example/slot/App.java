package com.example.slot;

import ai.timefold.solver.core.api.score.buildin.hardsoft.HardSoftScore;
import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.api.solver.SolverManager;
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
    private static final String RUN_VERSION = "v2"; // Different version for progress-enabled runs
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

        SolverConfig solverConfig = SolverConfigFactory.createConfig();
        SolverFactory<SlotSchedule> solverFactory = SolverFactory.create(solverConfig);
        SolverManager<SlotSchedule, Long> solverManager = SolverManager.create(solverFactory);

        System.out.println("Starting optimization with progress tracking...");
        System.out.println("Orders: " + schedule.getOrderList().size() + ", Shift buckets: " + schedule.getShiftList().size());

        progressTracker.startTracking();
        long startTime = System.currentTimeMillis();

        // Use a problem ID for tracking
        Long problemId = 1L;

        // Solve asynchronously with progress tracking
        var solvingJob = solverManager.solve(problemId, schedule, App::handleBestSolution);

        try {
            // Wait for completion
            SlotSchedule finalSolution = solvingJob.getFinalBestSolution();
            long endTime = System.currentTimeMillis();
            double totalSeconds = (endTime - startTime) / 1000.0;

            progressTracker.finalReport(finalSolution, totalSeconds);
            return finalSolution;

        } catch (Exception e) {
            System.err.println("Error during solving: " + e.getMessage());
            e.printStackTrace();

            // Return best solution found so far if available
            SlotSchedule best = bestSolutionSoFar.get();
            return best != null ? best : schedule;
        } finally {
            solverManager.close();
        }
    }

    /**
     * Handles each new best solution found during optimization.
     * This method is called every time the solver finds a better solution.
     */
    private static void handleBestSolution(SlotSchedule solution) {

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

    private static SlotSchedule generateSampleProblem() {
        // Depot per rider: center around city, then jitter per rider
        double baseDepotLat = 12.9716;
        double baseDepotLon = 77.5946;

        LocalDate base = LocalDate.now().withMonth(8).withDayOfMonth(3); // Aug 3
        int days = 5;
        int ridersPerDay = 20; // Increased for 5000 orders
        int capacityPerRiderPerDay = 30; // Further increased capacity per rider/day
        double movableThreshold = 0.8; // 80% movable per rider/day  
        int orderCount = 500;

        Random random = new Random(123);

        // Build shifts: 5 days * 200 riders/day = 1000 rider-day buckets
        List<ShiftBucket> shifts = new ArrayList<>(days * ridersPerDay);
        String[] skillTypes = {"ELECTRICAL", "PLUMBING", "GENERAL", "HEAVY_LIFTING"};

        for (int d = 0; d < days; d++) {
            LocalDate date = base.plusDays(d);
            for (int r = 0; r < ridersPerDay; r++) {
                String riderId = "R" + (r + 1);
                String id = "SHIFT-" + date + "-" + riderId;
                // Rider depot jitter (~5 km radius around city center)
                double[] depot = jitterAround(baseDepotLat, baseDepotLon, random, 5.0);

                // Assign 1-3 random skills per rider
                Set<String> riderSkills = new HashSet<>();
                int numSkills = 1 + random.nextInt(3);
                for (int s = 0; s < numSkills; s++) {
                    riderSkills.add(skillTypes[random.nextInt(skillTypes.length)]);
                }

                // Vehicle constraints: weight 200-400kg, volume 5-10 cubic meters (more generous)
                double maxWeight = 200 + random.nextDouble() * 200;
                double maxVolume = 5 + random.nextDouble() * 5;
                double bufferRatio = 0.10; // Reserve 10% capacity for future orders (reduced from 15%)

                shifts.add(new ShiftBucket(id, riderId, date, capacityPerRiderPerDay, movableThreshold,
                        depot[0], depot[1], riderSkills, maxWeight, maxVolume, bufferRatio));
            }
        }

        // Build orders: distribute allowed windows across the 5 days
        List<Order> orders = new ArrayList<>(orderCount);
        for (int i = 0; i < orderCount; i++) {
            String id = "ORDER-" + (i + 1);
            int windowType = random.nextInt(3);
            Set<LocalDate> allowed;
            if (windowType == 0) {
                // Single-day
                allowed = Set.of(base.plusDays(random.nextInt(days)));
            } else if (windowType == 1) {
                // Two consecutive days
                int start = random.nextInt(days - 1);
                allowed = Set.of(base.plusDays(start), base.plusDays(start + 1));
            } else {
                // Three consecutive days starting within first 3
                int start = random.nextInt(days - 2);
                allowed = Set.of(base.plusDays(start), base.plusDays(start + 1), base.plusDays(start + 2));
            }
            // Customer location within ~15km radius of city center
            double[] loc = jitterAround(baseDepotLat, baseDepotLon, random, 15.0);

            // Customer time preferences: random window between 8 AM and 6 PM
            int startHour = 8 + random.nextInt(6); // 8 AM to 1 PM
            int endHour = startHour + 2 + random.nextInt(4); // 2-5 hours later, max 6 PM
            LocalTime prefStart = LocalTime.of(startHour, 0);
            LocalTime prefEnd = LocalTime.of(Math.min(endHour, 18), 0);

            // Order characteristics: weight 5-50kg, volume 0.1-2 cubic meters
            double weight = 5 + random.nextDouble() * 45;
            double volume = 0.1 + random.nextDouble() * 1.9;

            // Required skills: 20% chance of needing specific skill (reduced from 30%)
            Set<String> requiredSkills = new HashSet<>();
            if (random.nextDouble() < 0.2) {
                requiredSkills.add(skillTypes[random.nextInt(skillTypes.length)]);
            }

            orders.add(new Order(id, allowed, loc[0], loc[1], prefStart, prefEnd, weight, volume, requiredSkills));
        }

        return new SlotSchedule(orders, shifts);
    }

    private static double[] jitterAround(double lat, double lon, Random random, double radiusKm) {
        double r = radiusKm * Math.sqrt(random.nextDouble());
        double theta = random.nextDouble() * 2 * Math.PI;
        double dLat = (r * Math.cos(theta)) / 110.574;
        double dLon = (r * Math.sin(theta)) / (111.320 * Math.cos(Math.toRadians(lat)));
        return new double[]{lat + dLat, lon + dLon};
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
