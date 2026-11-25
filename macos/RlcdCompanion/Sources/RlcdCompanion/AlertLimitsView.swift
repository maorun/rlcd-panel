import RlcdProtocol
import SwiftUI

/// Shows the alert limits of the last `CONFIG` reply and sends the changed ones as `SET` commands, then `CONFIG`.
struct AlertLimitsView: View {
    @Bindable var model: AppModel

    var body: some View {
        GroupBox("Alert limits") {
            VStack(alignment: .leading, spacing: 6) {
                if model.config == nil {
                    Text("Read CONFIG to see the panel values. Defaults are shown until then.")
                        .font(.caption).foregroundStyle(.secondary)
                }
                ForEach(AlertLimits.limits, id: \.key) { limit in row(limit) }
                HStack {
                    let plan = model.alertLimitsPlan
                    let commands: [ConsoleCommand] = { if case .commands(let c) = plan { return c } else { return [] } }()
                    Button("Apply (\(commands.count))") { model.applyAlertLimits() }
                        .disabled(!model.alertLimitsCanEdit || !model.isIdle || commands.isEmpty || !model.draft.isEmpty)
                    Button("Discard") { model.alertLimitEdits = [:] }
                        .disabled(model.alertLimitEdits.isEmpty)
                    if case .invalid(let reason) = plan {
                        Text(reason).font(.caption).foregroundStyle(.red)
                    }
                    if !model.alertLimitsCanEdit {
                        Text("SET needs ble_console=2 or the USB serial link.")
                            .font(.caption).foregroundStyle(.secondary)
                    }
                }
                if let s = model.batchSummary { Text(s).font(.caption).foregroundStyle(.secondary) }
            }.frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private func row(_ limit: AlertLimits.Limit) -> some View {
        let text = Binding(
            get: { model.alertLimitEdits[limit.key] ?? AlertLimits.shown(limit, config: model.config) },
            set: { model.alertLimitEdits = AlertLimits.updated(edits: model.alertLimitEdits, limit: limit, text: $0, config: model.config) })
        let error = AlertLimits.error(limit, text.wrappedValue)
        return HStack(alignment: .firstTextBaseline) {
            VStack(alignment: .leading) {
                Text(limit.label)
                Text("\(limit.key) — \(limit.rangeText)").font(.caption2).foregroundStyle(.secondary)
            }
            Spacer()
            if model.alertLimitsCanEdit {
                TextField(limit.unit, text: text).frame(width: 70).multilineTextAlignment(.trailing)
                    .disabled(!model.isIdle)
            } else {
                Text("\(AlertLimits.shown(limit, config: model.config)) \(limit.unit)")
            }
            if error != nil { Image(systemName: "exclamationmark.triangle").foregroundStyle(.red).help(error ?? "") }
        }
    }
}
