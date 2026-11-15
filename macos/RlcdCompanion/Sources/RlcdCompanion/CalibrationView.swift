import RlcdProtocol
import SwiftUI

/// Compares the sensors with one reference through `CALIB N` and applies the proposed offsets.
struct CalibrationView: View {
    @Bindable var model: AppModel

    var body: some View {
        GroupBox("Calibration assistant") {
            VStack(alignment: .leading, spacing: 6) {
                HStack {
                    Picker("Reference", selection: $model.calibrationReference) {
                        Text("Onboard").tag(0)
                        ForEach(1...5, id: \.self) { Text("Slot \($0)").tag($0) }
                    }.frame(maxWidth: 220)
                    Button("Compare") { model.calibrate(reference: model.calibrationReference) }
                        .disabled(!model.isIdle)
                }
                if let c = model.calibration {
                    Text("Reference: \(c.reference)").font(.caption)
                    if c.sensors.isEmpty && c.skipped.isEmpty {
                        Text("No other sensor.").font(.caption).foregroundStyle(.secondary)
                    }
                    ForEach(Array(c.sensors.enumerated()), id: \.offset) { _, s in sensorRow(s) }
                    ForEach(Array(c.skipped.enumerated()), id: \.offset) { _, s in
                        Text("\(s.name): \(s.hours) h, too few common hours (6 needed)")
                            .font(.caption).foregroundStyle(.secondary)
                    }
                    ForEach(c.proposals, id: \.key) { p in proposalRow(p) }
                    HStack {
                        Button("Apply selected") { model.applyCalibration() }
                            .disabled(model.calibrationChanges.isEmpty || !model.isIdle)
                        if !model.canSet {
                            Text("The panel may refuse SET (ERR READ_ONLY) over BLE with ble_console below 2.")
                                .font(.caption).foregroundStyle(.secondary)
                        }
                    }
                }
                if let s = model.batchSummary { Text(s).font(.caption).foregroundStyle(.secondary) }
            }.frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private func sensorRow(_ s: CalibrationReply.Sensor) -> some View {
        Text("\(s.name): \(s.hours) h, diff \(String(format: "%+.1f", s.tempDiff)) °C / \(String(format: "%+.1f", s.rhDiff)) %RH, "
             + "offset now \(String(format: "%.1f", s.tempOffset)) °C / \(String(format: "%.1f", s.rhOffset)) %RH")
            .font(.callout)
    }

    private func proposalRow(_ p: CalibrationReply.Proposal) -> some View {
        Toggle(isOn: Binding(
            get: { model.calibrationSelection.contains(p.key) },
            set: { on in
                if on { model.calibrationSelection.insert(p.key) } else { model.calibrationSelection.remove(p.key) }
            })) {
            Text("\(p.key) = \(p.value)").font(.system(.caption, design: .monospaced))
        }
    }
}
