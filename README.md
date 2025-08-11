# Timefold Slot Optimizer

A comprehensive slot management optimization system built with [Timefold Solver](https://timefold.ai/) that intelligently assigns orders to riders across multiple days while respecting business constraints and optimizing operational efficiency.

## 🎯 Problem Statement

This system solves the complex problem of assigning delivery/service orders to riders across multiple days, where:

- Orders have flexible time windows (single day or multi-day availability)
- Riders have different skills, capacities, and geographic locations
- Business needs to balance workload, minimize travel, and respect vehicle constraints
- Buffer capacity must be maintained for future urgent orders

## ✨ Key Features

### 🔧 **Advanced Constraints**

- **Skills Matching**: Orders requiring specific skills (ELECTRICAL, PLUMBING, HEAVY_LIFTING, GENERAL) are only assigned to qualified riders
- **Vehicle Capacity**: Enforces weight (100-200kg) and volume (2-5 cubic meters) limits per rider per day
- **Buffer Management**: Reserves 15% of capacity for emergency/future orders
- **Geographic Optimization**: Minimizes travel distance from rider depot to orders
- **Time Preferences**: Considers customer preferred time windows (8AM-6PM)

### 📊 **Optimization Goals**

- **Workload Balance**: Distributes orders evenly across riders and days
- **Clustering Bonus**: Rewards assigning nearby orders to the same rider-day
- **Earliest Day Preference**: Prioritizes scheduling orders as early as possible
- **Movable Order Management**: Balances flexible vs fixed-date orders per rider

### 📈 **Business Metrics**

- Capacity utilization tracking
- Skills utilization analysis
- Geographic clustering efficiency
- Workload fairness across riders
- Buffer capacity maintenance

## 🚀 Getting Started

### Prerequisites

- Java 17 or higher
- Maven 3.6+

### Installation & Running

1. **Clone and build**:

   ```bash
   git clone <repository-url>
   cd timefold-slot-optimizer
   mvn clean package
   ```

2. **Run the optimizer**:

   ```bash
   java -cp 'target/timefold-slot-optimizer-1.0.0-SNAPSHOT.jar:target/lib/*' com.example.slot.App
   ```

3. **Generated output**:
   - Console output with solving progress and summary
   - CSV files with detailed input/output data

## 📋 Input/Output

### Generated CSV Files

| File | Description |
|------|-------------|
| `input_orders.csv` | Order details: ID, allowed days, location, preferences, weight, volume, required skills |
| `input_riders.csv` | Rider details: shift info, capacity, location, skills, vehicle limits |
| `output_assignments.csv` | Final order-to-rider assignments |
| `output_shift_summary.csv` | Per-shift utilization and capacity metrics |
| `output_daily_summary.csv` | Daily KPIs: orders, riders used, utilization, weight/volume totals |

### Sample Configuration

- **Orders**: 500 with varied time windows and requirements
- **Riders**: 50 per day across 5 days (250 total shifts)
- **Capacity**: 20 orders per rider per day (17 effective with buffer)
- **Skills**: 4 types with 30% of orders requiring specific skills
- **Geography**: Orders within 15km radius, rider depots within 5km

## 🛠️ Customization

### Adjusting Parameters

Edit `App.java` to modify:

```java
int days = 5;                           // Number of days
int ridersPerDay = 50;                  // Riders available per day
int capacityPerRiderPerDay = 20;        // Orders per rider per day
double movableThreshold = 0.7;          // Max 70% movable orders per rider
int orderCount = 500;                   // Total orders to assign
double bufferRatio = 0.15;              // 15% capacity reserved for future
```

### Adding New Constraints

Extend `SlotConstraintProvider.java` to add business rules:

```java
private Constraint myCustomConstraint(ConstraintFactory factory) {
    return factory.forEach(Order.class)
            .filter(/* your condition */)
            .penalize(HardSoftScore.ONE_SOFT, /* penalty calculation */)
            .asConstraint("My custom constraint");
}
```

## 📊 Sample Results

### Optimization Metrics

- **Score**: 0hard/-6931soft (feasible solution)
- **Daily Distribution**: 89-110 orders per day
- **Utilization**: ~30% average (efficient buffer usage)
- **Skills Coverage**: 100% skill requirements satisfied
- **Travel Optimization**: Geographic clustering applied

### Daily Summary Example

```csv
date,total_orders,total_riders_used,avg_utilization_percent,total_weight,total_volume
2025-08-03,89,20,26.2,2584.96,94.55
2025-08-04,104,20,30.6,2819.90,103.63
2025-08-05,110,20,32.4,2981.22,116.70
```

## 🏗️ Architecture

### Domain Model

- **`Order`**: Planning entity with location, time windows, requirements
- **`ShiftBucket`**: Rider-day capacity with skills and vehicle limits
- **`SlotSchedule`**: Planning solution containing orders and shifts

### Constraints

- **Hard**: Must be satisfied (skills, capacity, allowed days)
- **Soft**: Optimized (distance, clustering, balance, preferences)

### Solver Configuration

- **Algorithm**: Timefold's constraint-based optimization
- **Environment**: Reproducible for consistent results
- **Termination**: 10 seconds solving time
- **Scaling**: Handles 500+ orders efficiently

## 🔍 Use Cases

- **Last-mile delivery** optimization
- **Field service** scheduling
- **Maintenance crew** allocation
- **Healthcare worker** scheduling
- **Installation team** planning

## 📚 References

- [Timefold Solver Documentation](https://docs.timefold.ai/)
- [Movable Visits and Multi-day Schedules](https://docs.timefold.ai/field-service-routing/latest/visit-service-constraints/movable-visits-and-multi-day-schedules)
- [Field Service Routing](https://docs.timefold.ai/field-service-routing/latest/)

## 🤝 Contributing

1. Fork the repository
2. Create a feature branch
3. Add tests for new constraints
4. Submit a pull request

## 📄 License

This project is licensed under the MIT License - see the LICENSE file for details.

---

Built with ❤️ using [Timefold Solver](https://timefold.ai/) - The fastest open-source optimization solver for Java.
