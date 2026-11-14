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
    /// A one-line reply: `OK ...` or `ERR ...`.
    case status(String)
    /// A `CRC32` line did not match, or was malformed.
    case corrupt(ReplyError)
}

/// Reassembles console replies from chunks (BLE notifications of 20 bytes or USB serial reads).
///
/// A reply ends at its `CRC32 <8 hex digits>` line. A first line that starts with `OK ` or `ERR `
/// is a complete reply on its own. One assembler follows one connection and one command at a time.
public struct ReplyAssembler {
    /// Longest line without LF and most lines in one reply. The firmware replies stay far below both.
    public static let maxLineBytes = 4096
    public static let maxLines = 512

    private var pending = Data()
    private var frameBytes = Data()
    private var lines: [String] = []

    public init() {}

    /// Drops a half-received reply, for example after a new connection.
    public mutating func reset() {
        pending.removeAll()
        frameBytes.removeAll()
        lines.removeAll()
    }

    /// Feeds one chunk and returns the replies that it completed.
    public mutating func append(_ chunk: Data) -> [ConsoleReply] {
        pending.append(chunk)
        var out: [ConsoleReply] = []
        while let lf = pending.firstIndex(of: 0x0A) {
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

    private mutating func consume(line: String, raw: Data) -> ConsoleReply? {
        if lines.isEmpty && (line.hasPrefix("OK ") || line.hasPrefix("ERR ")) {
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
}
