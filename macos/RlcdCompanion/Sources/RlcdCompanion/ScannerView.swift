import RlcdProtocol
import SwiftUI

/// Lists the sensors in range of the panel and assigns one to a slot. Needs the USB serial link.
struct ScannerView: View {
    @Bindable var model: AppModel

    var body: some View {
        GroupBox("Sensor scanner") {
            VStack(alignment: .leading, spacing: 6) {
                HStack {
                    Button("Scan (4 s)") { model.scan() }
                        .disabled(!model.consoleConnected || model.transport != .usb)
                    Toggle("Repeat (15 s)", isOn: Binding(get: { model.scanSeries.isRunning }, set: { model.setRepeatScan($0) }))
                        .disabled(!model.consoleConnected || model.transport != .usb)
                    Menu("Clear slot") {
                        ForEach(Array(SlotAssignment.slots), id: \.self) { n in
                            Button("Clear slot \(n)") { model.assign(slot: n, mac: nil) }
                        }
                    }
                    .disabled(!model.canSet)
                    if model.transport != .usb {
                        Text("SCAN needs the USB serial link.").font(.caption).foregroundStyle(.secondary)
                    }
                }
                if let reason = model.scanSeries.stopReason, !model.scanSeries.isRunning {
                    Text("Repeat stopped: \(Self.text(reason)).").font(.caption).foregroundStyle(.secondary)
                }
                if model.scanResult == nil {
                    // The rows are gone (SET, timeout, ERR) but the series stays visible.
                    ForEach(model.scanSeries.stats, id: \.mac) { st in
                        Text(st.mac).font(.system(.caption, design: .monospaced))
                        seriesView(st)
                    }
                }
                if let scan = model.scanResult {
                    if scan.rows.isEmpty && model.scanSeries.stats.isEmpty { Text("No sensor found.").font(.caption).foregroundStyle(.secondary) }
                    ForEach(scan.sortedByRssi) { row in
                        rowView(row)
                        if let st = model.scanSeries.stats(for: row.mac) { seriesView(st) }
                    }
                    // A MAC that the last scan missed keeps its series.
                    ForEach(model.scanSeries.stats.filter { st in !scan.rows.contains { $0.mac == st.mac } }, id: \.mac) { st in
                        Text(st.mac).font(.system(.caption, design: .monospaced))
                        seriesView(st)
                    }
                    if scan.dropped > 0 {
                        Text("\(scan.dropped) more sensors not listed.").font(.caption).foregroundStyle(.secondary)
                    }
                }
            }.frame(maxWidth: .infinity, alignment: .leading)
        }
        .onDisappear { model.stopRepeatScan(.viewClosed) }
    }

    private func seriesView(_ s: ScanSeries.Stats) -> some View {
        Text("last \(s.count): min \(s.min) / mean \(String(format: "%.1f", s.mean)) / max \(s.max) dBm, missed \(s.missed) of \(s.scans) scans")
            .font(.caption).foregroundStyle(.secondary)
    }

    private static func text(_ r: ScanSeries.StopReason) -> String {
        switch r {
        case .user: "turned off"
        case .viewClosed: "view closed"
        case .timeout: "10 minutes over"
        case .linkLost: "link lost"
        case .scanFailed: "SCAN failed"
        case .notUsb: "needs USB"
        }
    }

    private func rowView(_ r: ScanRow) -> some View {
        HStack {
            Text(r.mac).font(.system(.caption, design: .monospaced))
            Text(r.format).font(.caption)
            Text("\(r.rssi) dBm").foregroundStyle(.secondary)
            Text(r.temperatureC.map { String(format: "%.1f °C", $0) } ?? "--")
            Text(r.humidityPct.map { String(format: "%.1f %%RH", $0) } ?? "--")
            Text(r.batteryPct.map { String(format: "%.0f %%", $0) } ?? "--")
            Text(r.slot.map { "slot \($0)" } ?? "no slot").foregroundStyle(.secondary)
            Spacer()
            Menu("Assign") {
                ForEach(Array(SlotAssignment.slots), id: \.self) { n in
                    Button("Slot \(n)" + (SlotAssignment.warning(slot: n, mac: r.mac, config: model.config) != nil ? " (MAC in use)" : "")) {
                        model.assign(slot: n, mac: r.mac)
                    }
                }
            }
            .disabled(!model.canSet)
        }.font(.callout)
    }
}
