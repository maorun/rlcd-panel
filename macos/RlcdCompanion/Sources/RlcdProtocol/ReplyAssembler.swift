import Foundation

public enum ReplyError: Error, Equatable {
    /// A line or a reply is longer than the limits of `ReplyAssembler`.
    case tooLarge
    case badCrc(expected: UInt32, actual: UInt32)
    case malformedCrcLine(String)
}

/// A complete console reply.
public enum ConsoleReply: Equatable {
    /// Lines before the `CRC32` line, CRC-32 verified. Each line has no LF.
    case frame(lines: [String])
    /// The raw bytes of a `DUMP` reply, CRC-32 verified.
    case file(name: String, bytes: Data)
    /// A one-line reply: `OK ...` or `ERR ...`.
    case status(String)
    /// A `CRC32` line did not match, or was malformed.
    case corrupt(ReplyError)
}

/// Reassembles console replies from chunks (BLE notifications of 20 bytes or USB serial reads).
///
/// A reply ends at its `CRC32 <8 hex digits>` line. A `LIST` reply ends at its `END LIST <count>` line.
/// After a `BEGIN <name> <bytes>` line with a log file name the assembler takes exactly that many raw
/// bytes without line splitting (they may hold LF and `CRC32 ` text), then expects the `CRC32` line
/// over those bytes only, as `scripts/dump-logs.sh` does. An `ERR` line after the bytes ends the reply.
/// A first line that starts with `OK ` or `ERR ` is a complete reply on its own. One assembler follows one connection and one command at a time.
public struct ReplyAssembler {
    /// Longest line without LF and most lines in one reply. The firmware replies stay far below both.
    public static let maxLineBytes = 4096
    public static let maxLines = 512
    /// Largest log file that the assembler takes (the limit of `scripts/dump-logs.sh`).
    public static let maxFileBytes = 16 * 1024 * 1024

    private var pending = Data()
    private var frameBytes = Data()
    private var lines: [String] = []
    /// Set between a `BEGIN <name> <bytes>` line and the end of the reply.
    private var binary: (name: String, size: Int)?
    private var body = Data()

    public init() {}

    /// Drops a half-received reply, for example after a new connection.
    public mutating func reset() {
        pending.removeAll()
        frameBytes.removeAll()
        lines.removeAll()
        binary = nil
        body.removeAll()
    }

    /// Feeds one chunk and returns the replies that it completed.
    public mutating func append(_ chunk: Data) -> [ConsoleReply] {
        pending.append(chunk)
        var out: [ConsoleReply] = []
        while true {
            if let b = binary, body.count < b.size {
                let take = min(pending.count, b.size - body.count)
                guard take > 0 else { break }
                body.append(pending.prefix(take))
                pending = Data(pending.dropFirst(take))
                continue
            }
            guard let lf = pending.firstIndex(of: 0x0A) else { break }
            let lineData = pending[pending.startIndex..<lf]
            pending = Data(pending[pending.index(after: lf)...])
            var text = String(decoding: lineData, as: UTF8.self)
            if text.hasSuffix("\r") { text.removeLast() }
            if let reply = consume(line: text, raw: Data(lineData) + Data([0x0A])) {
                out.append(reply)
            }
        }
        if pending.count > Self.maxLineBytes || lines.count > Self.maxLines {
            reset()
            out.append(.corrupt(.tooLarge))
        }
        return out
    }

    /// `BEGIN <name> <bytes>` with a log file name and a decimal size, otherwise `nil`.
    private static func dumpHeader(_ line: String) -> (name: String, size: Int)? {
        let parts = line.split(separator: " ", omittingEmptySubsequences: false)
        guard parts.count == 3, parts[0] == "BEGIN", LogArchive.isValidName(String(parts[1])),
              parts[2].utf8.count <= 9, !parts[2].isEmpty,
              parts[2].utf8.allSatisfy({ $0 >= 0x30 && $0 <= 0x39 }), let size = Int(parts[2]) else { return nil }
        return (String(parts[1]), size)
    }

    private mutating func consume(line: String, raw: Data) -> ConsoleReply? {
        if let b = binary { return finishFile(b, line: line) }
        if lines.isEmpty, let header = Self.dumpHeader(line) {
            guard header.size <= Self.maxFileBytes else {
                reset()
                return .corrupt(.tooLarge)
            }
            binary = header
            body.reserveCapacity(header.size)
            return nil
        }
        if line.hasPrefix("END LIST ") {
            let collected = lines + [line]
            lines.removeAll()
            frameBytes.removeAll()
            return .frame(lines: collected)
        }
        if lines.isEmpty && (line.hasPrefix("OK ") || line.hasPrefix("ERR ") || line.hasPrefix("TIME ") || line == "TIME") {
            return .status(line)
        }
        if line.hasPrefix("CRC32 ") {
            let hex = String(line.dropFirst(6))
            let collected = lines
            let bytes = frameBytes
            lines.removeAll()
            frameBytes.removeAll()
            guard hex.count == 8, let actual = UInt32(hex, radix: 16) else {
                return .corrupt(.malformedCrcLine(line))
            }
            let expected = Checksums.crc32(bytes)
            guard expected == actual else { return .corrupt(.badCrc(expected: expected, actual: actual)) }
            return .frame(lines: collected)
        }
        lines.append(line)
        frameBytes.append(raw)
        return nil
    }

    /// The line after the raw bytes: the `CRC32` line over the bytes, or an `ERR` line.
    private mutating func finishFile(_ b: (name: String, size: Int), line: String) -> ConsoleReply {
        let bytes = body
        binary = nil
        body = Data()
        if line.hasPrefix("ERR ") { return .status(line) }
        guard line.hasPrefix("CRC32 ") else { return .corrupt(.malformedCrcLine(line)) }
        let hex = String(line.dropFirst(6))
        guard hex.count == 8, let actual = UInt32(hex, radix: 16) else { return .corrupt(.malformedCrcLine(line)) }
        let expected = Checksums.crc32(bytes)
        guard expected == actual else { return .corrupt(.badCrc(expected: expected, actual: actual)) }
        return .file(name: b.name, bytes: bytes)
    }
}
