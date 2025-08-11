package com.example.slot;

import ai.timefold.solver.core.api.score.buildin.hardsoft.HardSoftScore;
import ai.timefold.solver.core.api.solver.Solver;
import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.config.solver.EnvironmentMode;
import ai.timefold.solver.core.config.solver.SolverConfig;
import com.example.slot.domain.Order;
import com.example.slot.domain.ShiftBucket;
import com.example.slot.domain.SlotSchedule;
import com.example.slot.solver.SlotConstraintProvider;

import java.io.FileWriter;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

public class App {

    public static void main(String[] args) {
        var schedule = generateSampleProblem();

        // Export input to CSV
        exportInputToCSV(schedule);

        prettyPrintInput(schedule);

        SolverConfig solverConfig = new SolverConfig()
                .withSolutionClass(SlotSchedule.class)
                .withEntityClasses(Order.class)
                .withConstraintProviderClass(SlotConstraintProvider.class)
                .withEnvironmentMode(EnvironmentMode.REPRODUCIBLE)
                .withTerminationSpentLimit(Duration.ofSeconds(10));

        SolverFactory<SlotSchedule> solverFactory = SolverFactory.create(solverConfig);
        Solver<SlotSchedule> solver = solverFactory.buildSolver();

        SlotSchedule solution = solver.solve(schedule);

        // Export output to CSV
        exportOutputToCSV(solution);

        prettyPrintOutput(solution);

        if (!solution.getScore().isFeasible()) {
            System.err.println("Solution is not feasible. Try increasing capacities or reducing orders.");
            System.exit(1);
        }
    }

    private static SlotSchedule generateSampleProblem() {
        // Depot per rider: center around city, then jitter per rider
        double baseDepotLat = 12.9716;
        double baseDepotLon = 77.5946;

        LocalDate base = LocalDate.now().withMonth(8).withDayOfMonth(3); // Aug 3
        int days = 5;
        int ridersPerDay = 50;
        int capacityPerRiderPerDay = 20; // capacity per rider/day
        double movableThreshold = 0.7; // 50% movable per rider/day
        int orderCount = 500;

        Random random = new Random(123);

        // Build shifts: 5 days * 20 riders/day = 100 rider-day buckets
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

                // Vehicle constraints: weight 100-200kg, volume 2-5 cubic meters
                double maxWeight = 100 + random.nextDouble() * 100;
                double maxVolume = 2 + random.nextDouble() * 3;
                double bufferRatio = 0.15; // Reserve 15% capacity for future orders

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

            // Required skills: 30% chance of needing specific skill
            Set<String> requiredSkills = new HashSet<>();
            if (random.nextDouble() < 0.3) {
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
        DateTimeFormatter df = DateTimeFormatter.ISO_DATE;
        System.out.println("=== INPUT ===");
        System.out.println("Orders: " + schedule.getOrderList().size());
        // Orders grouped by earliest day and window size
        Map<String, List<Order>> byWindow = schedule.getOrderList().stream()
                .sorted(Comparator.comparing(Order::getId))
                .collect(Collectors.groupingBy(o -> o.getEarliestAllowedDay() + "|days=" + o.getAllowedDays().size(),
                        TreeMap::new, Collectors.toList()));
        byWindow.forEach((k, list) -> System.out.println("Window " + k + " -> count=" + list.size()));

        // Print a small sample of first 20 orders
        System.out.println("\nSample orders (first 20):");
        schedule.getOrderList().stream()
                .sorted(Comparator.comparing(Order::getId))
                .limit(20)
                .forEach(o -> System.out.printf("%s allowed=%s lat=%.5f lon=%.5f time=%s-%s weight=%.1f skills=%s%n",
                        o.getId(), o.getAllowedDays().stream().sorted().map(df::format).collect(Collectors.joining(",")),
                        o.getLatitude(), o.getLongitude(),
                        o.getPreferredStartTime(), o.getPreferredEndTime(), o.getWeight(),
                        o.getRequiredSkills().isEmpty() ? "none" : String.join(",", o.getRequiredSkills())));

        // Shifts grouped by day
        Map<String, List<ShiftBucket>> byDay = schedule.getShiftList().stream()
                .sorted(Comparator.comparing(ShiftBucket::getId))
                .collect(Collectors.groupingBy(s -> df.format(s.getDate()), LinkedHashMap::new, Collectors.toList()));
        byDay.forEach((day, list) -> {
            System.out.println("\nDay " + day + " riders=" + list.size());
            list.stream().limit(5).forEach(s -> System.out.printf("  %s rider=%s cap=%d/%d lat=%.5f lon=%.5f skills=%s maxW=%.0f maxV=%.1f%n",
                    s.getId(), s.getRiderId(), s.getEffectiveCapacity(), s.getCapacity(),
                    s.getStartLatitude(), s.getStartLongitude(),
                    s.getRiderSkills().isEmpty() ? "none" : String.join(",", s.getRiderSkills()),
                    s.getMaxWeight(), s.getMaxVolume()));
        });
        System.out.println("=== END INPUT ===\n");
    }

    private static void prettyPrintOutput(SlotSchedule solution) {
        DateTimeFormatter df = DateTimeFormatter.ISO_DATE;
        System.out.println("\n=== OUTPUT ===");
        HardSoftScore score = solution.getScore();
        System.out.println("Score: " + score);

        var byShift = solution.getOrderList().stream()
                .collect(Collectors.groupingBy(o -> o.getAssignedShift() == null ? "UNASSIGNED" : o.getAssignedShift().getId()));

        // Group shift summaries by day then rider
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

        dayToShifts.forEach((day, shifts) -> {
            System.out.println("\nDay " + day + ":");
            for (ShiftBucket s : shifts) {
                var assigned = byShift.getOrDefault(s.getId(), List.of());
                long movableCount = assigned.stream().filter(Order::isMovable).count();
                int movableLimit = (int) Math.floor(s.getMovableOccupationRatioThreshold() * s.getCapacity());
                double totalWeight = assigned.stream().mapToDouble(Order::getWeight).sum();
                double totalVolume = assigned.stream().mapToDouble(Order::getVolume).sum();
                System.out.printf("  %s rider=%s cap=%d/%d assigned=%d movable=%d/%d weight=%.1f/%.0f vol=%.1f/%.1f%n",
                        s.getId(), s.getRiderId(), s.getEffectiveCapacity(), s.getCapacity(),
                        assigned.size(), movableCount, movableLimit, totalWeight, s.getMaxWeight(),
                        totalVolume, s.getMaxVolume());
                // Print first few assigned orders
                var preview = assigned.stream().limit(10).map(Order::getId).collect(Collectors.joining(", "));
                if (!preview.isEmpty()) {
                    System.out.println("    orders: " + preview + (assigned.size() > 10 ? " ..." : ""));
                }
            }
        });

        var unassigned = byShift.getOrDefault("UNASSIGNED", List.of());
        if (!unassigned.isEmpty()) {
            System.out.println("\nUNASSIGNED (" + unassigned.size() + "): " + unassigned.stream().map(Order::getId).sorted().limit(50).collect(Collectors.joining(", ")) + (unassigned.size() > 50 ? " ..." : ""));
        }
        System.out.println("=== END OUTPUT ===");
    }

    private static void exportInputToCSV(SlotSchedule schedule) {
        try {
            // Export orders
            try (FileWriter writer = new FileWriter("input_orders.csv")) {
                writer.write("order_id,allowed_days,latitude,longitude,preferred_start_time,preferred_end_time,weight,volume,required_skills\n");
                for (Order order : schedule.getOrderList()) {
                    String allowedDays = order.getAllowedDays().stream()
                            .sorted()
                            .map(LocalDate::toString)
                            .collect(Collectors.joining(";"));
                    String skills = order.getRequiredSkills().isEmpty() ? "none" : String.join(";", order.getRequiredSkills());
                    writer.write(String.format("%s,%s,%.6f,%.6f,%s,%s,%.2f,%.2f,%s\n",
                            order.getId(), allowedDays, order.getLatitude(), order.getLongitude(),
                            order.getPreferredStartTime(), order.getPreferredEndTime(),
                            order.getWeight(), order.getVolume(), skills));
                }
            }

            // Export riders/shifts
            try (FileWriter writer = new FileWriter("input_riders.csv")) {
                writer.write("shift_id,rider_id,date,capacity,effective_capacity,latitude,longitude,skills,max_weight,max_volume,buffer_ratio,movable_threshold\n");
                for (ShiftBucket shift : schedule.getShiftList()) {
                    String skills = shift.getRiderSkills().isEmpty() ? "none" : String.join(";", shift.getRiderSkills());
                    writer.write(String.format("%s,%s,%s,%d,%d,%.6f,%.6f,%s,%.2f,%.2f,%.2f,%.2f\n",
                            shift.getId(), shift.getRiderId(), shift.getDate(),
                            shift.getCapacity(), shift.getEffectiveCapacity(),
                            shift.getStartLatitude(), shift.getStartLongitude(), skills,
                            shift.getMaxWeight(), shift.getMaxVolume(), shift.getBufferRatio(),
                            shift.getMovableOccupationRatioThreshold()));
                }
            }
            System.out.println("Input exported to: input_orders.csv and input_riders.csv");
        } catch (IOException e) {
            System.err.println("Error exporting input CSV: " + e.getMessage());
        }
    }

    private static void exportOutputToCSV(SlotSchedule solution) {
        try {
            // Export assignments
            try (FileWriter writer = new FileWriter("output_assignments.csv")) {
                writer.write("order_id,assigned_shift_id,assigned_rider_id,assigned_date,weight,volume,is_movable,required_skills\n");
                for (Order order : solution.getOrderList()) {
                    String assignedShift = order.getAssignedShift() != null ? order.getAssignedShift().getId() : "UNASSIGNED";
                    String assignedRider = order.getAssignedShift() != null ? order.getAssignedShift().getRiderId() : "UNASSIGNED";
                    String assignedDate = order.getAssignedShift() != null ? order.getAssignedShift().getDate().toString() : "UNASSIGNED";
                    String skills = order.getRequiredSkills().isEmpty() ? "none" : String.join(";", order.getRequiredSkills());
                    writer.write(String.format("%s,%s,%s,%s,%.2f,%.2f,%s,%s\n",
                            order.getId(), assignedShift, assignedRider, assignedDate,
                            order.getWeight(), order.getVolume(), order.isMovable(), skills));
                }
            }

            // Export shift utilization summary
            try (FileWriter writer = new FileWriter("output_shift_summary.csv")) {
                writer.write("shift_id,rider_id,date,capacity,effective_capacity,assigned_count,movable_count,movable_limit,total_weight,max_weight,total_volume,max_volume,utilization_percent\n");
                var byShift = solution.getOrderList().stream()
                        .collect(Collectors.groupingBy(Order::getAssignedShift));

                for (ShiftBucket shift : solution.getShiftList()) {
                    var assigned = byShift.getOrDefault(shift, List.of());
                    long movableCount = assigned.stream().filter(Order::isMovable).count();
                    int movableLimit = (int) Math.floor(shift.getMovableOccupationRatioThreshold() * shift.getCapacity());
                    double totalWeight = assigned.stream().mapToDouble(Order::getWeight).sum();
                    double totalVolume = assigned.stream().mapToDouble(Order::getVolume).sum();
                    double utilization = (double) assigned.size() / shift.getEffectiveCapacity() * 100;

                    writer.write(String.format("%s,%s,%s,%d,%d,%d,%d,%d,%.2f,%.2f,%.2f,%.2f,%.1f\n",
                            shift.getId(), shift.getRiderId(), shift.getDate(),
                            shift.getCapacity(), shift.getEffectiveCapacity(), assigned.size(),
                            movableCount, movableLimit, totalWeight, shift.getMaxWeight(),
                            totalVolume, shift.getMaxVolume(), utilization));
                }
            }

            // Export daily summary
            try (FileWriter writer = new FileWriter("output_daily_summary.csv")) {
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
                            .mapToDouble(s -> (double) byShift.getOrDefault(s, List.of()).size() / s.getEffectiveCapacity() * 100)
                            .average().orElse(0.0);
                    double totalWeight = shifts.stream()
                            .flatMap(s -> byShift.getOrDefault(s, List.of()).stream())
                            .mapToDouble(Order::getWeight).sum();
                    double totalVolume = shifts.stream()
                            .flatMap(s -> byShift.getOrDefault(s, List.of()).stream())
                            .mapToDouble(Order::getVolume).sum();

                    try {
                        writer.write(String.format("%s,%d,%d,%.1f,%.2f,%.2f\n",
                                day, totalOrders, ridersUsed, avgUtilization, totalWeight, totalVolume));
                    } catch (IOException e) {
                        System.err.println("Error writing daily summary: " + e.getMessage());
                    }
                });
            }
            System.out.println("Output exported to: output_assignments.csv, output_shift_summary.csv, output_daily_summary.csv");
        } catch (IOException e) {
            System.err.println("Error exporting output CSV: " + e.getMessage());
        }
    }
}
