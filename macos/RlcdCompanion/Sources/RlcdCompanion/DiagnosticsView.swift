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
                        Button("Run self-test") { model.runSelfTest() }
                            .disabled(!model.canCopyReport)
                        Spacer()
                    }
                    if let v = model.version {
                        Text("Firmware \(v.firmware) (git \(v.git), core \(v.core), state \(v.state), built \(v.built))")
                            .font(.caption).foregroundStyle(.secondary)
                    }
                    if let t = model.selfTestResult {
                        group("Self-test (\(t.pass) ok, \(t.fail) failed)") { selfTest(t) }
                    }
                    if let d = model.diagnostics {
                        group("Sensor health") { sensorHealth() }
                        group("SHTC3 sensor") { shtc3(d.shtc3) }
                        group("BLE rejected packets") { ble(d.ble) }
                        group("BLE reception (last 24 scans)") { reception(d.reception) }
                        group("Resets") { reset(d.reset) }
                        group("RTC frequency trim") { rtcTrim(d.rtcTrim) }
                        group("Clock sync (NTP)") { ntp(d.ntp) }
                        group("Battery runtime") { runtime(d.runtime) }
                        group("Awake time") { awake(d.awake) }
                        group("Memory") { memory(d.memory) }
                        group("Display refresh") { frame(d.frame) }
                        group("Wake cycle") { wake(d.wake) }
                        group("Panel badges") { latch(d.latch) }
                        group("Noise") { noise(d.noise) }
                        group("Quiet hours") { quiet(d.quiet) }
                        group("MicroSD card") { sdCard(model.status) }
                    } else {
                        Text("No diagnostics yet. Press the refresh button.").font(.caption).foregroundStyle(.secondary)
                    }
                }.frame(maxWidth: .infinity, alignment: .leading)
            }

            GroupBox("Alert timeline") {
                alertTimeline().frame(maxWidth: .infinity, alignment: .leading)
            }

            GroupBox("Badge intervals") {
                badgeIntervals().frame(maxWidth: .infinity, alignment: .leading)
            }

            GroupBox("Today") {
                todayBox.frame(maxWidth: .infinity, alignment: .leading)
            }

            GroupBox("Newest log rows") {
                tailBox.frame(maxWidth: .infinity, alignment: .leading)
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

                    if let s = model.status {
                        alertRows(s)
                    }
                    if let c = model.diagnostics?.chime {
                        Text(c.reminderText).font(.caption).foregroundStyle(.secondary)
                        if let t = c.snoozeText { Text(t).font(.caption).foregroundStyle(.secondary) }
                    }

                    if let r = model.lastChimeResult {
                        Text("Chime reply: \(r)").font(.caption.monospaced())
                    }
                    HStack {
                        Button("Identify panel") { model.identifyPanel() }
                            .disabled(!model.consoleConnected || !model.isIdle)
                        if let r = model.identifyResult { Text(r).font(.caption.monospaced()) }
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

    /// Row count picker, read button and the newest climate rows. Sends `TAIL` only on the button.
    private var todayBox: some View {
        VStack(alignment: .leading, spacing: 4) {
            Button("Read today") { model.readToday() }
                .disabled(!model.canCopyReport)
            if let rows = model.today {
                if rows.isEmpty {
                    Text("No sensor holds a sample.").font(.caption).foregroundStyle(.secondary)
                } else {
                    Grid(alignment: .leading, horizontalSpacing: 10, verticalSpacing: 2) {
                        GridRow {
                            Text("Sensor"); Text("Hours"); Text("Temp min / max / mean"); Text("RH min / max / mean")
                        }.font(.caption.bold())
                        ForEach(rows, id: \.sensor) { row in
                            GridRow {
                                Text(row.name)
                                Text("\(row.hours)")
                                Text(row.hours == 0 ? "--" : todayTriple(row.temperatureMinC, row.temperatureMaxC, row.temperatureMeanC, unit: "°C"))
                                Text(row.hours == 0 ? "--" : todayTriple(row.humidityMinPct, row.humidityMaxPct, row.humidityMeanPct, unit: "%"))
                            }
                        }
                    }
                    .font(.system(.caption, design: .monospaced))
                }
            } else {
                Text("not read yet").font(.caption).foregroundStyle(.secondary)
            }
        }
    }

    private func todayTriple(_ lo: Double?, _ hi: Double?, _ mean: Double?, unit: String) -> String {
        [lo, hi, mean].map { $0.map { String(format: "%.1f", $0) } ?? "--" }.joined(separator: " / ") + " " + unit
    }

    private var tailBox: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 8) {
                Picker("Rows", selection: $model.tailRows) {
                    ForEach(TailReply.rowChoices, id: \.self) { Text("\($0)").tag($0) }
                }
                .pickerStyle(.segmented).frame(maxWidth: 160)
                Button("Read log rows") { model.readTail(rows: model.tailRows) }
                    .disabled(!model.canCopyReport)
            }
            if let tail = model.tailResult {
                if tail.rows.isEmpty {
                    Text("No rows in the log.").font(.caption).foregroundStyle(.secondary)
                } else {
                    TailGapSummary(report: LogGaps.report(rows: tail.rows))
                    ScrollView(.vertical) {
                        Grid(alignment: .leading, horizontalSpacing: 10, verticalSpacing: 2) {
                            GridRow {
                                Text("Time"); Text("Cell"); Text("USB"); Text("Onboard"); Text("Slots")
                            }.font(.caption.bold())
                            ForEach(Array(tail.rows.reversed().enumerated()), id: \.offset) { _, row in tailRow(row) }
                        }
                        .font(.system(.caption, design: .monospaced))
                    }
                    .frame(maxHeight: 220)
                }
            } else {
                Text("not read yet").font(.caption).foregroundStyle(.secondary)
            }
        }
    }

    private func tailRow(_ row: ClimateRow) -> some View {
        let slots = row.sensors.enumerated().dropFirst().compactMap { index, reading -> String? in
            guard reading.tempC != nil || reading.rh != nil else { return nil }
            return "s\(index) \(tailValue(reading))"
        }
        return GridRow {
            Text(row.timestamp)
            Text(row.batteryPct.map { "\($0)%" } ?? "--")
            Text(row.usb.map { $0 ? "yes" : "no" } ?? "--")
            Text(row.sensors.first.map(tailValue) ?? "--")
            Text(slots.isEmpty ? "--" : slots.joined(separator: "  "))
        }
    }

    private func tailValue(_ reading: ClimateSensorReading) -> String {
        let t = reading.tempC.map { String(format: "%.1f°C", $0) } ?? "--"
        let h = reading.rh.map { String(format: "%.0f%%", $0) } ?? "--"
        return "\(t) \(h)"
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

    /// One row for each active alert bit with `Acknowledge` or `Acknowledged`, and `Acknowledge all` for two or more open bits.
    @ViewBuilder private func alertRows(_ s: StatusReply) -> some View {
        if ConsoleCommand.canAcknowledgeAll(alertMask: s.alertMask, ackedMask: s.ackedMask) {
            Button("Acknowledge all") { model.acknowledgeAll() }
                .disabled(!model.canSet || !model.isIdle)
        }
        ForEach((0..<8).filter { s.alertMask & (1 << $0) != 0 }, id: \.self) { bit in
            HStack {
                Text(AlertMask(rawValue: 1 << UInt8(bit)).names.first ?? "Alert \(bit)")
                Spacer()
                if s.ackedMask & (1 << UInt8(bit)) != 0 {
                    Text("Acknowledged").foregroundStyle(.secondary)
                } else {
                    Button("Acknowledge") { model.acknowledge(bit: bit) }
                        .disabled(!model.canSet || !model.isIdle)
                }
            }
        }
        if let m = model.message, m == ConsoleCommand.ackMessage("ERR NOT_ACTIVE") || m == ConsoleCommand.ackMessage("ERR READ_ONLY") {
            Text(m).font(.caption).foregroundStyle(.red)
        } else if let m = model.message, m.hasPrefix("Acknowledged: ") {
            Text(m).font(.caption).foregroundStyle(.secondary)
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

    /// One line for each badge interval (newest first): badge, room, start, end and duration.
    @ViewBuilder private func badgeIntervals() -> some View {
        let intervals = AlertTimeline.badges(rows: model.events, now: Date()).sorted { ($0.start, $0.event, $0.tile) > ($1.start, $1.event, $1.tile) }
        if intervals.isEmpty {
            Text("No badge events. Read the panel data to load the event ring.")
                .font(.caption).foregroundStyle(.secondary)
        } else {
            VStack(alignment: .leading, spacing: 2) {
                ForEach(Array(intervals.enumerated()), id: \.offset) { _, i in
                    Text(BadgeIntervalText.line(i, config: model.lastConfig))
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(i.isOpen ? Color.red : Color.primary)
                }
            }
            Text("~ marks an unknown start. Red line: badge still set.")
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
            if let jumps = m.jumps {
                Text("jumps \(n(jumps))")
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
                    let live = status.sensors.first { $0.index == r.slot }
                    Text("\(live?.temperature.map { String(format: "%.1f °\(model.config?.temperatureUnit ?? "?")", $0) } ?? "--")\(live?.temperatureTrend?.symbol ?? "")")
                        .help("Temperature trend over 3 hours")
                    Text("\(live?.humidity.map { String(format: "%.1f %%RH", $0) } ?? "--")\(live?.humidityTrend?.symbol ?? "")")
                        .help("Humidity trend over 3 hours")
                    if let dry = live?.dryHours {
                        Text("Dry \(dry) h")
                        if dry > 0 {
                            Image(systemName: "exclamationmark.triangle").foregroundStyle(.orange)
                                .help("Hours at or below dry_alert_limit_rh in the last 24 hours")
                                .accessibilityLabel("Dry hours")
                        }
                    }
                    Text("RSSI \(r.rssi.map { String(format: "%.0f", $0) } ?? "--")")
                    Text("bat \(r.battery.map { String(format: "%.0f %%", $0) } ?? "--")")
                    Text("age \(r.ageSeconds.map { String(format: "%.0f s", $0) } ?? "--")")
                    Text(r.reception.map { "\($0.received)/\($0.scans)" } ?? "--")
                    if model.diagnostics?.latch?.hasWeakSignal(slot: r.slot) == true {
                        Image(systemName: "antenna.radiowaves.left.and.right.slash").foregroundStyle(.orange)
                            .help("Weak BLE signal (rxlow)")
                            .accessibilityLabel("Weak signal")
                    }
                    if model.diagnostics?.latch?.hasDrift(sensor: r.slot) == true {
                        Button {
                            model.openCalibrationForDrift()
                        } label: {
                            Image(systemName: "arrow.up.and.down.circle").foregroundStyle(.orange)
                        }
                        .buttonStyle(.borderless)
                        .disabled(!model.consoleConnected)
                        .help("Sensor drift: open the calibration and read the spread")
                        .accessibilityLabel("Sensor drift, open calibration")
                    }
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

    @ViewBuilder private func ntp(_ m: NtpMetrics?) -> some View {
        if let m {
            HStack {
                if m.isWarning {
                    Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(.orange)
                }
                Text("\(m.ok ? "ok" : "failed")  failures \(m.failures)  last try \(n(m.ageSeconds)) s ago  next in \(n(m.nextSeconds)) s")
            }
        } else { Text("no sync attempt yet or no Wi-Fi credentials").foregroundStyle(.secondary) }
        Button("Sync clock now (Wi-Fi)") { model.syncClockOverWifi() }
            .disabled(!model.canSet || !model.isIdle || model.transport != .usb)
    }

    @ViewBuilder private func sdCard(_ s: StatusReply?) -> some View {
        if let s, let mb = s.sdFreeMb {
            HStack {
                if mb < Self.sdLowMb {
                    Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(.orange)
                }
                Text("SD free \(mb) MB")
            }
        } else { Text("SD free --").foregroundStyle(.secondary) }
    }

    /// Free card space below this value shows a warning.
    static let sdLowMb = 100

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

    @ViewBuilder private func memory(_ m: MemoryMetrics?) -> some View {
        if let m {
            Text("free \(m.free) B  min \(m.min) B  largest \(m.largest) B  PSRAM \(m.psram) B")
            if m.isLow {
                Label("Largest block below \(MemoryMetrics.lowLargestBytes) B", systemImage: "exclamationmark.triangle.fill")
                    .foregroundStyle(.orange)
            }
        } else { unknown }
    }

    @ViewBuilder private func frame(_ m: FrameMetrics?) -> some View {
        if let m {
            Text("frame CRC \(String(format: "%08x", m.crc))  skipped flushes \(m.skips)")
        } else { unknown }
    }

    @ViewBuilder private func wake(_ m: WakeMetrics?) -> some View {
        if let m {
            HStack {
                Text("cause \(m.cause)  boots \(m.boots)  battery interval \(m.batteryIntervalS) s")
                // `lastConfig` comes from the last config read, so it can lag behind a later config change.
                if m.isNightInterval(config: model.lastConfig) {
                    Text("Night interval").font(.caption.bold()).foregroundStyle(.white)
                        .padding(.horizontal, 6).background(Color.indigo, in: Capsule())
                }
            }
        } else { unknown }
    }

    @ViewBuilder private func latch(_ m: LatchMetrics?) -> some View {
        if let m {
            let names = Dictionary(model.status?.sensors.map { ($0.index, $0.name) } ?? [], uniquingKeysWith: { a, _ in a })
            let rows = (0..<LatchMetrics.sensorCount).compactMap { i -> (Int, [String])? in
                let b = m.badges(forSensor: i)
                return b.isEmpty ? nil : (i, b)
            }
            if rows.isEmpty {
                Text("No badges").foregroundStyle(.secondary)
            } else {
                ForEach(rows, id: \.0) { row in
                    Text("\(names[row.0] ?? "sensor \(row.0)"): \(row.1.joined(separator: ", "))")
                }
            }
        } else { unknown }
    }

    @ViewBuilder private func noise(_ m: NoiseMetrics?) -> some View {
        if let m {
            Text("level \(m.db.map { "\($0) dB" } ?? "--")  band \(m.band)")
            Text("today's \(m.peakText)  events \(m.eventsSum)")
        } else { unknown }
    }

    @ViewBuilder private func quiet(_ m: QuietMetrics?) -> some View {
        if let m {
            Text("\(m.windowText)  \(m.activeText)  \(m.nightIntervalText)")
        } else { unknown }
    }

    private var unknown: some View { Text("not reported by this firmware").foregroundStyle(.secondary) }
    private func n(_ v: Int?) -> String { v.map(String.init) ?? "--" }

    @ViewBuilder
    private func selfTest(_ t: SelfTestReply) -> some View {
        ForEach(t.checks) { c in
            HStack(spacing: 6) {
                Image(systemName: c.state == .ok ? "checkmark.circle.fill" : c.state == .fail ? "xmark.octagon.fill" : "minus.circle")
                    .foregroundStyle(c.state == .ok ? Color.green : c.state == .fail ? Color.red : Color.secondary)
                Text(c.name).bold()
                Text(c.state.rawValue)
                Text(c.detail).foregroundStyle(.secondary)
            }.font(.caption)
        }
    }
}

/// Missing hours between the rows of one `TAIL` reply. Reads only the rows of the reply.
private struct TailGapSummary: View {
    let report: LogGaps.Report

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            if report.gaps.isEmpty {
                Text("No gaps").font(.caption).foregroundStyle(.secondary)
            } else {
                Text("\(report.missingHours) missing h").font(.caption).foregroundStyle(.orange)
                ScrollView { GapLines(gaps: report.gaps).frame(maxWidth: .infinity, alignment: .leading) }
                    .frame(maxHeight: 120)
            }
        }
    }
}
