import RlcdProtocol
import SwiftUI

/// Hardware diagnostics from the `STATUS` and `VERSION` replies. A red mark shows a sensor or reset fault.
struct DiagnosticsView: View {
    let model: AppModel

    var body: some View {
        GroupBox("Hardware diagnostics") {
            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    Button("Refresh STATUS and VERSION") { model.refreshDiagnostics() }
                        .disabled(!model.consoleConnected)
                    Spacer()
                }
                if let v = model.version {
                    Text("Firmware \(v.firmware) (git \(v.git), core \(v.core), state \(v.state), built \(v.built))")
                        .font(.caption).foregroundStyle(.secondary)
                }
                if let d = model.diagnostics {
                    group("SHTC3 sensor") { shtc3(d.shtc3) }
                    group("BLE rejected packets") { ble(d.ble) }
                    group("Resets") { reset(d.reset) }
                    group("Battery runtime") { runtime(d.runtime) }
                    group("Awake time") { awake(d.awake) }
                } else {
                    Text("No diagnostics yet. Press the refresh button.").font(.caption).foregroundStyle(.secondary)
                }
            }.frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private func group<C: View>(_ title: String, @ViewBuilder _ content: () -> C) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title).font(.subheadline.bold())
            content().font(.system(.caption, design: .monospaced))
        }
    }

    @ViewBuilder private func shtc3(_ m: Shtc3Stats?) -> some View {
        if let m {
            Text("nack \(n(m.nack))  short \(n(m.short))  crc \(n(m.crc))")
            Text("failures in a row: \(n(m.run))")
                .foregroundStyle(m.isAlert ? .white : .primary)
                .padding(.horizontal, 4)
                .background(m.isAlert ? Color.red : Color.clear, in: RoundedRectangle(cornerRadius: 4))
        } else { unknown }
    }

    @ViewBuilder private func ble(_ m: BleRejectMetrics?) -> some View {
        if let m {
            Text("rejected \(n(m.rejected))  last \(m.lastMac ?? "--")")
            Text("woio \(n(m.woio))  meter \(n(m.meter))  bthome \(n(m.bthome))  atc \(n(m.atc))")
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
