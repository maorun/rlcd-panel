import RlcdProtocol
import SwiftUI

/// Grouped form for the `config.ini` keys. Values come from the last `CONFIG` reply.
struct ConfigEditorView: View {
    @Bindable var model: AppModel

    var body: some View {
        GroupBox("Config editor") {
            VStack(alignment: .leading, spacing: 8) {
                if let c = model.config {
                    ForEach(ConfigCategory.allCases, id: \.self) { cat in
                        DisclosureGroup(cat.rawValue) {
                            ForEach(ConfigSchema.entries(in: cat), id: \.key) { entry in
                                row(entry, reply: c)
                            }
                        }
                    }
                    HStack {
                        Button("Review changes (\(model.changes.count))") { model.showDiff = true }
                            .disabled(model.changes.isEmpty)
                        Button("Discard") { model.draft = [:] }.disabled(model.draft.isEmpty)
                    }
                    if !model.canSet {
                        Text("SET needs ble_console=2 or the USB serial link.").font(.caption).foregroundStyle(.secondary)
                    }
                    if let s = model.batchSummary { Text(s).font(.caption).foregroundStyle(.orange) }
                } else {
                    Text("Read CONFIG first.").foregroundStyle(.secondary)
                }
            }.frame(maxWidth: .infinity, alignment: .leading)
        }
        .disabled(model.batch != nil) // a running batch must not lose edits
        .sheet(isPresented: $model.showDiff) { diffSheet }
    }

    private func binding(_ entry: ConfigEntry, _ reply: ConfigReply) -> Binding<String> {
        Binding(
            get: { model.draft[entry.key] ?? reply.values[entry.key] ?? entry.defaultValue },
            set: { new in
                if new == (reply.values[entry.key] ?? entry.defaultValue) { model.draft[entry.key] = nil } else { model.draft[entry.key] = new }
            })
    }

    private func row(_ entry: ConfigEntry, reply: ConfigReply) -> some View {
        let changed = model.changes.contains { $0.key == entry.key }
        let problem = model.problems[entry.key]
        return VStack(alignment: .leading, spacing: 2) {
            HStack {
                Image(systemName: changed ? "circle.fill" : "circle")
                    .foregroundStyle(changed ? Color.orange : Color.clear).font(.caption2)
                Text(entry.key).font(.system(.caption, design: .monospaced)).frame(width: 190, alignment: .leading)
                if case let .enumeration(list) = entry.kind {
                    Picker("", selection: binding(entry, reply)) { ForEach(list, id: \.self) { Text($0).tag($0) } }
                        .labelsHidden().frame(width: 90)
                } else {
                    TextField(entry.defaultValue.isEmpty ? "(empty)" : entry.defaultValue, text: binding(entry, reply))
                        .textFieldStyle(.roundedBorder).frame(width: 170)
                }
                Text(entry.unit).font(.caption).foregroundStyle(.secondary)
            }
            Text(problem ?? entry.description).font(.caption2).foregroundStyle(problem == nil ? Color.secondary : Color.red)
        }
    }

    private var diffSheet: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Changes to apply").font(.headline)
            ForEach(model.changes, id: \.key) { c in
                Text(c.text).font(.system(.body, design: .monospaced))
            }
            ForEach(model.changes.filter { model.problems[$0.key] != nil }, id: \.key) { c in
                Text("\(c.key): \(model.problems[c.key] ?? "")").foregroundStyle(.red)
            }
            if let w = model.bleConsoleWarning { Text(w).foregroundStyle(.orange) }
            HStack {
                Button("Cancel") { model.showDiff = false }
                Button("Apply") { model.showDiff = false; model.applyChanges() }
                    .disabled(!model.canApply).keyboardShortcut(.defaultAction)
            }
        }
        .padding().frame(minWidth: 420)
    }
}
