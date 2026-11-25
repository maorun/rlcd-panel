import RlcdProtocol
import SwiftUI

/// Grouped form for the `config.ini` keys. Values come from the last `CONFIG` reply.
struct ConfigEditorView: View {
    @Bindable var model: AppModel

    var body: some View {
        GroupBox("Config editor") {
            VStack(alignment: .leading, spacing: 8) {
                if let c = model.config {
                    changedFilter
                    ForEach(ConfigCategory.allCases, id: \.self) { cat in
                        let shown = ConfigSchema.entries(in: cat).filter { !model.changedOnly || changedKeys.contains($0.key) }
                        if !shown.isEmpty {
                            DisclosureGroup(cat.rawValue) {
                                ForEach(shown, id: \.key) { entry in
                                    row(entry, reply: c)
                                }
                            }
                        }
                    }
                    HStack {
                        Button("Review changes (\(model.changes.count))") { model.showDiff = true }
                            .disabled(model.changes.isEmpty)
                        Button("Discard") { model.draft = [:] }.disabled(model.draft.isEmpty)
                        Button("Save backup…") { model.saveBackup() }
                        Button("Restore backup…") { model.loadBackup() }
                    }
                    if !model.canSet {
                        Text("SET needs ble_console=2 or the USB serial link.").font(.caption).foregroundStyle(.secondary)
                    }
                    ForEach(ConfigSchema.unboundNames(config: c), id: \.self) { n in
                        Text(ConfigSchema.unboundNameWarning(slot: n)).font(.caption).foregroundStyle(.orange)
                    }
                    if let s = model.batchSummary { Text(s).font(.caption).foregroundStyle(.orange) }
                } else {
                    Text("Read CONFIG first.").foregroundStyle(.secondary)
                }
            }.frame(maxWidth: .infinity, alignment: .leading)
        }
        .disabled(model.batch != nil) // a running batch must not lose edits
        .sheet(isPresented: $model.showDiff) { diffSheet }
        .sheet(isPresented: $model.showRestore) { restoreSheet }
        .onChange(of: model.showRestore) { _, shown in if !shown { model.restore = nil; model.restoreFileName = nil } }
    }

    private var changedKeys: Set<String> { Set((model.configChanged ?? []).map(\.key)) }

    /// `Changed only` filter and the read of `CONFIG CHANGED`. Each changed key gets a `Reset` button in its row.
    @ViewBuilder private var changedFilter: some View {
        HStack {
            Toggle("Changed only", isOn: $model.changedOnly)
            Button("Read changed") { model.readConfigChanged() }.disabled(!model.isIdle)
        }
        if model.changedOnly {
            if let changed = model.configChanged {
                if changed.isEmpty { Text("No setting differs from the default.").font(.caption).foregroundStyle(.secondary) }
            } else {
                Text("Read changed to list the settings that differ from the default.").font(.caption).foregroundStyle(.secondary)
            }
        }
        if model.consoleLevel == 1 {
            Text("Reset is off for ble_console=1.").font(.caption).foregroundStyle(.secondary)
        }
        if !model.draft.isEmpty, model.configChanged?.isEmpty == false {
            Text("Reset is off while the form holds unsaved edits. Apply or discard them first.").font(.caption).foregroundStyle(.secondary)
        }
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
                if let item = model.configChanged?.first(where: { $0.key == entry.key }) {
                    Button("Reset") { model.resetToDefault(item) }
                        .disabled(!model.canReset(item))
                        .help("SET \(entry.key)=\(entry.defaultValue.isEmpty ? "(empty)" : entry.defaultValue)")
                    if ConfigSchema.resetChanges(for: item).isEmpty {
                        Text("No reset: the default cannot be sent").font(.caption2).foregroundStyle(.secondary)
                    }
                }
            }
            Text(problem ?? entry.description).font(.caption2).foregroundStyle(problem == nil ? Color.secondary : Color.red)
            if entry.key == "night_wake_interval_s" { runtimePreview(reply) }
        }
    }

    /// `About <N> h on battery` below `night_wake_interval_s`. Needs the last `STATUS` with `RUNTIME`, `AWAKE` and `QUIET`.
    @ViewBuilder private func runtimePreview(_ reply: ConfigReply) -> some View {
        // The current schedule is the one that `RUNTIME h` was measured with: the `CONFIG` values of the `STATUS` read.
        if let d = model.diagnostics, let measured = model.diagnosticsConfig {
            let hours = RuntimePreview.hours(config: measured, draft: model.draft.merging(reply.values) { mine, _ in mine },
                                             runtime: d.runtime, awake: d.awake, quiet: d.quiet)
            Text(RuntimePreview.text(hours)).font(.caption).foregroundStyle(.secondary)
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

    private var restoreSheet: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Restore \(model.restoreFileName ?? "backup")").font(.headline)
            if let rejected = model.restore?.rejected, !rejected.isEmpty {
                Text("Rejected lines (fix the file to restore):").foregroundStyle(.red)
                ForEach(rejected, id: \.line) { r in
                    Text(r.text).font(.system(.caption, design: .monospaced)).foregroundStyle(.red)
                }
            }
            if model.restoreChanges.isEmpty {
                Text("The backup equals the current values.").foregroundStyle(.secondary)
            } else {
                Text("Changes to apply").font(.subheadline)
                ForEach(model.restoreChanges, id: \.key) { c in
                    Text(c.text).font(.system(.body, design: .monospaced))
                }
            }
            ForEach(model.restoreChanges.filter { model.restoreProblems[$0.key] != nil }, id: \.key) { c in
                Text("\(c.key): \(model.restoreProblems[c.key] ?? "")").foregroundStyle(.red)
            }
            if let w = model.restoreWarning { Text(w).foregroundStyle(.orange) }
            if !model.canSet {
                Text("SET needs ble_console=2 or the USB serial link.").font(.caption).foregroundStyle(.secondary)
            }
            HStack {
                Button("Cancel") { model.closeRestore() }
                Button("Restore") { model.applyRestore() }
                    .disabled(!model.canRestore).keyboardShortcut(.defaultAction)
            }
        }
        .padding().frame(minWidth: 420)
    }
}
