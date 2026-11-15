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
                Divider()
                saltSection
            }.frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    /// Humidity check of one sensor in a closed box with saturated sodium chloride (6 hours at least).
    private var saltSection: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("Salt check").font(.headline)
            Text("Put one sensor in a closed box with saturated sodium chloride for 6 hours at least, then check it.")
                .font(.caption).foregroundStyle(.secondary)
            HStack {
                Picker("Sensor", selection: $model.saltSensor) {
                    Text("Onboard").tag(0)
                    ForEach(1...5, id: \.self) { Text("Slot \($0)").tag($0) }
                }.frame(maxWidth: 220)
                Button("Check") { model.saltCheck(sensor: model.saltSensor) }
                    .disabled(!model.isIdle)
            }
            if let reply = model.salt {
                switch reply.outcome {
                case let .measured(m, proposal):
                    Text("\(m.name): \(m.hours) h, mean \(String(format: "%.1f", m.rhMean)) %RH at "
                         + "\(String(format: "%.1f", m.tempMean)) °C, span \(String(format: "%.1f", m.rhSpan)) %RH, "
                         + "reference \(String(format: "%.1f", m.reference)) %RH")
                        .font(.callout)
                    if let p = proposal {
                        Toggle(isOn: $model.saltSelected) {
                            Text("\(p.key) = \(p.value)").font(.system(.caption, design: .monospaced))
                        }
                        Button("Apply proposal") { model.applySalt() }
                            .disabled(model.saltChanges.isEmpty || !model.isIdle)
                    }
                case let .skipped(name, hours):
                    Text("\(name): \(hours) h, too few samples (6 needed)").font(.caption).foregroundStyle(.secondary)
                case let .unstable(name, span):
                    Text("\(name): humidity span \(String(format: "%.1f", span)) %RH is above 1.0 %RH, wait and check again")
                        .font(.caption).foregroundStyle(.secondary)
                }
            }
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
