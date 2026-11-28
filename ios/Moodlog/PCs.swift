import Foundation
import Observation

/// One PC the phone syncs with. The phone holds the whole diary, so it passes entries
/// between PCs: something written on one PC reaches the others through the phone.
struct PCLink: Codable, Identifiable, Hashable {
    var id = UUID().uuidString
    var name = ""
    var server = ""
    var token = ""
    var lastHealth: Date?           // Health is sent to each PC at most hourly
    var medicationsSentAll = false  // this PC already has the whole medication history
    var yearSent: Bool?             // this PC already has the last year of Health

    var cleanServer: String { server.trimmingCharacters(in: .whitespacesAndNewlines) }
    var cleanToken: String { token.trimmingCharacters(in: .whitespacesAndNewlines) }
    var isConfigured: Bool { !cleanServer.isEmpty && !cleanToken.isEmpty }

    /// The name, or the computer's name from the address ("pop-os" for http://pop-os.local:8765).
    var title: String {
        let n = name.trimmingCharacters(in: .whitespacesAndNewlines)
        if !n.isEmpty { return n }
        var raw = cleanServer
        if !raw.contains("://") { raw = "http://" + raw }
        if let host = URL(string: raw)?.host, !host.isEmpty {
            if host.first?.isNumber == true { return host }    // an IP address stays whole
            return String(host.split(separator: ".").first ?? Substring(host))
        }
        return tr("New PC")
    }
}

/// The PCs in Settings, kept in UserDefaults ("pcs"). The single PC of older versions
/// ("server" and "token") becomes the first one.
@MainActor
@Observable
final class PCList {
    static let shared = PCList()

    var items: [PCLink] = [] {
        didSet { save() }
    }

    /// The last sync result for each PC, shown under its name in Settings.
    var status: [String: String] = [:]

    var configured: [PCLink] { items.filter(\.isConfigured) }

    private init() {
        let defaults = UserDefaults.standard
        if let data = defaults.data(forKey: "pcs"), let list = try? JSONDecoder().decode([PCLink].self, from: data) {
            items = list
        } else {
            let server = defaults.string(forKey: "server") ?? ""
            let token = defaults.string(forKey: "token") ?? ""
            if !server.isEmpty || !token.isEmpty {
                items = [PCLink(server: server, token: token,
                                lastHealth: defaults.object(forKey: "lastHealth") as? Date,
                                medicationsSentAll: defaults.bool(forKey: "medicationsSentAll"))]
            }
            save()
        }
    }

    private func save() {
        if let data = try? JSONEncoder().encode(items) {
            UserDefaults.standard.set(data, forKey: "pcs")
        }
    }

    func update(_ id: String, _ change: (inout PCLink) -> Void) {
        guard let i = items.firstIndex(where: { $0.id == id }) else { return }
        change(&items[i])
    }

    func remove(_ id: String) {
        items.removeAll { $0.id == id }
        status[id] = nil
    }

    /// Every PC gets the whole medication history again (new names, new choices).
    func resendMedications() {
        for i in items.indices { items[i].medicationsSentAll = false }
    }

    /// Drops PCs left with no address and no token (an "Add a PC" that wasn't filled in).
    func dropEmpty() {
        items.removeAll { $0.cleanServer.isEmpty && $0.cleanToken.isEmpty }
    }
}
