import Charts
import RlcdProtocol
import SwiftUI

/// Hardware diagnostics from the `STATUS` and `VERSION` replies, and maintenance actions.
struct DiagnosticsView: View {
    @Bindable var model: AppModel

    var body: some View {
        VStack(spacing: 12) {
            GroupBox("Hardware diagnostics") {
                VStack(alignment: .leading, spacing: 8) {
                    HStack {
                        Button("Refresh STATUS and VERSION") { model.refreshDiagnostics() }
                            .disabled(!model.consoleConnected || !model.isIdle)
                        Button("Copy report") { model.copyReport() }
                            .disabled(!model.canCopyReport)
                        Toggle("Include names and MACs", isOn: $model.reportIncludeNames)
                            .toggleStyle(.checkbox)
                        Spacer()
                    }
                    if let v = model.version {
                        Text("Firmware \(v.firmware) (git \(v.git), core \(v.core), state \(v.state), built \(v.built))")
                            .font(.caption).foregroundStyle(.secondary)
                    }
                    if let d = model.diagnostics {
                        group("Sensor health") { sensorHealth() }
                        group("SHTC3 sensor") { shtc3(d.shtc3) }
                        group("BLE rejected packets") { ble(d.ble) }
                        group("BLE reception (last 24 scans)") { reception(d.reception) }
                        group("Resets") { reset(d.reset) }
                        group("RTC frequency trim") { rtcTrim(d.rtcTrim) }
                        group("Battery runtime") { runtime(d.runtime) }
                        group("Awake time") { awake(d.awake) }
                    } else {
                        Text("No diagnostics yet. Press the refresh button.").font(.caption).foregroundStyle(.secondary)
                    }
                }.frame(maxWidth: .infinity, alignment: .leading)
            }

            GroupBox("Alert timeline") {
                alertTimeline().frame(maxWidth: .infinity, alignment: .leading)
            }

            GroupBox("Maintenance and tests") {
                VStack(alignment: .leading, spacing: 8) {
                    group("Panel clock") {
                        if let t = model.panelTime {
                            HStack(spacing: 8) {
                                Text("Time: \(t.timeString)")
                                if t.valid {
                                    Text("VALID").font(.caption.bold()).foregroundStyle(.green)
                                } else {
                                    Text("INVALID").font(.caption.bold()).foregroundStyle(.white)
                                        .padding(.horizontal, 4).background(Color.red, in: RoundedRectangle(cornerRadius: 4))
                                }
                                if t.osf {
                                    Text("OSCILLATOR STOPPED").font(.caption.bold()).foregroundStyle(.white)
                                        .padding(.horizontal, 4).background(Color.red, in: RoundedRectangle(cornerRadius: 4))
                                }
                            }
                            if let off = model.panelOffsetSeconds {
                                Text("Offset to Mac: \(off >= 0 ? "+" : "")\(off) s")
                            }
                        } else {
                            Text("not read yet").foregroundStyle(.secondary)
                        }
                    }

                    HStack(spacing: 12) {
                        Button("Sync clock") { model.syncClock() }
                            .disabled(!model.canTimeSet || !model.isIdle)
                        Button("Read clock") { model.readTime() }
                            .disabled(!model.consoleConnected || !model.isIdle)

                        Menu("Chime test...") {
                            ForEach(0..<8) { bit in
                                Button("BEEP \(bit) (\(AppModel.chimeName(bit)))") {
                                    model.beep(bit)
                                }
                            }
                        }
                        .disabled(!model.canBeep || !model.isIdle)

                        Menu("Display test...") {
                            Button("PATTERN 0 (Black)") { confirmPattern(0) }
                            Button("PATTERN 1 (Checkerboard)") { confirmPattern(1) }
                            Button("PATTERN 2 (Border with cross)") { confirmPattern(2) }
                        }
                        .disabled(!model.canPattern || !model.isIdle)
                    }

                    if let s = model.status {
                        Toggle("Mute alerts", isOn: Binding(get: { s.muted }, set: { model.setMute($0) }))
                            .disabled(!model.canSet || !model.isIdle)
                    }

                    if let r = model.lastChimeResult {
                        Text("Chime reply: \(r)").font(.caption.monospaced())
                    }
                    if let r = model.lastPatternResult {
                        Text("Display pattern reply: \(r)").font(.caption.monospaced())
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .confirmationDialog(
            "Show test pattern?",
            isPresented: $model.showingPatternAlert,
            titleVisibility: .visible
        ) {
            Button("Show Pattern", role: .destructive) {
                if let p = model.pendingPattern {
                    model.pattern(p)
                }
                model.pendingPattern = nil
            }
            Button("Cancel", role: .cancel) {
                model.pendingPattern = nil
            }
        } message: {
            Text("The panel will show this test pattern until the next wake cycle.")
        }
    }

    private func confirmPattern(_ n: Int) {
        model.pendingPattern = n
        model.showingPatternAlert = true
    }

    private func group<C: View>(_ title: String, @ViewBuilder _ content: () -> C) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title).font(.subheadline.bold())
            content().font(.system(.caption, design: .monospaced))
        }
    }

    /// Horizontal bars of the alert intervals from the event ring (`model.events`, filled by the existing read action).
    @ViewBuilder private func alertTimeline() -> some View {
        let intervals = AlertTimeline.build(rows: model.events, now: Date())
        if intervals.isEmpty {
            Text("No alert events. Read the panel data to load the event ring.")
                .font(.caption).foregroundStyle(.secondary)
        } else {
            let names = Array(Set(intervals.map(\.name))).sorted()
            Chart(Array(intervals.enumerated()), id: \.offset) { _, i in
                BarMark(xStart: .value("Start", i.start), xEnd: .value("End", max(i.end, i.start.addingTimeInterval(60))), y: .value("Alert", i.name))
                    .foregroundStyle(i.isOpen ? Color.red : Color.orange)
                    .opacity(i.startUnknown ? 0.5 : 1)
            }
            .chartYScale(domain: names)
            .frame(height: CGFloat(names.count) * 28 + 40)
            Text("Dimmed bar: start unknown. Red bar: alert still active.")
                .font(.caption).foregroundStyle(.secondary)
        }
    }

    @ViewBuilder private func shtc3(_ m: Shtc3Stats?) -> some View {
        if let m {
            Text("nack \(n(m.nack))  short \(n(m.short))  crc \(n(m.crc))")
            if let retried = m.retried { Text("retried after soft reset: \(retried)") }
            Text("failures in a row: \(n(m.run))")
                .foregroundStyle(m.isAlert ? .white : .primary)
                .padding(.horizontal, 4)
                .background(m.isAlert ? Color.red : Color.clear, in: RoundedRectangle(cornerRadius: 4))
        } else { unknown }
    }

    @ViewBuilder private func ble(_ m: BleRejectMetrics?) -> some View {
        if let m {
            let tpPart = m.thermopro.map { "  thermopro \(n($0))" } ?? ""
            Text("rejected \(n(m.rejected))  last \(m.lastMac ?? "--")")
            Text("woio \(n(m.woio))  meter \(n(m.meter))  bthome \(n(m.bthome))  atc \(n(m.atc))\(tpPart)")
            if let unassigned = m.unassigned {
                Text("unassigned \(n(unassigned))")
            }
        } else { unknown }
    }

    @ViewBuilder private func sensorHealth() -> some View {
        if let status = model.status {
            sensorRows(status)
        } else { unknown }
    }

    @ViewBuilder private func sensorRows(_ status: StatusReply) -> some View {
        switch Result(catching: { try SensorHealth.rows(status: status, config: model.lastConfig) }) {
        case .failure(let error):
            Text("Sensor health unavailable: \(String(describing: error))").foregroundStyle(.secondary)
        case .success(let rows):
            ForEach(rows, id: \.slot) { r in
                HStack(spacing: 8) {
                    Text("\(r.slot) \(r.name)\(r.badge.map { " \($0)" } ?? "")").frame(width: 140, alignment: .leading)
                    Text(r.mac ?? "--").frame(width: 130, alignment: .leading).foregroundStyle(.secondary)
                    Text("RSSI \(r.rssi.map { String(format: "%.0f", $0) } ?? "--")")
                    Text("bat \(r.battery.map { String(format: "%.0f %%", $0) } ?? "--")")
                    Text("age \(r.ageSeconds.map { String(format: "%.0f s", $0) } ?? "--")")
                    Text(r.reception.map { "\($0.received)/\($0.scans)" } ?? "--")
                    Text(r.verdict.rawValue).font(.caption.bold())
                        .foregroundStyle(r.verdict == .good ? Color.green : r.verdict == .empty ? Color.secondary : Color.orange)
                    Text(r.advice).font(.caption).foregroundStyle(.secondary)
                }
            }
        }
    }

    @ViewBuilder private func reception(_ m: ReceptionMetrics?) -> some View {
        if let m {
            ForEach(Array(m.slots.enumerated()), id: \.offset) { index, rate in
                HStack(spacing: 4) {
                    Text("slot \(index + 1)")
                    if let rate {
                        Text("\(rate.received)/\(rate.scans) (\(rate.percent) %)")
                        if rate.isWeak {
                            Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(.orange)
                                .accessibilityLabel("Weak reception")
                        }
                    } else {
                        Text("--").foregroundStyle(.secondary)
                    }
                }
            }
        } else { unknown }
    }

    @ViewBuilder private func reset(_ m: ResetMetrics?) -> some View {
        if let m {
            HStack {
                Text("reason \(m.reason)")
                if m.isAbnormal {
                    Text("ABNORMAL").font(.caption.bold()).foregroundStyle(.white)
                        .padding(.horizontal, 6).background(Color.red, in: Capsule())
                }
            }
            Text("abnormal resets: \(n(m.abnormalCount))")
        } else { unknown }
    }

    @ViewBuilder private func rtcTrim(_ m: RtcTrimMetrics?) -> some View {
        if let m {
            Text("offset register \(m.trim.map(String.init) ?? "--")  drift \(m.driftPpm.map { String(format: "%+d ppm", $0) } ?? "--")")
            if m.canTrim {
                Button("Trim RTC") { model.trimRtc() }
                    .disabled(!model.canSet || !model.isIdle)
            }
        } else { unknown }
    }

    @ViewBuilder private func runtime(_ m: RuntimeMetrics?) -> some View {
        if let m {
            Text("remaining \(m.hours.map { String(format: "%.0f h", $0) } ?? "--")  samples \(n(m.samples))")
            Text("cycles \(m.cycles.map { String(format: "%.1f", $0) } ?? "--")  scan saved \(n(m.scanSavedMs)) ms")
        } else { unknown }
    }

    @ViewBuilder private func awake(_ m: AwakeMetrics?) -> some View {
        if let m {
            Text("last \(n(m.lastMs)) ms  avg \(n(m.avgMs)) ms  max \(n(m.maxMs)) ms  samples \(n(m.samples))")
        } else { unknown }
    }

    private var unknown: some View { Text("not reported by this firmware").foregroundStyle(.secondary) }
    private func n(_ v: Int?) -> String { v.map(String.init) ?? "--" }
}
