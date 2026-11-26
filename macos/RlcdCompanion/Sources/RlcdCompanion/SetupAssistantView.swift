import RlcdProtocol
import SwiftUI

/// Guided setup of one new sensor: scan, choose, assign, verify, calibrate. Each button sends the commands of one step.
struct SetupAssistantView: View {
    @Bindable var model: AppModel

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            if let a = model.setup {
                header(a)
                if let stop = a.stop {
                    Text(stop.text).font(.caption).foregroundStyle(.red)
                    Text("The panel keeps the settings that it already applied.").font(.caption).foregroundStyle(.secondary)
                } else if let notice = a.notice {
                    Text(notice).font(.caption).foregroundStyle(.orange)
                }
                if !a.isStopped { stepView(a) }
            } else {
                Button("Set up a new sensor") { model.openSetup() }
                    .disabled(!model.consoleConnected || model.transport != .usb)
            }
        }
    }

    private func header(_ a: SetupAssistant) -> some View {
        HStack {
            Text("Setup: \(Self.title(a.step))").font(.headline)
            Spacer()
            Button(a.step == .done || a.isStopped ? "Close" : "Cancel") { model.closeSetup() }
        }
    }

    @ViewBuilder private func stepView(_ a: SetupAssistant) -> some View {
        switch a.step {
        case .scan:
            Text("Put the new sensor next to the panel.").font(.caption)
            Button("Scan (4 s)") { model.setupScan() }.disabled(!model.isIdle)
        case .choose:
            if a.candidates.isEmpty { Text("No new sensor found.").font(.caption).foregroundStyle(.secondary) }
            ForEach(a.candidates) { row in
                HStack {
                    Text(row.mac).font(.system(.caption, design: .monospaced))
                    Text("\(row.format) \(row.rssi) dBm").font(.caption).foregroundStyle(.secondary)
                    Spacer()
                    Button("Choose") { model.setupChosen = row.mac }.disabled(!model.isIdle)
                }
            }
            if let mac = model.setupChosen {
                HStack {
                    TextField("Name", text: $model.setupName).textFieldStyle(.roundedBorder).frame(width: 140)
                        .help("Optional, up to \(SlotAssignment.maxNameChars) characters: A-Z, 0-9, -.")
                    Button("Use \(mac)") { model.setupChoose(mac: mac, name: model.setupName) }
                }
            }
            Button("Scan again") { model.setupScan() }.disabled(!model.isIdle)
        case .assign:
            Text("\(a.chosenMac ?? "") \(a.chosenName)").font(.system(.caption, design: .monospaced))
            if model.status == nil || model.config == nil {
                Text("Read STATUS and CONFIG first.").font(.caption).foregroundStyle(.secondary)
            }
            Button("Read STATUS and CONFIG") { model.refresh() }.disabled(!model.isIdle)
            Button("Assign to the first free slot") { model.setupAssign() }
                .disabled(!model.isIdle || !model.canSet || model.status == nil || model.config == nil)
        case .verify:
            Text("Slot \(a.slot ?? 0) waits for a packet of \(a.chosenMac ?? ""). This can take a wake interval.").font(.caption)
            Button("Check reception") { model.setupCheckReception() }.disabled(!model.isIdle)
        case .calibrate:
            HStack {
                Picker("Reference", selection: $model.setupReference) {
                    Text("Onboard").tag(0)
                    ForEach(SlotAssignment.slots.filter { $0 != a.slot }, id: \.self) { Text("Slot \($0)").tag($0) }
                }.frame(maxWidth: 220)
                Button("Compare") { model.setupCalibrate(reference: model.setupReference) }.disabled(!model.isIdle)
            }
            if let c = a.calibration {
                if c.proposals.isEmpty { Text("No proposal yet. Compare again after more hours.").font(.caption).foregroundStyle(.secondary) }
                ForEach(c.proposals, id: \.key) { p in
                    Toggle("\(p.key) = \(p.value)", isOn: Binding(
                        get: { a.selection.contains(p.key) }, set: { _ in model.setup?.toggle(proposal: p.key) }))
                        .font(.caption)
                }
                Button("Apply selected") { model.setupApply() }
                    .disabled(a.calibrationChanges.isEmpty || !model.isIdle || !model.canSet)
            }
            if let o = a.outdoorProposal(week: model.week, config: model.config) {
                Text(o.text).font(.caption)
                Button("Use slot \(o.slot) as outdoor slot") { model.setupApplyOutdoor() }
                    .disabled(!model.isIdle || !model.canSet)
            }
            if let d = a.doorProposal(week: model.week, config: model.config) {
                Text(d.text).font(.caption)
                Button("Use slot \(d.slot) as door reference") { model.setupApplyDoor() }
                    .disabled(!model.isIdle || !model.canSet)
            }
            if model.week.isEmpty {
                Text("No WEEK data for an outdoor proposal.").font(.caption).foregroundStyle(.secondary)
                Button("Read week data") { model.refresh() }.disabled(!model.isIdle)
            }
            Button("Finish without calibration") { model.setup?.finish() }
        case .done:
            Text("Setup done.").font(.caption)
        }
    }

    private static func title(_ s: SetupAssistant.Step) -> String {
        switch s {
        case .scan: "1 Scan"
        case .choose: "2 Choose"
        case .assign: "3 Assign"
        case .verify: "4 Verify"
        case .calibrate: "5 Calibrate"
        case .done: "Done"
        }
    }
}
