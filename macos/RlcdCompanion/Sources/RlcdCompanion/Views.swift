import Charts
import RlcdProtocol
import SwiftUI

private let tileNames = ["Onboard", "Slot 1", "Slot 2", "Slot 3"]

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
    }

    private var telemetrySection: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Telemetry").font(.headline)
            Text(model.bluetoothState).font(.caption).foregroundStyle(.secondary)
            if let frame = model.frame {
                LazyVGrid(columns: Array(repeating: GridItem(.flexible()), count: 4)) {
                    ForEach(0..<4, id: \.self) { i in
                        VStack {
                            Text(tileNames[i]).font(.caption)
                            if let t = frame.tiles[i].temperatureC, let h = frame.tiles[i].humidityPct {
                                Text(String(format: "%.1f °C", t)).font(.title2)
                                Text(String(format: "%.1f %%RH", h)).foregroundStyle(.secondary)
                            } else {
                                Text("--").font(.title2)
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
                Text("Alerts: " + (frame.alerts.names.isEmpty ? "none" : frame.alerts.names.joined(separator: ", ")))
            } else {
                Text("No telemetry frame yet. Set ble_broadcast_enabled=1 on the panel.").foregroundStyle(.secondary)
            }
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
                Button("Read STATUS, HISTORY, EVENTS, CONFIG") { model.refresh() }
                    .disabled(!model.consoleConnected)
            }
            if let m = model.message { Text(m).font(.caption).foregroundStyle(.orange) }
            if let s = model.status { statusView(s) }
            if !model.history.isEmpty { chartsView }
            if let c = model.config { configView(c) }
            if !model.events.isEmpty { eventsView }
        }
    }

    private func statusView(_ s: StatusReply) -> some View {
        GroupBox("Status") {
            VStack(alignment: .leading) {
                // STATUS temperatures follow temp_unit; the unit comes from CONFIG ("?" until it arrives).
                let unit = model.config?.temperatureUnit ?? "?"
                ForEach(s.sensors, id: \.index) { x in
                    Text("\(x.name): \(x.temperature.map { String(format: "%.1f °\(unit)", $0) } ?? "--") "
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
                    .foregroundStyle(by: .value("Sensor", tileNames[min(r.sensor, 3)]))
            }
            .chartXScale(domain: .automatic(reversed: true)).frame(height: 160)
            Text("Relative humidity, last 24 h").font(.subheadline)
            Chart(rows.filter { $0.humidityPct != nil }, id: \.self) { r in
                LineMark(x: .value("Hours ago", r.ageHours), y: .value("%RH", r.humidityPct ?? 0))
                    .foregroundStyle(by: .value("Sensor", tileNames[min(r.sensor, 3)]))
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
                if model.canSet {
                    HStack {
                        TextField("key", text: $model.setKey)
                        TextField("value", text: $model.setValue)
                        Button("SET") { model.set(key: model.setKey, value: model.setValue) }
                            .disabled(model.setKey.isEmpty || model.setValue.isEmpty)
                    }
                } else {
                    Text("SET needs ble_console=2 or the USB serial link.").font(.caption).foregroundStyle(.secondary)
                }
            }.frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private var eventsView: some View {
        GroupBox("Events") {
            VStack(alignment: .leading) {
                ForEach(Array(model.events.enumerated()), id: \.offset) { _, e in
                    Text("\(e.time.isEmpty ? "--" : e.time)  \(e.event)  \(e.detail)")
                        .font(.system(.caption, design: .monospaced))
                }
            }.frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

func ageText(_ date: Date?) -> String {
    guard let date else { return "never" }
    let s = Int(Date().timeIntervalSince(date))
    return s < 60 ? "\(s) s ago" : "\(s / 60) min ago"
}
