import SwiftUI

/// One PC from Settings: its name, address and token, a connection test, and Remove.
struct PCEditView: View {
    let id: String
    @Environment(\.dismiss) private var dismiss
    @Environment(AppStore.self) private var store
    @Environment(SyncCoordinator.self) private var sync
    @State private var pcs = PCList.shared
    @State private var testResult = ""
    @State private var testing = false
    @State private var confirmRemove = false
    @State private var sending = false

    private func field(_ key: WritableKeyPath<PCLink, String>) -> Binding<String> {
        Binding(get: { pcs.items.first { $0.id == id }?[keyPath: key] ?? "" },
                set: { value in pcs.update(id) { $0[keyPath: key] = value } })
    }

    private var pc: PCLink? { pcs.items.first { $0.id == id } }

    var body: some View {
        Form {
            Section {
                TextField("Name, e.g. Mac or Living room PC", text: field(\.name))
            } footer: {
                Text("Only to tell your PCs apart. Leave it empty to use the computer's name.")
            }
            Section {
                TextField("Address, e.g. http://pop-os.local:8765", text: field(\.server))
                    .keyboardType(.URL)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                SecureField("Token", text: field(\.token))
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                Button {
                    guard let pc else { return }
                    testing = true
                    Task {
                        do {
                            testResult = try await SyncClient.ping(pc)
                        } catch {
                            testResult = error.localizedDescription
                        }
                        testing = false
                    }
                } label: {
                    HStack {
                        Text("Test connection")
                        if testing { Spacer(); ProgressView() }
                    }
                }
                .disabled(!(pc?.isConfigured ?? false) || testing)
                if !testResult.isEmpty {
                    Text(testResult).font(.footnote).foregroundStyle(.secondary)
                }
            } footer: {
                Text("In Sottovoce on this PC, open Phone sync and switch the receiver on. Use the address and token shown there.")
            }
            Section {
                Button {
                    guard let pc else { return }
                    pcs.update(id) { $0.yearSent = nil }     // the whole last year, not only the last week
                    sending = true
                    Task {
                        await sync.sendHealth(to: [pc])
                        sending = false
                    }
                } label: {
                    HStack {
                        Text("Send the last year of Health")
                        if sending { Spacer(); ProgressView() }
                    }
                }
                .disabled(!(pc?.isConfigured ?? false) || sending)
                if !sync.healthStatus.isEmpty && !sending {
                    Text(sync.healthStatus).font(.footnote).foregroundStyle(.secondary)
                }
            } footer: {
                Text("Sleep, steps and resting heart rate from the last 12 months, for a PC that's new or missing older nights.")
            }
            Section {
                Button("Remove this PC", role: .destructive) { confirmRemove = true }
            } footer: {
                Text("Only the connection is removed: the diary stays on the iPhone and on the PC.")
            }
        }
        .navigationTitle(pc?.title ?? tr("PC"))
        .navigationBarTitleDisplayMode(.inline)
        .confirmationDialog(Text("Remove this PC?"), isPresented: $confirmRemove, titleVisibility: .visible) {
            Button("Remove", role: .destructive) {
                pcs.remove(id)
                dismiss()
            }
        }
        .onDisappear {
            pcs.dropEmpty()
            if pc?.isConfigured == true { sync.syncSoon(store, delay: 0.5) }
        }
    }
}
