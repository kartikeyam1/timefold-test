#!/usr/bin/env python3
"""
Simple HTTP server for the Timefold Visualization Frontend
Serves static files and handles CORS for CSV access
"""

import http.server
import socketserver
import os
import sys
import json
from pathlib import Path

class CORSHTTPRequestHandler(http.server.SimpleHTTPRequestHandler):
    def end_headers(self):
        self.send_header('Access-Control-Allow-Origin', '*')
        self.send_header('Access-Control-Allow-Methods', 'GET, POST, OPTIONS')
        self.send_header('Access-Control-Allow-Headers', 'Content-Type')
        super().end_headers()
    
    def do_GET(self):
        # Handle API endpoint for version detection
        if self.path == '/api/versions':
            self.handle_versions_api()
        else:
            super().do_GET()
    
    def handle_versions_api(self):
        """Return available CSV output versions as JSON"""
        try:
            csv_output_path = Path('csv_output')
            versions = []
            
            if csv_output_path.exists() and csv_output_path.is_dir():
                for item in csv_output_path.iterdir():
                    if item.is_dir() and not item.name.startswith('.'):
                        # Check if it contains the required CSV files
                        required_files = ['input_orders.csv', 'input_riders.csv']
                        if all((item / file).exists() for file in required_files):
                            versions.append(item.name)
            
            versions.sort()
            
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.end_headers()
            
            response = {
                'versions': versions,
                'count': len(versions),
                'base_path': '/csv_output'
            }
            
            self.wfile.write(json.dumps(response).encode('utf-8'))
            
        except Exception as e:
            self.send_error(500, f"Error detecting versions: {str(e)}")
    
    def log_message(self, format, *args):
        # Suppress log messages for version API calls to reduce noise
        if '/api/versions' not in args[0] if args else True:
            super().log_message(format, *args)

def main():
    port = 8080
    
    # Change to the project root directory to serve CSV files
    project_root = Path(__file__).parent.parent
    os.chdir(project_root)
    
    print(f"🌐 Starting Timefold Visualization Server...")
    print(f"📁 Serving files from: {project_root}")
    print(f"🔗 Frontend URL: http://localhost:{port}/frontend/")
    print(f"📊 CSV files available at: http://localhost:{port}/csv_output/")
    print(f"\n🚀 Open your browser and navigate to: http://localhost:{port}/frontend/")
    print(f"\n⚠️  Make sure you have run the solver and generated CSV files first!")
    print(f"    Example: mvn exec:java -Dexec.mainClass=\"com.example.slot.App\"")
    print(f"\n🛑 Press Ctrl+C to stop the server")
    
    try:
        with socketserver.TCPServer(("", port), CORSHTTPRequestHandler) as httpd:
            httpd.serve_forever()
    except KeyboardInterrupt:
        print(f"\n🛑 Server stopped by user")
    except OSError as e:
        if e.errno == 48:  # Address already in use
            print(f"\n❌ Port {port} is already in use. Try closing other applications or use a different port.")
            print(f"    You can modify the port in server.py if needed.")
        else:
            print(f"\n❌ Server error: {e}")
        sys.exit(1)

if __name__ == "__main__":
    main()
