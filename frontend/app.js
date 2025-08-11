// Global variables
let map;
let markersLayer;
let currentData = {
    orders: [],
    riders: [],
    assignments: [],
    dailySummary: [],
    shiftSummary: []
};
let charts = {};

// Color schemes for different data types
const COLORS = {
    days: ['#e74c3c', '#f39c12', '#f1c40f', '#27ae60', '#3498db', '#9b59b6', '#1abc9c'],
    riders: ['#FF6B6B', '#4ECDC4', '#45B7D1', '#96CEB4', '#FECA57', '#FF9FF3', '#54A0FF', '#5F27CD'],
    skills: {
        'ELECTRICAL': '#e74c3c',
        'PLUMBING': '#3498db', 
        'GENERAL': '#95a5a6',
        'HEAVY_LIFTING': '#f39c12'
    },
    utilization: {
        low: '#27ae60',    // Green for low utilization
        medium: '#f39c12', // Orange for medium
        high: '#e74c3c'    // Red for high utilization
    }
};

// Initialize the application
document.addEventListener('DOMContentLoaded', function() {
    initMap();
    bindEvents();
    updateFilePathInfo();
});

function initMap() {
    // Initialize map centered on Bangalore
    map = L.map('map').setView([12.9716, 77.5946], 11);
    
    // Add OpenStreetMap tiles
    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
        attribution: '© OpenStreetMap contributors'
    }).addTo(map);
    
    // Create markers layer
    markersLayer = L.layerGroup().addTo(map);
}

function bindEvents() {
    document.getElementById('loadBtn').addEventListener('click', loadData);
    document.getElementById('refreshBtn').addEventListener('click', refreshVisualization);
    document.getElementById('viewSelect').addEventListener('change', refreshVisualization);
    document.getElementById('dayFilter').addEventListener('change', refreshVisualization);
    document.getElementById('versionSelect').addEventListener('change', updateFilePathInfo);
}

function updateFilePathInfo() {
    const version = document.getElementById('versionSelect').value;
    console.log(`Current version: ${version}`);
}

async function loadData() {
    const version = document.getElementById('versionSelect').value;
    const basePath = `../csv_output/${version}`;
    
    showLoading();
    
    try {
        // Load all CSV files
        const [orders, riders, assignments, dailySummary, shiftSummary] = await Promise.all([
            loadCSV(`${basePath}/input_orders.csv`),
            loadCSV(`${basePath}/input_riders.csv`),
            loadCSV(`${basePath}/output_assignments.csv`),
            loadCSV(`${basePath}/output_daily_summary.csv`),
            loadCSV(`${basePath}/output_shift_summary.csv`)
        ]);
        
        currentData = { orders, riders, assignments, dailySummary, shiftSummary };
        
        console.log('Data loaded:', {
            orders: orders.length,
            riders: riders.length,
            assignments: assignments.length,
            dailySummary: dailySummary.length,
            shiftSummary: shiftSummary.length
        });
        
        refreshVisualization();
        
    } catch (error) {
        showError(`Failed to load data: ${error.message}`);
    }
}

function loadCSV(url) {
    return new Promise((resolve, reject) => {
        Papa.parse(url, {
            download: true,
            header: true,
            skipEmptyLines: true,
            complete: function(results) {
                if (results.errors.length > 0) {
                    reject(new Error(`CSV parsing error: ${results.errors[0].message}`));
                } else {
                    resolve(results.data);
                }
            },
            error: function(error) {
                reject(error);
            }
        });
    });
}

function refreshVisualization() {
    if (currentData.orders.length === 0) {
        showError('No data loaded. Please click "Load Data" first.');
        return;
    }
    
    const viewType = document.getElementById('viewSelect').value;
    const dayFilter = document.getElementById('dayFilter').value;
    
    // Clear existing markers
    markersLayer.clearLayers();
    
    if (viewType === 'input') {
        visualizeInput(dayFilter);
    } else {
        visualizeOutput(dayFilter);
    }
    
    updateStatistics(viewType, dayFilter);
    updateLegend(viewType);
}

function visualizeInput(dayFilter) {
    const filteredOrders = filterOrdersByDay(currentData.orders, dayFilter);
    
    filteredOrders.forEach((order, index) => {
        const lat = parseFloat(order.latitude);
        const lng = parseFloat(order.longitude);
        
        if (isNaN(lat) || isNaN(lng)) return;
        
        // Color by allowed days count
        const allowedDaysCount = order.allowed_days.split(';').length;
        const color = COLORS.days[Math.min(allowedDaysCount - 1, COLORS.days.length - 1)];
        
        const marker = L.circleMarker([lat, lng], {
            radius: 6,
            fillColor: color,
            color: 'white',
            weight: 2,
            opacity: 1,
            fillOpacity: 0.8
        });
        
        // Create popup content
        const popupContent = createOrderPopup(order);
        marker.bindPopup(popupContent);
        
        markersLayer.addLayer(marker);
    });
}

function visualizeOutput(dayFilter) {
    // Group assignments by rider and day
    const assignmentsByRider = {};
    
    currentData.assignments.forEach(assignment => {
        if (assignment.assigned_rider_id === 'UNASSIGNED') return;
        
        const date = assignment.assigned_date;
        if (dayFilter !== 'all' && date !== dayFilter) return;
        
        const riderId = assignment.assigned_rider_id;
        const key = `${riderId}_${date}`;
        
        if (!assignmentsByRider[key]) {
            assignmentsByRider[key] = {
                riderId,
                date,
                orders: [],
                totalWeight: 0,
                totalVolume: 0
            };
        }
        
        // Find the original order for location
        const originalOrder = currentData.orders.find(o => o.order_id === assignment.order_id);
        if (originalOrder) {
            assignmentsByRider[key].orders.push({
                ...assignment,
                ...originalOrder
            });
            assignmentsByRider[key].totalWeight += parseFloat(assignment.weight) || 0;
            assignmentsByRider[key].totalVolume += parseFloat(assignment.volume) || 0;
        }
    });
    
    // Visualize rider clusters
    Object.values(assignmentsByRider).forEach(riderData => {
        if (riderData.orders.length === 0) return;
        
        // Find rider info
        const riderInfo = currentData.riders.find(r => 
            r.rider_id === riderData.riderId && r.date === riderData.date
        );
        
        if (!riderInfo) return;
        
        const riderLat = parseFloat(riderInfo.latitude);
        const riderLng = parseFloat(riderInfo.longitude);
        
        if (isNaN(riderLat) || isNaN(riderLng)) return;
        
        // Calculate utilization
        const utilization = (riderData.orders.length / parseInt(riderInfo.effective_capacity)) * 100;
        const utilizationColor = getUtilizationColor(utilization);
        
        // Create rider marker (depot)
        const riderMarker = L.marker([riderLat, riderLng], {
            icon: L.divIcon({
                className: 'rider-marker',
                html: `<div style="
                    background: ${utilizationColor};
                    width: 20px;
                    height: 20px;
                    border-radius: 50%;
                    border: 3px solid white;
                    box-shadow: 0 0 5px rgba(0,0,0,0.3);
                    display: flex;
                    align-items: center;
                    justify-content: center;
                    color: white;
                    font-weight: bold;
                    font-size: 10px;
                ">${riderData.riderId}</div>`,
                iconSize: [20, 20]
            })
        });
        
        const riderPopup = createRiderPopup(riderData, riderInfo, utilization);
        riderMarker.bindPopup(riderPopup);
        markersLayer.addLayer(riderMarker);
        
        // Add order markers for this rider
        riderData.orders.forEach(order => {
            const lat = parseFloat(order.latitude);
            const lng = parseFloat(order.longitude);
            
            if (isNaN(lat) || isNaN(lng)) return;
            
            const orderMarker = L.circleMarker([lat, lng], {
                radius: 4,
                fillColor: utilizationColor,
                color: 'white',
                weight: 1,
                opacity: 1,
                fillOpacity: 0.7
            });
            
            const orderPopup = createAssignedOrderPopup(order, riderData.riderId);
            orderMarker.bindPopup(orderPopup);
            markersLayer.addLayer(orderMarker);
            
            // Draw line from rider to order
            const line = L.polyline([
                [riderLat, riderLng],
                [lat, lng]
            ], {
                color: utilizationColor,
                weight: 2,
                opacity: 0.6,
                dashArray: '5,5'
            });
            
            markersLayer.addLayer(line);
        });
    });
}

function filterOrdersByDay(orders, dayFilter) {
    if (dayFilter === 'all') return orders;
    
    return orders.filter(order => {
        const allowedDays = order.allowed_days.split(';');
        return allowedDays.includes(dayFilter);
    });
}

function getUtilizationColor(utilization) {
    if (utilization < 60) return COLORS.utilization.low;
    if (utilization < 85) return COLORS.utilization.medium;
    return COLORS.utilization.high;
}

function createOrderPopup(order) {
    const skills = order.required_skills === 'none' ? 'None' : order.required_skills;
    const allowedDays = order.allowed_days.split(';').join(', ');
    
    return `
        <div class="tooltip">
            <h4>📦 ${order.order_id}</h4>
            <div class="tooltip-row">
                <span>Location:</span>
                <span>${parseFloat(order.latitude).toFixed(4)}, ${parseFloat(order.longitude).toFixed(4)}</span>
            </div>
            <div class="tooltip-row">
                <span>Weight:</span>
                <span>${parseFloat(order.weight).toFixed(1)} kg</span>
            </div>
            <div class="tooltip-row">
                <span>Volume:</span>
                <span>${parseFloat(order.volume).toFixed(2)} m³</span>
            </div>
            <div class="tooltip-row">
                <span>Skills:</span>
                <span>${skills}</span>
            </div>
            <div class="tooltip-row">
                <span>Time Window:</span>
                <span>${order.preferred_start_time} - ${order.preferred_end_time}</span>
            </div>
            <div class="tooltip-row">
                <span>Allowed Days:</span>
                <span>${allowedDays}</span>
            </div>
        </div>
    `;
}

function createRiderPopup(riderData, riderInfo, utilization) {
    const skills = riderInfo.skills === 'none' ? 'None' : riderInfo.skills;
    
    return `
        <div class="tooltip">
            <h4>🚚 Rider ${riderData.riderId}</h4>
            <div class="tooltip-row">
                <span>Date:</span>
                <span>${riderData.date}</span>
            </div>
            <div class="tooltip-row">
                <span>Assigned Orders:</span>
                <span>${riderData.orders.length} / ${riderInfo.effective_capacity}</span>
            </div>
            <div class="tooltip-row">
                <span>Utilization:</span>
                <span>${utilization.toFixed(1)}%</span>
            </div>
            <div class="tooltip-row">
                <span>Total Weight:</span>
                <span>${riderData.totalWeight.toFixed(1)} kg / ${parseFloat(riderInfo.max_weight).toFixed(0)} kg</span>
            </div>
            <div class="tooltip-row">
                <span>Total Volume:</span>
                <span>${riderData.totalVolume.toFixed(2)} m³ / ${parseFloat(riderInfo.max_volume).toFixed(1)} m³</span>
            </div>
            <div class="tooltip-row">
                <span>Skills:</span>
                <span>${skills}</span>
            </div>
        </div>
    `;
}

function createAssignedOrderPopup(order, riderId) {
    const skills = order.required_skills === 'none' ? 'None' : order.required_skills;
    
    return `
        <div class="tooltip">
            <h4>📦 ${order.order_id}</h4>
            <div class="tooltip-row">
                <span>Assigned to:</span>
                <span>Rider ${riderId}</span>
            </div>
            <div class="tooltip-row">
                <span>Date:</span>
                <span>${order.assigned_date}</span>
            </div>
            <div class="tooltip-row">
                <span>Weight:</span>
                <span>${parseFloat(order.weight).toFixed(1)} kg</span>
            </div>
            <div class="tooltip-row">
                <span>Volume:</span>
                <span>${parseFloat(order.volume).toFixed(2)} m³</span>
            </div>
            <div class="tooltip-row">
                <span>Skills Required:</span>
                <span>${skills}</span>
            </div>
            <div class="tooltip-row">
                <span>Movable:</span>
                <span>${order.is_movable}</span>
            </div>
        </div>
    `;
}

function updateStatistics(viewType, dayFilter) {
    const container = document.getElementById('statsContainer');
    container.innerHTML = '';
    
    if (viewType === 'input') {
        showInputStatistics(container, dayFilter);
    } else {
        showOutputStatistics(container, dayFilter);
    }
}

function showInputStatistics(container, dayFilter) {
    const filteredOrders = filterOrdersByDay(currentData.orders, dayFilter);
    
    // Basic stats
    const totalOrders = filteredOrders.length;
    const movableOrders = filteredOrders.filter(o => o.allowed_days.split(';').length > 1).length;
    const avgWeight = filteredOrders.reduce((sum, o) => sum + parseFloat(o.weight), 0) / totalOrders;
    const avgVolume = filteredOrders.reduce((sum, o) => sum + parseFloat(o.volume), 0) / totalOrders;
    
    // Skills distribution
    const skillsCount = {};
    filteredOrders.forEach(order => {
        const skills = order.required_skills === 'none' ? [] : order.required_skills.split(';');
        skills.forEach(skill => {
            skillsCount[skill] = (skillsCount[skill] || 0) + 1;
        });
    });
    
    container.innerHTML = `
        <div class="stats-card">
            <h3>📊 Input Overview</h3>
            <div class="stat-item">
                <span>Total Orders:</span>
                <span class="stat-value">${totalOrders}</span>
            </div>
            <div class="stat-item">
                <span>Movable Orders:</span>
                <span class="stat-value">${movableOrders} (${(movableOrders/totalOrders*100).toFixed(1)}%)</span>
            </div>
            <div class="stat-item">
                <span>Avg Weight:</span>
                <span class="stat-value">${avgWeight.toFixed(1)} kg</span>
            </div>
            <div class="stat-item">
                <span>Avg Volume:</span>
                <span class="stat-value">${avgVolume.toFixed(2)} m³</span>
            </div>
        </div>
        
        <div class="stats-card">
            <h3>🛠️ Skills Distribution</h3>
            ${Object.entries(skillsCount).map(([skill, count]) => `
                <div class="stat-item">
                    <span>${skill}:</span>
                    <span class="stat-value">${count}</span>
                </div>
            `).join('')}
        </div>
        
        <div class="stats-card">
            <h3>📅 Day Distribution</h3>
            <div class="chart-container">
                <canvas id="dayChart"></canvas>
            </div>
        </div>
    `;
    
    // Create day distribution chart
    createDayDistributionChart(filteredOrders);
}

function showOutputStatistics(container, dayFilter) {
    let filteredAssignments = currentData.assignments.filter(a => a.assigned_rider_id !== 'UNASSIGNED');
    
    if (dayFilter !== 'all') {
        filteredAssignments = filteredAssignments.filter(a => a.assigned_date === dayFilter);
    }
    
    const totalAssigned = filteredAssignments.length;
    const unassigned = currentData.assignments.filter(a => a.assigned_rider_id === 'UNASSIGNED').length;
    const assignmentRate = (totalAssigned / (totalAssigned + unassigned) * 100);
    
    // Calculate rider utilization
    const riderStats = {};
    filteredAssignments.forEach(assignment => {
        const key = `${assignment.assigned_rider_id}_${assignment.assigned_date}`;
        if (!riderStats[key]) {
            riderStats[key] = { count: 0, weight: 0, volume: 0 };
        }
        riderStats[key].count++;
        riderStats[key].weight += parseFloat(assignment.weight) || 0;
        riderStats[key].volume += parseFloat(assignment.volume) || 0;
    });
    
    const activeRiders = Object.keys(riderStats).length;
    const avgOrdersPerRider = totalAssigned / activeRiders;
    
    container.innerHTML = `
        <div class="stats-card">
            <h3>📈 Assignment Overview</h3>
            <div class="stat-item">
                <span>Assigned Orders:</span>
                <span class="stat-value">${totalAssigned}</span>
            </div>
            <div class="stat-item">
                <span>Unassigned:</span>
                <span class="stat-value">${unassigned}</span>
            </div>
            <div class="stat-item">
                <span>Assignment Rate:</span>
                <span class="stat-value">${assignmentRate.toFixed(1)}%</span>
            </div>
            <div class="stat-item">
                <span>Active Riders:</span>
                <span class="stat-value">${activeRiders}</span>
            </div>
            <div class="stat-item">
                <span>Avg Orders/Rider:</span>
                <span class="stat-value">${avgOrdersPerRider.toFixed(1)}</span>
            </div>
        </div>
        
        <div class="stats-card">
            <h3>📊 Daily Summary</h3>
            <div class="chart-container">
                <canvas id="dailyChart"></canvas>
            </div>
        </div>
        
        <div class="stats-card">
            <h3>⚡ Utilization</h3>
            <div class="chart-container">
                <canvas id="utilizationChart"></canvas>
            </div>
        </div>
    `;
    
    createDailySummaryChart();
    createUtilizationChart(riderStats);
}

function createDayDistributionChart(orders) {
    const dayCount = {};
    orders.forEach(order => {
        const allowedDays = order.allowed_days.split(';');
        allowedDays.forEach(day => {
            dayCount[day] = (dayCount[day] || 0) + 1;
        });
    });
    
    const ctx = document.getElementById('dayChart').getContext('2d');
    charts.dayChart = new Chart(ctx, {
        type: 'bar',
        data: {
            labels: Object.keys(dayCount).sort(),
            datasets: [{
                label: 'Orders',
                data: Object.values(dayCount),
                backgroundColor: COLORS.days
            }]
        },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            plugins: {
                legend: { display: false }
            },
            scales: {
                y: { beginAtZero: true }
            }
        }
    });
}

function createDailySummaryChart() {
    if (currentData.dailySummary.length === 0) return;
    
    const ctx = document.getElementById('dailyChart').getContext('2d');
    charts.dailyChart = new Chart(ctx, {
        type: 'line',
        data: {
            labels: currentData.dailySummary.map(d => d.date),
            datasets: [{
                label: 'Orders Assigned',
                data: currentData.dailySummary.map(d => parseInt(d.total_orders)),
                borderColor: '#667eea',
                backgroundColor: 'rgba(102, 126, 234, 0.1)',
                tension: 0.4
            }, {
                label: 'Riders Used',
                data: currentData.dailySummary.map(d => parseInt(d.total_riders_used)),
                borderColor: '#f39c12',
                backgroundColor: 'rgba(243, 156, 18, 0.1)',
                tension: 0.4
            }]
        },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            scales: {
                y: { beginAtZero: true }
            }
        }
    });
}

function createUtilizationChart(riderStats) {
    const utilizationRanges = { 'Low (0-60%)': 0, 'Medium (60-85%)': 0, 'High (85%+)': 0 };
    
    Object.values(riderStats).forEach(stats => {
        // Approximate utilization (we don't have exact capacity here)
        const utilization = stats.count * 4; // Rough estimate
        if (utilization < 60) utilizationRanges['Low (0-60%)']++;
        else if (utilization < 85) utilizationRanges['Medium (60-85%)']++;
        else utilizationRanges['High (85%+)']++;
    });
    
    const ctx = document.getElementById('utilizationChart').getContext('2d');
    charts.utilizationChart = new Chart(ctx, {
        type: 'doughnut',
        data: {
            labels: Object.keys(utilizationRanges),
            datasets: [{
                data: Object.values(utilizationRanges),
                backgroundColor: [
                    COLORS.utilization.low,
                    COLORS.utilization.medium,
                    COLORS.utilization.high
                ]
            }]
        },
        options: {
            responsive: true,
            maintainAspectRatio: false
        }
    });
}

function updateLegend(viewType) {
    const legend = document.getElementById('legend');
    const content = document.getElementById('legendContent');
    
    if (viewType === 'input') {
        content.innerHTML = `
            <div class="legend-item">
                <div class="legend-color" style="background: ${COLORS.days[0]}"></div>
                <span>Single day orders</span>
            </div>
            <div class="legend-item">
                <div class="legend-color" style="background: ${COLORS.days[1]}"></div>
                <span>2-day window</span>
            </div>
            <div class="legend-item">
                <div class="legend-color" style="background: ${COLORS.days[2]}"></div>
                <span>3+ day window</span>
            </div>
        `;
    } else {
        content.innerHTML = `
            <div class="legend-item">
                <div class="legend-color" style="background: ${COLORS.utilization.low}"></div>
                <span>Low utilization (&lt;60%)</span>
            </div>
            <div class="legend-item">
                <div class="legend-color" style="background: ${COLORS.utilization.medium}"></div>
                <span>Medium utilization (60-85%)</span>
            </div>
            <div class="legend-item">
                <div class="legend-color" style="background: ${COLORS.utilization.high}"></div>
                <span>High utilization (&gt;85%)</span>
            </div>
            <div class="legend-item">
                <div style="width: 16px; height: 2px; background: #667eea; margin-right: 0.5rem; margin-top: 7px; opacity: 0.6;"></div>
                <span>Rider-Order connections</span>
            </div>
        `;
    }
    
    legend.style.display = 'block';
}

function showLoading() {
    document.getElementById('statsContainer').innerHTML = '<div class="loading">Loading data...</div>';
}

function showError(message) {
    document.getElementById('statsContainer').innerHTML = `<div class="error">${message}</div>`;
}
