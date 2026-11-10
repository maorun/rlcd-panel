#!/usr/bin/env python3
"""
Generates docs/board_buttons.svg illustrating the 3 physical buttons
on the Waveshare ESP32-S3-RLCD-4.2 development board and how to enter Download Mode.
"""

import os
import subprocess

def generate_svg(output_path: str):
    svg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 960 620" width="100%" height="100%">
  <defs>
    <style>
      .bg { fill: #0f172a; }
      .title { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif; font-size: 24px; font-weight: 700; fill: #f8fafc; }
      .subtitle { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif; font-size: 14px; fill: #94a3b8; }
      .board-pcb { fill: #1e293b; stroke: #334155; stroke-width: 3; rx: 16; }
      .screen-bezel { fill: #0f172a; stroke: #475569; stroke-width: 2; rx: 8; }
      .screen { fill: #dbe2d4; stroke: #94a3b8; stroke-width: 1; rx: 4; }
      .screen-text { font-family: "Courier New", monospace; font-size: 16px; font-weight: bold; fill: #1a1e1a; text-anchor: middle; }
      .screen-subtext { font-family: -apple-system, sans-serif; font-size: 13px; fill: #334155; text-anchor: middle; }
      
      .btn-boot { fill: #3b82f6; stroke: #1d4ed8; stroke-width: 2; cursor: pointer; }
      .btn-pwr  { fill: #ef4444; stroke: #b91c1c; stroke-width: 2; cursor: pointer; }
      .btn-key  { fill: #10b981; stroke: #047857; stroke-width: 2; cursor: pointer; }
      
      .label-title { font-family: -apple-system, sans-serif; font-size: 16px; font-weight: 700; }
      .label-desc  { font-family: -apple-system, sans-serif; font-size: 12px; fill: #cbd5e1; }
      .badge-boot  { fill: #1e3a8a; stroke: #3b82f6; stroke-width: 1.5; rx: 8; }
      .badge-pwr   { fill: #7f1d1d; stroke: #ef4444; stroke-width: 1.5; rx: 8; }
      .badge-key   { fill: #064e3b; stroke: #10b981; stroke-width: 1.5; rx: 8; }
      
      .card-bg { fill: #1e293b; stroke: #475569; stroke-width: 1.5; rx: 12; }
      .card-title { font-family: -apple-system, sans-serif; font-size: 15px; font-weight: 700; fill: #38bdf8; }
      .step-num { font-family: -apple-system, sans-serif; font-size: 12px; font-weight: 700; fill: #38bdf8; }
      .step-text { font-family: -apple-system, sans-serif; font-size: 13px; fill: #e2e8f0; }
      .step-code { font-family: monospace; font-weight: bold; fill: #facc15; }
      
      .callout-line { stroke: #64748b; stroke-width: 2; stroke-dasharray: 4 4; fill: none; }
      .arrow { fill: #64748b; }
      .connector-boot { stroke: #3b82f6; stroke-width: 2.5; fill: none; }
      .connector-pwr  { stroke: #ef4444; stroke-width: 2.5; fill: none; }
      .connector-key  { stroke: #10b981; stroke-width: 2.5; fill: none; }
    </style>
    
    <marker id="arrow-boot" viewBox="0 0 10 10" refX="5" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse">
      <path d="M 0 1 L 10 5 L 0 9 z" fill="#3b82f6" />
    </marker>
    <marker id="arrow-pwr" viewBox="0 0 10 10" refX="5" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse">
      <path d="M 0 1 L 10 5 L 0 9 z" fill="#ef4444" />
    </marker>
    <marker id="arrow-key" viewBox="0 0 10 10" refX="5" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse">
      <path d="M 0 1 L 10 5 L 0 9 z" fill="#10b981" />
    </marker>
  </defs>

  <!-- Background -->
  <rect width="960" height="620" class="bg" rx="16" />

  <!-- Header -->
  <text x="40" y="45" class="title">Waveshare ESP32-S3-RLCD-4.2 — Hardware Buttons</text>
  <text x="40" y="70" class="subtitle">Top-edge physical button layout and manual bootloader / download mode procedure</text>

  <!-- ==================== BOARD OUTLINE ==================== -->
  <g transform="translate(40, 150)">
    <!-- Main PCB Body -->
    <rect x="0" y="20" width="460" height="340" class="board-pcb" />
    <text x="20" y="348" font-family="-apple-system, sans-serif" font-size="11" font-weight="600" fill="#64748b" letter-spacing="1">WAVESHARE ESP32-S3-RLCD-4.2</text>
    
    <!-- Top Edge 3 Push Buttons -->
    <!-- 1. BOOT Button (Left) -->
    <rect x="80" y="6" width="44" height="18" rx="3" class="btn-boot" />
    <text x="102" y="19" font-family="-apple-system, sans-serif" font-size="10" font-weight="bold" fill="#ffffff" text-anchor="middle">BOOT</text>

    <!-- 2. PWR Button (Middle) -->
    <rect x="208" y="6" width="44" height="18" rx="3" class="btn-pwr" />
    <text x="230" y="19" font-family="-apple-system, sans-serif" font-size="10" font-weight="bold" fill="#ffffff" text-anchor="middle">PWR</text>

    <!-- 3. KEY Button (Right) -->
    <rect x="336" y="6" width="44" height="18" rx="3" class="btn-key" />
    <text x="358" y="19" font-family="-apple-system, sans-serif" font-size="10" font-weight="bold" fill="#ffffff" text-anchor="middle">KEY</text>

    <!-- Reflective LCD Screen Area -->
    <rect x="30" y="55" width="400" height="260" class="screen-bezel" />
    <rect x="40" y="65" width="380" height="240" class="screen" />
    
    <!-- Dashboard Screen Mockup -->
    <rect x="40" y="65" width="380" height="24" fill="#1a1e1a" />
    <text x="230" y="81" font-family="monospace" font-size="11" font-weight="bold" fill="#dbe2d4" text-anchor="middle">CLIMATE MONITOR — ONBOARD SHTC3 + SWITCHBOT</text>
    <line x1="230" y1="89" x2="230" y2="305" stroke="#1a1e1a" stroke-width="1.5" />
    <line x1="40" y1="197" x2="420" y2="197" stroke="#1a1e1a" stroke-width="1.5" />
    <text x="135" y="145" class="screen-text">ONBOARD SHTC3</text>
    <text x="135" y="170" class="screen-subtext">22.9 °C  |  50 %RH</text>
    <text x="325" y="145" class="screen-text">OUTDOOR BLE</text>
    <text x="325" y="170" class="screen-subtext">19.5 °C  |  55 %RH</text>
    <text x="135" y="245" class="screen-text">ROOM 1 BLE</text>
    <text x="135" y="270" class="screen-subtext">21.0 °C  |  48 %RH</text>
    <text x="325" y="245" class="screen-text">ROOM 2 BLE</text>
    <text x="325" y="270" class="screen-subtext">20.4 °C  |  52 %RH</text>

    <!-- USB Type-C Port (Bottom) -->
    <rect x="195" y="357" width="70" height="12" rx="4" fill="#64748b" stroke="#334155" stroke-width="1" />
    <text x="230" y="385" font-family="-apple-system, sans-serif" font-size="11" fill="#94a3b8" text-anchor="middle">USB Type-C (Flash / Serial)</text>
  </g>

  <!-- ==================== BUTTON CALLOUTS ==================== -->

  <!-- BOOT Connector & Label -->
  <path d="M 142 150 L 142 110" class="connector-boot" marker-end="url(#arrow-boot)" />
  <g transform="translate(40, 95)">
    <rect x="0" y="0" width="170" height="32" class="badge-boot" />
    <text x="12" y="21" class="label-title" fill="#93c5fd">1. BOOT (GPIO0)</text>
  </g>

  <!-- PWR Connector & Label -->
  <path d="M 270 150 L 270 110" class="connector-pwr" marker-end="url(#arrow-pwr)" />
  <g transform="translate(230, 95)">
    <rect x="0" y="0" width="160" height="32" class="badge-pwr" />
    <text x="12" y="21" class="label-title" fill="#fca5a5">2. PWR (Power)</text>
  </g>

  <!-- KEY Connector & Label -->
  <path d="M 398 150 L 398 110" class="connector-key" marker-end="url(#arrow-key)" />
  <g transform="translate(410, 95)">
    <rect x="0" y="0" width="170" height="32" class="badge-key" />
    <text x="12" y="21" class="label-title" fill="#6ee7b7">3. KEY (GPIO18)</text>
  </g>

  <!-- ==================== SIDE DETAIL CARDS ==================== -->

  <!-- Card 1: Button Descriptions -->
  <g transform="translate(540, 110)">
    <rect x="0" y="0" width="380" height="230" class="card-bg" />
    <text x="20" y="30" class="card-title">BUTTON FUNCTIONS (TOP EDGE, L → R)</text>

    <!-- Item 1: BOOT -->
    <circle cx="28" cy="65" r="8" fill="#3b82f6" />
    <text x="28" y="69" font-family="sans-serif" font-size="10" font-weight="bold" fill="#fff" text-anchor="middle">1</text>
    <text x="46" y="65" font-family="sans-serif" font-size="14" font-weight="bold" fill="#93c5fd">BOOT (Left, GPIO 0)</text>
    <text x="46" y="82" class="label-desc">Forces ESP32-S3 into ROM Bootloader / Download Mode.</text>

    <!-- Item 2: PWR -->
    <circle cx="28" cy="120" r="8" fill="#ef4444" />
    <text x="28" y="124" font-family="sans-serif" font-size="10" font-weight="bold" fill="#fff" text-anchor="middle">2</text>
    <text x="46" y="120" font-family="sans-serif" font-size="14" font-weight="bold" fill="#fca5a5">PWR (Middle, Power Circuit)</text>
    <text x="46" y="137" class="label-desc">• Short click: Power ON / Wake</text>
    <text x="46" y="153" class="label-desc">• Long press (> 5s): Power OFF</text>

    <!-- Item 3: KEY -->
    <circle cx="28" cy="190" r="8" fill="#10b981" />
    <text x="28" y="194" font-family="sans-serif" font-size="10" font-weight="bold" fill="#fff" text-anchor="middle">3</text>
    <text x="46" y="190" font-family="sans-serif" font-size="14" font-weight="bold" fill="#6ee7b7">KEY (Right, GPIO 18)</text>
    <text x="46" y="207" class="label-desc">User-programmable button (Active Low, custom actions).</text>
  </g>

  <!-- Card 2: How to enter Download Mode -->
  <g transform="translate(540, 360)">
    <rect x="0" y="0" width="380" height="225" class="card-bg" stroke="#f59e0b" stroke-width="2" />
    <rect x="0" y="0" width="380" height="36" fill="#78350f" rx="12" />
    <text x="20" y="24" font-family="sans-serif" font-size="14" font-weight="bold" fill="#fef08a">⚡ HOW TO ENTER DOWNLOAD / FLASHING MODE</text>

    <text x="20" y="65" class="step-num">Step 1</text>
    <text x="75" y="65" class="step-text">Turn board OFF (<tspan class="step-code">long-press PWR</tspan> or unplug USB).</text>

    <text x="20" y="105" class="step-num">Step 2</text>
    <text x="75" y="105" class="step-text">Press and <tspan class="step-code">HOLD the BOOT button</tspan> (left button).</text>

    <text x="20" y="145" class="step-num">Step 3</text>
    <text x="75" y="145" class="step-text">Turn board ON (<tspan class="step-code">click PWR</tspan> or plug USB-C cable in).</text>

    <text x="20" y="185" class="step-num">Step 4</text>
    <text x="75" y="185" class="step-text"><tspan class="step-code">RELEASE the BOOT button</tspan>.</text>

    <text x="20" y="212" font-family="sans-serif" font-size="12" font-weight="600" fill="#38bdf8">Result: Chip enters ROM Download Mode (0x0) for esptool.</text>
  </g>
</svg>
"""
    with open(output_path, "w", encoding="utf-8") as f:
        f.write(svg)
    print(f"Generated SVG: {output_path}")

if __name__ == "__main__":
    import sys
    out = sys.argv[1] if len(sys.argv) > 1 else "docs/board_buttons.svg"
    generate_svg(out)
