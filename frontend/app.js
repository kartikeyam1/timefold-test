// Global variables
let map;
let markersLayer;
let clusterPolygonsLayer; // Layer for cluster polygons
let currentData = {
    orders: [],
    riders: [],
    assignments: [],
    dailySummary: [],
    shiftSummary: []
};
let charts = {};
let riderVisibility = {}; // Track which riders are visible
let riderMarkers = {}; // Store rider markers for easy access
let clusterPolygons = {}; // Store cluster polygons for each rider
let showClusters = false; // Toggle for cluster visibility

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
    loadAvailableVersions();
    updateFilePathInfo();
});

function initMap() {
    // Initialize map centered on Bangalore
    map = L.map('map').setView([12.9716, 77.5946], 11);
    
    // Add OpenStreetMap tiles
    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
        attribution: '© OpenStreetMap contributors'
    }).addTo(map);
    
    // Create layers
    markersLayer = L.layerGroup().addTo(map);
    clusterPolygonsLayer = L.layerGroup().addTo(map);
}

function bindEvents() {
    document.getElementById('loadBtn').addEventListener('click', loadData);
    document.getElementById('refreshBtn').addEventListener('click', refreshVisualization);
    document.getElementById('viewSelect').addEventListener('change', refreshVisualization);
    document.getElementById('dayFilter').addEventListener('change', refreshVisualization);
    document.getElementById('versionSelect').addEventListener('change', updateFilePathInfo);
    document.getElementById('showClustersToggle').addEventListener('change', toggleClusters);
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
    
    // Clear existing markers and clusters
    markersLayer.clearLayers();
    clusterPolygonsLayer.clearLayers();
    riderMarkers = {}; // Reset rider markers
    clusterPolygons = {}; // Reset cluster polygons
    
    if (viewType === 'input') {
        visualizeInput(dayFilter);
    } else {
        visualizeOutput(dayFilter);
        
        // Generate clusters if enabled
        if (showClusters) {
            generateAndDisplayClusters();
        }
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
    
    // Initialize rider visibility if not set
    Object.keys(assignmentsByRider).forEach(key => {
        if (!(key in riderVisibility)) {
            riderVisibility[key] = true; // Default to visible
        }
    });
    
    // Visualize rider clusters
    Object.values(assignmentsByRider).forEach(riderData => {
        if (riderData.orders.length === 0) return;
        
        const riderKey = `${riderData.riderId}_${riderData.date}`;
        
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
        
        // Create layer group for this rider
        const riderLayerGroup = L.layerGroup();
        
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
        riderLayerGroup.addLayer(riderMarker);
        
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
            riderLayerGroup.addLayer(orderMarker);
            
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
            
            riderLayerGroup.addLayer(line);
        });
        
        // Store rider layer group and add to map if visible
        riderMarkers[riderKey] = {
            layerGroup: riderLayerGroup,
            data: riderData,
            info: riderInfo,
            utilization: utilization
        };
        
        if (riderVisibility[riderKey]) {
            markersLayer.addLayer(riderLayerGroup);
        }
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
        
        ${createRidersPanel(dayFilter)}
        
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
    
    // Bind events for rider toggles
    bindRiderToggleEvents();
    
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
            ${showClusters ? `
            <div class="legend-item">
                <div style="width: 16px; height: 16px; border: 2px dashed #667eea; margin-right: 0.5rem; opacity: 0.7; background: rgba(102, 126, 234, 0.15);"></div>
                <span>Cluster polygons</span>
            </div>
            ` : ''}
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

// Version Management Functions
async function loadAvailableVersions() {
    try {
        showVersionLoading();
        
        // First try the API endpoint
        try {
            const response = await fetch('/api/versions');
            if (response.ok) {
                const data = await response.json();
                if (data.versions && data.versions.length > 0) {
                    populateVersionSelect(data.versions);
                    console.log(`API detected ${data.count} versions:`, data.versions);
                    return;
                }
            }
        } catch (apiError) {
            console.log('API endpoint not available, trying directory listing...');
        }
        
        // Fallback: Try to get the directory listing from the server
        try {
            const response = await fetch('../csv_output/');
            const text = await response.text();
            
            // Parse the HTML directory listing to extract folder names
            const versions = parseDirectoryListing(text);
            
            if (versions.length > 0) {
                populateVersionSelect(versions);
                return;
            }
        } catch (dirError) {
            console.log('Directory listing failed, trying probing method...');
        }
        
        // Final fallback: try to detect versions by attempting to load known directories
        await detectVersionsByProbing();
        
    } catch (error) {
        console.warn('Could not auto-detect versions:', error);
        // Keep default hardcoded versions as ultimate fallback
        populateVersionSelect(['v1', 'v2', 'prod', 'test']);
        console.log('Using fallback hardcoded versions');
    } finally {
        // Re-enable the select
        const select = document.getElementById('versionSelect');
        select.disabled = false;
    }
}

function parseDirectoryListing(html) {
    const versions = [];
    
    // Try multiple patterns to match directory links
    const patterns = [
        /<a[^>]*href="([^"]+)\/"[^>]*>([^<]+)<\/a>/gi,  // Standard Apache/Nginx style
        /<a[^>]*>([^<]+)\/<\/a>/gi,                     // Simple directory links
        /href="([^"\/]+)\/"[^>]*>([^<]*)/gi             // Alternative pattern
    ];
    
    for (const pattern of patterns) {
        let match;
        while ((match = pattern.exec(html)) !== null) {
            const dirName = match[1] || match[2];
            if (dirName && 
                dirName !== '..' && 
                dirName !== '.' && 
                !dirName.includes('..') &&
                !dirName.includes('index') &&
                dirName.trim() !== '') {
                
                // Clean up the directory name
                const cleanName = dirName.replace(/\//g, '').trim();
                if (cleanName && !versions.includes(cleanName)) {
                    versions.push(cleanName);
                }
            }
        }
    }
    
    return versions.sort();
}

async function detectVersionsByProbing() {
    const commonVersions = ['v1', 'v2', 'v3', 'prod', 'test', 'dev', 'staging'];
    const detectedVersions = [];
    
    for (const version of commonVersions) {
        try {
            const response = await fetch(`../csv_output/${version}/input_orders.csv`, { method: 'HEAD' });
            if (response.ok) {
                detectedVersions.push(version);
            }
        } catch (error) {
            // Version doesn't exist, continue
        }
    }
    
    if (detectedVersions.length > 0) {
        populateVersionSelect(detectedVersions);
    }
}

function populateVersionSelect(versions) {
    const select = document.getElementById('versionSelect');
    const currentValue = select.value;
    
    // Clear existing options
    select.innerHTML = '';
    
    // Add detected versions
    versions.forEach(version => {
        const option = document.createElement('option');
        option.value = version;
        option.textContent = version;
        select.appendChild(option);
    });
    
    // Try to restore previous selection, or select first available
    if (versions.includes(currentValue)) {
        select.value = currentValue;
    } else if (versions.length > 0) {
        select.value = versions[0];
    }
    
    // Update info message
    updateVersionInfo(versions);
    
    console.log(`Detected ${versions.length} versions:`, versions);
}

function updateVersionInfo(versions) {
    // Remove any existing version info
    const existingInfo = document.querySelector('.version-info');
    if (existingInfo) {
        existingInfo.remove();
    }
    
    // Add info about detected versions
    const controlsDiv = document.querySelector('.controls');
    const infoDiv = document.createElement('div');
    infoDiv.className = 'version-info';
    infoDiv.style.cssText = 'font-size: 0.8rem; color: #6c757d; margin-left: auto;';
    infoDiv.textContent = `${versions.length} version${versions.length !== 1 ? 's' : ''} detected`;
    
    controlsDiv.appendChild(infoDiv);
}

function showVersionLoading() {
    const select = document.getElementById('versionSelect');
    select.innerHTML = '<option>Loading versions...</option>';
    select.disabled = true;
}

// Enhanced version selector with refresh capability
function refreshVersions() {
    loadAvailableVersions().then(() => {
        const select = document.getElementById('versionSelect');
        select.disabled = false;
    });
}

// Rider Management Functions
function createRidersPanel(dayFilter) {
    const ridersData = Object.values(riderMarkers);
    
    if (ridersData.length === 0) {
        return '<div class="stats-card"><h3>🚚 No Riders Available</h3></div>';
    }
    
    // Sort riders by utilization (highest first)
    ridersData.sort((a, b) => b.utilization - a.utilization);
    
    const visibleCount = ridersData.filter(r => riderVisibility[`${r.data.riderId}_${r.data.date}`]).length;
    
    let ridersHtml = `
        <div class="riders-panel">
            <div class="riders-header" onclick="toggleRidersPanel()">
                <span>🚚 Riders (${visibleCount}/${ridersData.length})</span>
                <span class="collapse-icon" id="ridersCollapseIcon">▼</span>
            </div>
            <div class="riders-content" id="ridersContent">
    `;
    
    ridersData.forEach(rider => {
        const riderKey = `${rider.data.riderId}_${rider.data.date}`;
        const isVisible = riderVisibility[riderKey];
        const utilizationColor = getUtilizationColor(rider.utilization);
        
        ridersHtml += `
            <div class="rider-item">
                <div class="rider-toggle ${isVisible ? 'active' : 'inactive'}" 
                     onclick="toggleRider('${riderKey}')"
                     title="Click to ${isVisible ? 'hide' : 'show'} rider">
                    ${isVisible ? '👁' : '👁'}
                </div>
                <div class="rider-info">
                    <div>
                        <div class="rider-name">${rider.data.riderId}</div>
                        <div class="rider-stats">${rider.data.date}</div>
                        <div class="rider-utilization">
                            <div class="rider-utilization-bar" 
                                 style="width: ${Math.min(rider.utilization, 100)}%; background: ${utilizationColor}">
                            </div>
                        </div>
                    </div>
                    <div style="text-align: right; font-size: 0.75rem;">
                        <div>${rider.data.orders.length}/${rider.info.effective_capacity}</div>
                        <div style="color: ${utilizationColor}; font-weight: bold;">
                            ${rider.utilization.toFixed(0)}%
                        </div>
                    </div>
                </div>
            </div>
        `;
    });
    
    ridersHtml += `
            </div>
            <div class="bulk-actions">
                <button class="bulk-btn" onclick="toggleAllRiders(true)">Show All</button>
                <button class="bulk-btn" onclick="toggleAllRiders(false)">Hide All</button>
                <button class="bulk-btn" onclick="toggleHighUtilization()">High Only</button>
            </div>
        </div>
    `;
    
    return ridersHtml;
}

function bindRiderToggleEvents() {
    // Events are bound via onclick in the HTML, no additional binding needed
}

function toggleRider(riderKey) {
    riderVisibility[riderKey] = !riderVisibility[riderKey];
    
    if (riderMarkers[riderKey]) {
        if (riderVisibility[riderKey]) {
            markersLayer.addLayer(riderMarkers[riderKey].layerGroup);
        } else {
            markersLayer.removeLayer(riderMarkers[riderKey].layerGroup);
        }
    }
    
    // Update cluster visibility
    updateClusterVisibility();
    
    // Update the toggle button appearance
    const toggleBtn = document.querySelector(`[onclick="toggleRider('${riderKey}')"]`);
    if (toggleBtn) {
        toggleBtn.className = `rider-toggle ${riderVisibility[riderKey] ? 'active' : 'inactive'}`;
        toggleBtn.title = `Click to ${riderVisibility[riderKey] ? 'hide' : 'show'} rider`;
    }
    
    // Update the riders count in header
    updateRidersCount();
}

function toggleAllRiders(show) {
    Object.keys(riderMarkers).forEach(riderKey => {
        riderVisibility[riderKey] = show;
        
        if (riderMarkers[riderKey]) {
            if (show) {
                markersLayer.addLayer(riderMarkers[riderKey].layerGroup);
            } else {
                markersLayer.removeLayer(riderMarkers[riderKey].layerGroup);
            }
        }
    });
    
    // Update cluster visibility
    updateClusterVisibility();
    
    // Update all toggle buttons
    document.querySelectorAll('.rider-toggle').forEach(btn => {
        btn.className = `rider-toggle ${show ? 'active' : 'inactive'}`;
        btn.title = `Click to ${show ? 'hide' : 'show'} rider`;
    });
    
    updateRidersCount();
}

function toggleHighUtilization() {
    Object.keys(riderMarkers).forEach(riderKey => {
        const rider = riderMarkers[riderKey];
        const showHighUtil = rider.utilization >= 70; // Show riders with 70%+ utilization
        
        riderVisibility[riderKey] = showHighUtil;
        
        if (rider) {
            if (showHighUtil) {
                markersLayer.addLayer(rider.layerGroup);
            } else {
                markersLayer.removeLayer(rider.layerGroup);
            }
        }
    });
    
    // Update cluster visibility
    updateClusterVisibility();
    
    // Update all toggle buttons
    document.querySelectorAll('.rider-toggle').forEach((btn, index) => {
        const riderKey = Object.keys(riderMarkers)[index];
        if (riderKey) {
            const isVisible = riderVisibility[riderKey];
            btn.className = `rider-toggle ${isVisible ? 'active' : 'inactive'}`;
            btn.title = `Click to ${isVisible ? 'hide' : 'show'} rider`;
        }
    });
    
    updateRidersCount();
}

function toggleRidersPanel() {
    const content = document.getElementById('ridersContent');
    const icon = document.getElementById('ridersCollapseIcon');
    
    if (content.style.display === 'none') {
        content.style.display = 'block';
        icon.textContent = '▼';
        icon.classList.remove('collapsed');
    } else {
        content.style.display = 'none';
        icon.textContent = '▶';
        icon.classList.add('collapsed');
    }
}

function updateRidersCount() {
    const totalRiders = Object.keys(riderMarkers).length;
    const visibleRiders = Object.keys(riderMarkers).filter(key => riderVisibility[key]).length;
    
    const header = document.querySelector('.riders-header span');
    if (header) {
        header.textContent = `🚚 Riders (${visibleRiders}/${totalRiders})`;
    }
}

// ============================================================================
// CLUSTER POLYGON FUNCTIONALITY
// ============================================================================

// Helper function for coordinate calculations
Math.toRadians = function(degrees) {
    return degrees * (Math.PI / 180);
};

function toggleClusters() {
    const toggle = document.getElementById('showClustersToggle');
    showClusters = toggle.checked;
    
    if (showClusters) {
        generateAndDisplayClusters();
    } else {
        hideClusters();
    }
}

function generateAndDisplayClusters() {
    if (Object.keys(riderMarkers).length === 0) return;
    
    // Clear existing cluster polygons
    clusterPolygonsLayer.clearLayers();
    clusterPolygons = {};
    
    // Generate cluster polygons for each visible rider
    Object.keys(riderMarkers).forEach(riderKey => {
        if (riderVisibility[riderKey]) {
            const riderData = riderMarkers[riderKey];
            createClusterPolygon(riderKey, riderData);
        }
    });
}

function createClusterPolygon(riderKey, riderData) {
    const orders = riderData.data.orders;
    const riderInfo = riderData.info;
    
    if (orders.length < 3) {
        // Need at least 3 points for a meaningful polygon
        return;
    }
    
    // Collect all order coordinates + rider depot
    const points = [];
    
    // Add rider depot
    points.push([riderInfo.latitude, riderInfo.longitude]);
    
    // Add order locations
    orders.forEach(order => {
        const lat = parseFloat(order.latitude);
        const lng = parseFloat(order.longitude);
        if (!isNaN(lat) && !isNaN(lng)) {
            points.push([lat, lng]);
        }
    });
    
    if (points.length < 3) return;
    
    // Calculate convex hull
    const hull = calculateConvexHull(points);
    
    if (hull && hull.length >= 3) {
        // Create polygon
        const utilizationColor = getUtilizationColor(riderData.utilization);
        const polygon = L.polygon(hull, {
            color: utilizationColor,
            weight: 2,
            opacity: 0.7,
            fillColor: utilizationColor,
            fillOpacity: 0.15,
            dashArray: '5,5'
        });
        
        // Add popup with cluster info
        const clusterInfo = createClusterPopup(riderData);
        polygon.bindPopup(clusterInfo);
        
        // Store and add to map
        clusterPolygons[riderKey] = polygon;
        clusterPolygonsLayer.addLayer(polygon);
    }
}

function calculateConvexHull(points) {
    try {
        // Convert [lat, lng] to [lng, lat] for d3.polygonHull (expects x, y format)
        const d3Points = points.map(point => [point[1], point[0]]);
        
        // Calculate convex hull using d3-polygon
        const hull = d3.polygonHull(d3Points);
        
        if (!hull) return null;
        
        // Convert back to [lat, lng] for Leaflet
        return hull.map(point => [point[1], point[0]]);
    } catch (error) {
        console.warn('Error calculating convex hull:', error);
        return null;
    }
}

function createClusterPopup(riderData) {
    const orders = riderData.data.orders;
    const riderInfo = riderData.info;
    
    // Calculate cluster statistics
    const totalOrders = orders.length;
    const totalWeight = orders.reduce((sum, order) => sum + parseFloat(order.weight || 0), 0);
    const totalVolume = orders.reduce((sum, order) => sum + parseFloat(order.volume || 0), 0);
    
    // Calculate cluster area (approximate)
    const lats = orders.map(o => parseFloat(o.latitude)).filter(lat => !isNaN(lat));
    const lngs = orders.map(o => parseFloat(o.longitude)).filter(lng => !isNaN(lng));
    lats.push(riderInfo.latitude);
    lngs.push(riderInfo.longitude);
    
    const latRange = Math.max(...lats) - Math.min(...lats);
    const lngRange = Math.max(...lngs) - Math.min(...lngs);
    const approxArea = (latRange * 111) * (lngRange * 111 * Math.cos(Math.toRadians(riderInfo.latitude)));
    
    return `
        <div class="tooltip">
            <h4>📍 Cluster: Rider ${riderData.data.riderId}</h4>
            <div class="tooltip-row">
                <span>Date:</span>
                <span>${riderData.data.date}</span>
            </div>
            <div class="tooltip-row">
                <span>Orders in Cluster:</span>
                <span>${totalOrders}</span>
            </div>
            <div class="tooltip-row">
                <span>Total Weight:</span>
                <span>${totalWeight.toFixed(1)} kg</span>
            </div>
            <div class="tooltip-row">
                <span>Total Volume:</span>
                <span>${totalVolume.toFixed(2)} m³</span>
            </div>
            <div class="tooltip-row">
                <span>Approx. Area:</span>
                <span>${approxArea.toFixed(1)} km²</span>
            </div>
            <div class="tooltip-row">
                <span>Utilization:</span>
                <span>${riderData.utilization.toFixed(1)}%</span>
            </div>
        </div>
    `;
}

function hideClusters() {
    clusterPolygonsLayer.clearLayers();
    clusterPolygons = {};
}

// Update cluster visibility when rider visibility changes
function updateClusterVisibility() {
    if (!showClusters) return;
    
    Object.keys(clusterPolygons).forEach(riderKey => {
        const polygon = clusterPolygons[riderKey];
        if (riderVisibility[riderKey]) {
            if (!clusterPolygonsLayer.hasLayer(polygon)) {
                clusterPolygonsLayer.addLayer(polygon);
            }
        } else {
            if (clusterPolygonsLayer.hasLayer(polygon)) {
                clusterPolygonsLayer.removeLayer(polygon);
            }
        }
    });
}
