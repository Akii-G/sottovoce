import Foundation
import Observation
import UIKit

enum SyncError: LocalizedError {
    case notConfigured
    case badAddress
    case unauthorized
    case unreachable(String)
    case server(String)

    var errorDescription: String? {
        switch self {
        case .notConfigured:
            return tr("Add your PC's address and token in Settings first.")
        case .badAddress:
            return tr("The address doesn't look right. It should be like http://pop-os.local:8765")
        case .unauthorized:
            return tr("Wrong token. Copy it again from Sottovoce on your PC (Phone sync).")
        case .unreachable(let why):
            return tr("Can't reach your PC. Is it on, with Phone sync switched on, and is your iPhone on the same Wi-Fi? (%@)", why)
        case .server(let message):
            return message
        }
    }
}

enum SyncClient {
    /// The address from Settings. Accepts "pop-os.local:8765", a full URL, or the
    /// shortcut's address ending in /health.
    static func baseURL(_ pc: PCLink) throws -> URL {
        var raw = pc.cleanServer
        if raw.isEmpty || pc.cleanToken.isEmpty { throw SyncError.notConfigured }
        if !raw.lowercased().hasPrefix("http://") && !raw.lowercased().hasPrefix("https://") {
            raw = "http://" + raw
        }
        while raw.hasSuffix("/") { raw.removeLast() }
        if raw.lowercased().hasSuffix("/health") { raw.removeLast("/health".count) }
        guard let url = URL(string: raw), url.host != nil else { throw SyncError.badAddress }
        return url
    }

    /// Sends `payload` encrypted to `path` (e.g. "/api/e/sync") and returns the decrypted reply.
    /// The token is only used as the key: it is never sent.
    static func request(_ path: String, payload: Data, pc: PCLink) async throws -> Data {
        let token = pc.cleanToken
        let url = try baseURL(pc).appendingPathComponent(String(path.dropFirst()))
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.timeoutInterval = 20
        req.httpBody = try SyncCrypto.seal(payload, token: token, path: path)
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        let result: (Data, URLResponse)
        do {
            result = try await URLSession.shared.data(for: req)
        } catch let e as URLError where e.code == .cancelled {
            throw CancellationError()
        } catch {
            throw SyncError.unreachable(error.localizedDescription)
        }
        let data = result.0
        guard let http = result.1 as? HTTPURLResponse else { throw SyncError.server(tr("No answer from your PC.")) }
        if http.statusCode == 401 { throw SyncError.unauthorized }
        if http.statusCode == 404 {
            throw SyncError.server(tr("Your PC runs an older Sottovoce. Update it to 2.5 or newer: syncing is now encrypted."))
        }
        if !(200..<300).contains(http.statusCode) {
            if let doc = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
               let message = doc["error"] as? String {
                throw SyncError.server(message)
            }
            throw SyncError.server(tr("Your PC answered with error %@.", String(http.statusCode)))
        }
        do {
            return try SyncCrypto.open(data, token: token, path: path)
        } catch {
            throw SyncError.unauthorized
        }
    }

    /// Checks the address and token. Returns e.g. "Connected to Sottovoce 2.5.0".
    static func ping(_ pc: PCLink) async throws -> String {
        let data = try await request("/api/e/ping", payload: Data("{}".utf8), pc: pc)
        guard let doc = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let version = doc["version"] as? String else {
            throw SyncError.server(tr("Your PC sent something unexpected. Update Sottovoce on your PC to 2.5 or newer."))
        }
        return tr("Connected to Sottovoce %@ on your PC (encrypted)", version)
    }

    private struct Outgoing: Codable {
        var entries: [Entry]
        var events: [MoodEvent]
        var deleted: [Tombstone]
        var dreams: [Dream]
        var surveys: [Survey]   // an older PC ignores them
        var appointment: Appointment?
        var points: [TalkPoint]
        var device: String
        var corrections: Corrections
    }

    /// Sends everything on the phone, gets the merged state back and merges it here.
    @MainActor
    static func sync(_ store: AppStore, pc: PCLink) async throws -> Int {
        let state = store.state()
        let body = try JSONEncoder().encode(Outgoing(entries: state.entries, events: state.events,
                                                     deleted: state.deleted, dreams: state.dreams ?? [],
                                                     surveys: state.surveys ?? [], appointment: state.appointment,
                                                     points: state.points ?? [], device: UIDevice.current.name,
                                                     corrections: Corrections.current))
        let data = try await request("/api/e/sync", payload: body, pc: pc)
        let remote: SyncState
        do {
            remote = try JSONDecoder().decode(SyncState.self, from: data)
        } catch {
            throw SyncError.server(tr("Your PC sent something unexpected. Update Sottovoce on your PC to 2.5 or newer."))
        }
        var corrections = Corrections.current
        let fixed = remote.corrections.map { corrections.absorb($0) } ?? 0
        if fixed > 0 {
            Corrections.current = corrections
            store.correctionsChanged()
        }
        return store.absorb(remote) + fixed
    }

    /// Sends Health lines (the same format as the shortcut), encrypted.
    static func sendHealth(lines: [String], pc: PCLink) async throws -> String {
        let body = try JSONSerialization.data(withJSONObject: ["lines": lines.joined(separator: "\n")])
        let data = try await request("/api/e/health", payload: body, pc: pc)
        let doc = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        return ((doc?["message"] as? String) ?? tr("Sent.")).trimmingCharacters(in: .whitespacesAndNewlines)
    }
}

/// Runs syncs: when the app opens, a couple of seconds after a change, and on request.
@MainActor
@Observable
final class SyncCoordinator {
    var status = tr("Not synced yet")
    var busy = false
    var healthStatus = ""

    @ObservationIgnored private var pending: Task<Void, Never>?
    @ObservationIgnored private var again = false

    func syncSoon(_ store: AppStore, delay: Double) {
        guard UserDefaults.standard.bool(forKey: "autoSync"), !PCList.shared.configured.isEmpty else { return }
        pending?.cancel()
        pending = Task { [weak self] in
            if delay > 0 {
                try? await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))
            }
            if Task.isCancelled { return }
            // The sync runs in a task of its own: a newer syncSoon only cancels the wait,
            // never a sync that is already talking to the PC.
            Task { await self?.run(store) }
        }
    }

    /// Syncs with every PC in turn. When a later PC brings something new, the earlier
    /// ones get a second round, so every PC ends up with everything.
    func run(_ store: AppStore) async {
        if busy {           // one at a time; sync once more when this one ends
            again = true
            return
        }
        let list = PCList.shared
        let pcs = list.configured
        guard !pcs.isEmpty else {
            status = SyncError.notConfigured.localizedDescription
            return
        }
        busy = true
        var changes = 0
        var failed: [String: String] = [:]      // id → error
        var stopped = false
        rounds: for round in 0..<2 {
            var newFromLater = false
            for (i, pc) in pcs.enumerated() {
                if round == 1 && failed[pc.id] != nil { continue }
                do {
                    let n = try await SyncClient.sync(store, pc: pc)
                    changes += n
                    if n > 0 && i > 0 { newFromLater = true }
                    failed[pc.id] = nil
                    list.status[pc.id] = tr("Synced at %@", Day.timeString(Date()))
                } catch is CancellationError {
                    stopped = true      // stopped on purpose: keep the last status
                    break rounds
                } catch {
                    failed[pc.id] = error.localizedDescription
                    list.status[pc.id] = error.localizedDescription
                }
            }
            if !newFromLater || pcs.count < 2 { break }
        }
        if !stopped {
            let when = Day.timeString(Date())
            if failed.count == pcs.count {
                status = pcs.count == 1 ? failed[pcs[0].id]! : tr("Couldn't sync with any PC. Tap one in Settings to see why.")
            } else {
                var text = changes > 0 ? trn("Synced at %2$@ · %1$@ change", "Synced at %2$@ · %1$@ changes", changes, when)
                    : tr("Synced at %@", when)
                for pc in pcs where failed[pc.id] != nil {
                    text += "\n" + tr("%@: not reached", pc.title)
                }
                status = text
                UserDefaults.standard.set(Date(), forKey: "lastSync")
                if UserDefaults.standard.bool(forKey: "sendHealth") {
                    let due = list.configured.filter {
                        failed[$0.id] == nil && Date().timeIntervalSince($0.lastHealth ?? .distantPast) > 3600
                    }
                    if !due.isEmpty { await sendHealth(to: due) }
                }
            }
        }
        busy = false
        if again {
            again = false
            await run(store)
        }
    }

    /// Sends the last 7 days of Health to every PC (or to the ones given).
    func sendHealth(to only: [PCLink]? = nil) async {
        guard HealthSender.isAvailable else {
            healthStatus = tr("Apple Health isn't available on this device.")
            return
        }
        let pcs = only ?? PCList.shared.configured
        guard !pcs.isEmpty else {
            healthStatus = SyncError.notConfigured.localizedDescription
            return
        }
        // a PC that never had Health gets the last year, so its charts and report are complete
        var health: [Int: [String]] = [:]
        var answers: [String] = []
        for pc in pcs {
            let days = pc.yearSent == true ? 7 : 365
            if health[days] == nil {
                do {
                    health[days] = try await HealthSender.lines(days: days)
                } catch {
                    healthStatus = error.localizedDescription
                    return
                }
            }
            let meds = await Medications.lines(all: !pc.medicationsSentAll)
            let lines = (health[days] ?? []) + meds.lines
            if lines.isEmpty {
                healthStatus = tr("No Health data found for the last 7 days (or access wasn't allowed in Settings → Health → Data Access).")
                return
            }
            do {
                let answer = try await SyncClient.sendHealth(lines: lines, pc: pc)
                PCList.shared.update(pc.id) {
                    $0.lastHealth = Date()
                    $0.yearSent = true
                    if meds.all { $0.medicationsSentAll = true }
                }
                answers.append(pcs.count == 1 ? answer : pc.title + ": " + answer)
            } catch {
                answers.append(pcs.count == 1 ? error.localizedDescription : pc.title + ": " + error.localizedDescription)
            }
        }
        healthStatus = answers.joined(separator: "\n")
    }
}
