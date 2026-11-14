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
                if let scan = model.scanResult {
                    if scan.rows.isEmpty { Text("No sensor found.").font(.caption).foregroundStyle(.secondary) }
                    ForEach(scan.sortedByRssi) { row in rowView(row) }
                    if scan.dropped > 0 {
                        Text("\(scan.dropped) more sensors not listed.").font(.caption).foregroundStyle(.secondary)
                    }
                }
            }.frame(maxWidth: .infinity, alignment: .leading)
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
