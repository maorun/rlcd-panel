import Charts
import RlcdProtocol
import SwiftUI

private let tileNames = ["Onboard", "Slot 1", "Slot 2", "Slot 3", "Slot 4", "Slot 5", "Slot 6", "Slot 7", "Slot 8", "Slot 9", "Slot 10"]

struct MainView: View {
    @Bindable var model: AppModel
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                telemetrySection
                Divider()
                consoleSection
            }
            .padding()
        }
        .frame(minWidth: 560, minHeight: 640)
        .onAppear { model.refreshLoginItem() }
    }

    private var telemetrySection: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Telemetry").font(.headline)
            Text(model.bluetoothState).font(.caption).foregroundStyle(.secondary)
            panelsView
            if let frame = model.frame {
                let activeIndices = (0..<min(frame.tiles.count, tileNames.count)).filter { i in
                    i == 0 || frame.tiles[i].isValid || model.config?.isConfigured(slot: i) == true
                }
                let visibleIndices = activeIndices.isEmpty ? Array(0..<min(4, frame.tiles.count)) : activeIndices
                let columnCount = visibleIndices.count <= 2 ? max(1, visibleIndices.count) : (visibleIndices.count == 3 ? 3 : (visibleIndices.count <= 4 ? 2 : 3))
                LazyVGrid(columns: Array(repeating: GridItem(.flexible()), count: columnCount), spacing: 8) {
                    ForEach(visibleIndices, id: \.self) { i in
                        VStack(spacing: 4) {
                            HStack {
                                Text((model.config?.name(forSlot: i) ?? tileNames[i])
                                     + (model.status?.sensors.first { $0.index == i }?.badge.map { " \($0)" } ?? ""))
                                    .font(.caption).lineLimit(1)
                                Spacer()
                                if let mold = model.moldHours(forSensor: i), mold > 0 {
                                    Text("MOLD \(mold)H").font(.system(size: 9, weight: .bold)).foregroundStyle(.red)
                                }
                            }
                            if let mac = model.status?.sensors.first(where: { $0.index == i })?.mac ?? model.config?.mac(forSlot: i) {
                                Text(mac)
                                    .font(.system(size: 9, design: .monospaced))
                                    .foregroundStyle(.secondary)
                                    .lineLimit(1)
                            }
                            if let t = frame.tiles[i].temperatureC, let h = frame.tiles[i].humidityPct {
                                Text(String(format: "%.1f °C", t)).font(.title2)
                                Text(String(format: "%.1f %%RH", h)).foregroundStyle(.secondary)
                                let cz = ClimateMath.comfort(tempC: t, humidityPct: h)
                                let dp = ClimateMath.dewPointC(tempC: t, humidityPct: h)
                                let hi = ClimateMath.heatIndexC(tempC: t, humidityPct: h)
                                HStack(spacing: 4) {
                                    if let dp {
                                        Text(String(format: "DP %.1f", dp)).font(.caption2).foregroundStyle(.secondary)
                                    }
                                    if let hi, hi > t + 0.5 {
                                        Text(String(format: "HI %.1f", hi)).font(.caption2).foregroundStyle(.orange)
                                    }
                                    Spacer()
                                    Text(cz.label).font(.caption2.bold()).foregroundStyle(cz == .ok ? .green : .secondary)
                                }
                                if let wall = model.wallText(forTile: i) {
                                    Text(wall).font(.caption2).foregroundStyle(.secondary)
                                }
                                if let advice = model.ventilationAdvice(forTile: i) {
                                    Text(advice.title).font(.caption2.bold()).foregroundStyle(ventilationColor(advice.action))
                                        .lineLimit(1)
                                    Text(String(format: "AH %+.1f g/m³", advice.difference)).font(.system(size: 9))
                                        .foregroundStyle(.secondary).help(advice.message)
                                }
                            } else {
                                Text("--").font(.title2)
                                Text("-- %RH").foregroundStyle(.secondary)
                            }
                        }
                        .frame(maxWidth: .infinity).padding(8)
                        .background(.quaternary, in: RoundedRectangle(cornerRadius: 8))
                    }
                }
                HStack {
                    Text("Cell: " + (frame.cellPercent.map { "\($0) %" } ?? "unknown"))
                    Spacer()
                    TimelineView(.periodic(from: .now, by: 1)) { _ in
                        Text("Last frame: " + ageText(model.frameDate))
                    }
                }
                HStack {
                    Text("Alerts: " + (frame.alerts.names.isEmpty ? "none" : frame.alerts.names.joined(separator: ", ")))
                    Spacer()
                    Toggle("Notifications", isOn: Binding(
                        get: { model.notificationsEnabled },
                        set: { model.toggleNotifications($0) }
                    ))
                    .toggleStyle(.switch)
                    .controlSize(.small)
                }
            } else {
                Text("No telemetry frame yet. Set ble_broadcast_enabled=1 on the panel.").foregroundStyle(.secondary)
            }
            if !model.chartRows.isEmpty { logChartsView }
            loginItemRow
            exportButtons
        }
    }

    private var logChartsView: some View {
        struct Point: Identifiable { let id: Int; let time: Date; let tile: String; let t: Double?; let h: Double? }
        let points = model.chartRows.enumerated().flatMap { (n, r) in
            r.tiles.enumerated().map { (i, t) in
                Point(id: n * TelemetryFrame.tileCount + i, time: r.time, tile: model.config?.name(forSlot: i) ?? tileNames[min(i, tileNames.count - 1)],
                      t: t.temperatureC, h: t.humidityPct)
            }
        }
        return VStack(alignment: .leading) {
            Text("Temperature, last 7 days").font(.subheadline)
            Chart(points.filter { $0.t != nil }) { p in
                LineMark(x: .value("Time", p.time), y: .value("°C", p.t ?? 0)).foregroundStyle(by: .value("Tile", p.tile))
            }.frame(height: 160)
            Text("Relative humidity, last 7 days").font(.subheadline)
            Chart(points.filter { $0.h != nil }) { p in
                LineMark(x: .value("Time", p.time), y: .value("%RH", p.h ?? 0)).foregroundStyle(by: .value("Tile", p.tile))
            }.frame(height: 160)
        }
    }

    private var loginItemRow: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Toggle("Open at login", isOn: Binding(
                    get: { model.loginStatus.isOn },
                    set: { model.setOpenAtLogin($0) }
                ))
                .toggleStyle(.switch)
                .controlSize(.small)
                .disabled(model.loginStatus == .notAvailable)
                if model.loginStatus == .notAvailable {
                    Text("Not available without an app bundle").font(.caption).foregroundStyle(.secondary)
                }
                if model.loginStatus == .requiresApproval {
                    Button("Open Login Items settings") { model.openLoginItemSettings() }
                }
            }
            if let error = model.loginError {
                Text(error).font(.caption).foregroundStyle(.red)
            }
        }
    }

    private var exportButtons: some View {
        HStack {
            Button("Export telemetry log (CSV)") {
                model.exportCsv(name: "telemetry.csv", text: CsvExport.telemetry(model.telemetryLog))
            }.disabled(model.chartRows.isEmpty)
            Button("Export HISTORY (CSV)") {
                model.exportCsv(name: "history.csv", text: CsvExport.history(model.history))
            }.disabled(model.history.isEmpty)
            Button("Export EVENTS (CSV)") {
                model.exportCsv(name: "events.csv", text: CsvExport.events(model.filteredEvents))
            }.disabled(model.filteredEvents.isEmpty)
            Button("Export week CSV") {
                model.exportCsv(name: "week.csv", text: CsvExport.week(model.week))
            }.disabled(model.week.isEmpty)
        }
    }

    private var panelsView: some View {
        GroupBox("Panels") {
            VStack(alignment: .leading, spacing: 6) {
                if let id = model.pinnedPanel {
                    HStack {
                        Text("Pinned: \(id.uuidString)").font(.system(.caption, design: .monospaced))
                        Spacer()
                        Button("Forget panel") { model.forgetPanel() }
                    }
                } else {
                    Text("No panel pinned. The app accepts each panel in range.").font(.caption).foregroundStyle(.secondary)
                }
                TimelineView(.periodic(from: .now, by: 1)) { context in
                    VStack(alignment: .leading, spacing: 4) {
                        ForEach(model.discovered.panels) { p in
                            HStack {
                                Text(p.name ?? "(no name)")
                                Text("\(p.rssi) dBm").foregroundStyle(.secondary)
                                Text("last packet " + ageText(p.lastPacket, now: context.date)).foregroundStyle(.secondary)
                                Spacer()
                                if p.id == model.pinnedPanel {
                                    Text("pinned").font(.caption).foregroundStyle(.secondary)
                                } else {
                                    Button("Pin") { model.pinPanel(p.id) }
                                }
                            }
                            .font(.callout)
                            .help(p.id.uuidString)
                        }
                    }
                }
                if model.discovered.panels.isEmpty { Text("No panel found yet.").font(.caption).foregroundStyle(.secondary) }
            }.frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private var consoleSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Console").font(.headline)
            Picker("Link", selection: Binding(get: { model.transport }, set: { model.selectTransport($0) })) {
                ForEach(Transport.allCases, id: \.self) { Text($0.rawValue).tag($0) }
            }.pickerStyle(.segmented)
            Text(model.consoleState + (model.transport == .bluetooth && model.consoleAvailable ? " — bridge in range" : ""))
                .font(.caption).foregroundStyle(.secondary)
            HStack {
                Button(model.consoleConnected ? "Disconnect" : "Connect console") {
                    model.consoleConnected ? model.disconnectConsole() : model.connectConsole()
                }
                Button("Read STATUS, HISTORY, WEEK, EVENTS, CONFIG") { model.refresh() }
                    .disabled(!model.consoleConnected)
            }
            if let m = model.message { Text(m).font(.caption).foregroundStyle(.orange) }
            PanelView(model: model)
            ScannerView(model: model)
            ArchiveView(model: model)
            CalibrationView(model: model)
            if let s = model.status { statusView(s) }
            DiagnosticsView(model: model)
            if !model.history.isEmpty { chartsView }
            if !model.week.isEmpty { WeekChartView(rows: model.week) }
            if let c = model.config { configView(c) }
            ConfigEditorView(model: model)
            if !model.events.isEmpty { eventsView }
        }
    }

    private func statusView(_ s: StatusReply) -> some View {
        GroupBox("Status") {
            VStack(alignment: .leading) {
                // STATUS temperatures follow temp_unit; the unit comes from CONFIG ("?" until it arrives).
                let unit = model.config?.temperatureUnit ?? "?"
                ForEach(s.sensors, id: \.index) { x in
                    let mac = x.mac ?? model.config?.mac(forSlot: x.index)
                    let macText = mac.map { " (\($0))" } ?? ""
                    Text("\(x.name)\(x.badge.map { " \($0)" } ?? "")\(macText): \(x.temperature.map { String(format: "%.1f °\(unit)", $0) } ?? "--") "
                         + "\(x.humidity.map { String(format: "%.1f %%RH", $0) } ?? "--") [\(x.state)]")
                }
                Text("Power: \(s.powerSource ?? "?") \(s.cellVolts.map { String(format: "%.2f V", $0) } ?? "")")
                Text("Reset: \(s.resetReason ?? "--"), SD: \(s.sdStatus ?? "--")")
            }.frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private var chartsView: some View {
        let rows = model.history
        return VStack(alignment: .leading) {
            Text("Temperature, last 24 h").font(.subheadline)
            Chart(rows.filter { $0.temperatureC != nil }, id: \.self) { r in
                LineMark(x: .value("Hours ago", r.ageHours), y: .value("°C", r.temperatureC ?? 0))
                    .foregroundStyle(by: .value("Sensor", tileNames[min(r.sensor, tileNames.count - 1)]))
            }
            .chartXScale(domain: .automatic(reversed: true)).frame(height: 160)
            Text("Relative humidity, last 24 h").font(.subheadline)
            Chart(rows.filter { $0.humidityPct != nil }, id: \.self) { r in
                LineMark(x: .value("Hours ago", r.ageHours), y: .value("%RH", r.humidityPct ?? 0))
                    .foregroundStyle(by: .value("Sensor", tileNames[min(r.sensor, tileNames.count - 1)]))
            }
            .chartXScale(domain: .automatic(reversed: true)).frame(height: 160)
        }
    }

    private func configView(_ c: ConfigReply) -> some View {
        GroupBox("Config (source \(c.source ?? "?"), ble_console=\(c.bleConsoleLevel))") {
            VStack(alignment: .leading) {
                ForEach(c.keys, id: \.self) { k in
                    Text("\(k)=\(c.values[k] ?? "")").font(.system(.caption, design: .monospaced))
                }
            }.frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private var eventsView: some View {
        GroupBox("Events") {
            VStack(alignment: .leading) {
                Picker("Category", selection: $model.eventCategory) {
                    Text("All").tag(EventFilter.Category?.none)
                    ForEach(EventFilter.Category.allCases) { Text($0.title).tag(Optional($0)) }
                }
                .pickerStyle(.segmented)
                ForEach(Array(model.filteredEvents.enumerated()), id: \.offset) { _, e in
                    Text("\(e.time.isEmpty ? "--" : e.time)  \(EventText.describe(e, config: model.lastConfig))")
                        .font(.system(.caption, design: .monospaced))
                        .foregroundStyle(EventText.isAbnormal(e) ? Color.red : Color.primary)
                }
            }.frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

func ageText(_ date: Date?, now: Date = Date()) -> String {
    guard let date else { return "never" }
    let s = max(0, Int(now.timeIntervalSince(date)))
    return s < 60 ? "\(s) s ago" : "\(s / 60) min ago"
}

private func ventilationColor(_ action: VentilationAction) -> Color {
    switch action {
    case .luftungStarten, .weiterLuften: return .green
    case .luftungBeenden: return .blue
    case .luftungAbbrechen: return .orange
    case .nichtLuften: return .red
    case .neutral: return .secondary
    }
}
