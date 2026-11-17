import Foundation

public enum LogArchiveError: Error, Equatable {
    case invalidName(String)
}

/// One row of the `LIST` reply: a log file on the MicroSD card.
public struct LogFile: Equatable, Identifiable, Sendable {
    public let name: String
    public let bytes: Int
    public var id: String { name }

    public init(name: String, bytes: Int) {
        self.name = name
        self.bytes = bytes
    }
}

/// The `LIST` reply: `FILE <name> <bytes>` rows and the closing `END LIST <count>` line.
public enum ListReply {
    /// Rejects a row with a name outside the firmware patterns, a non-numeric size, a missing
    /// `END LIST` line, a count that differs from the rows and any line after `END LIST`.
    public static func parse(lines: [String]) throws -> [LogFile] {
        guard let last = lines.last else { throw ParseError.missingLine("END LIST") }
        guard last.hasPrefix("END LIST ") else { throw ParseError.missingLine("END LIST") }
        guard let count = decimal(String(last.dropFirst(9))) else { throw ParseError.badLine(last) }
        var files: [LogFile] = []
        for line in lines.dropLast() {
            let parts = line.split(separator: " ", omittingEmptySubsequences: false)
            guard parts.count == 3, parts[0] == "FILE", LogArchive.isValidName(String(parts[1])),
                  let bytes = decimal(String(parts[2])), !files.contains(where: { $0.name == String(parts[1]) }) else {
                throw ParseError.badLine(line) // also a repeated name: the ids and the downloads must stay unique
            }
            files.append(LogFile(name: String(parts[1]), bytes: bytes))
        }
        guard files.count == count else { throw ParseError.badLine(last) }
        return files
    }

    private static func decimal(_ text: String) -> Int? {
        guard !text.isEmpty, text.utf8.count <= 9, text.utf8.allSatisfy({ $0 >= 0x30 && $0 <= 0x39 }) else { return nil }
        return Int(text)
    }
}

/// Names, download plan and safe storage of the SD card log archive on the Mac.
public enum LogArchive {
    enum Period: Equatable {
        case month(year: Int, month: Int)
        case year(Int)
    }

    /// Accepts only the firmware file name patterns: `climate_v2|v3|v4|v5_YYYYMM.csv`,
    /// `daily_v1|v2|v3|v4_YYYY.csv` and `events_v1_YYYY.csv`. A path, a space or a line end is never valid.
    public static func isValidName(_ name: String) -> Bool { period(of: name) != nil }

    static func period(of name: String) -> Period? {
        guard name.utf8.allSatisfy({ $0 < 0x80 }), name.hasSuffix(".csv") else { return nil }
        let parts = name.dropLast(4).split(separator: "_", omittingEmptySubsequences: false).map(String.init)
        guard parts.count == 3 else { return nil }
        let digits: (String, Int) -> Int? = { text, count in
            guard text.utf8.count == count, text.utf8.allSatisfy({ $0 >= 0x30 && $0 <= 0x39 }) else { return nil }
            return Int(text)
        }
        switch (parts[0], parts[1]) {
        case ("climate", "v2"), ("climate", "v3"), ("climate", "v4"), ("climate", "v5"), ("climate", "v6"):
            guard let stamp = digits(parts[2], 6), (2000...2099).contains(stamp / 100), (1...12).contains(stamp % 100) else { return nil }
            return .month(year: stamp / 100, month: stamp % 100)
        case ("daily", "v1"), ("daily", "v2"), ("daily", "v3"), ("daily", "v4"), ("daily", "v5"), ("events", "v1"):
            guard let year = digits(parts[2], 4), (2000...2099).contains(year) else { return nil }
            return .year(year)
        default:
            return nil
        }
    }

    /// True when the assembler takes the file. A larger file would leave unread bytes in the stream.
    public static func isDownloadable(_ file: LogFile) -> Bool { file.bytes <= ReplyAssembler.maxFileBytes }

    /// The files to download, in the order of the card. A file is skipped when the local copy has
    /// the same name and size, except the file of the current month (or the current year), which
    /// still grows and may change without a size change.
    public static func plan(remote: [LogFile], localSizes: [String: Int], year: Int, month: Int) -> [LogFile] {
        remote.filter { file in
            guard let period = period(of: file.name), isDownloadable(file) else { return false }
            let isCurrent: Bool
            switch period {
            case let .month(y, m): isCurrent = y == year && m == month
            case let .year(y): isCurrent = y == year
            }
            return isCurrent || localSizes[file.name] != file.bytes
        }
    }

    /// Sizes of the archive files in a folder. Other files and temporary files are ignored.
    public static func localSizes(in folder: URL) -> [String: Int] {
        var sizes: [String: Int] = [:]
        let names = (try? FileManager.default.contentsOfDirectory(atPath: folder.path)) ?? []
        for name in names where isValidName(name) {
            let attrs = try? FileManager.default.attributesOfItem(atPath: folder.appendingPathComponent(name).path)
            if let size = attrs?[.size] as? NSNumber { sizes[name] = size.intValue }
        }
        return sizes
    }

    /// Writes the bytes to a temporary name in the folder and renames it to the final name. The
    /// caller calls this only after the CRC-32 check, so a failed download leaves no final file.
    public static func store(_ data: Data, name: String, in folder: URL) throws {
        guard isValidName(name) else { throw LogArchiveError.invalidName(name) }
        let target = folder.appendingPathComponent(name)
        let temp = folder.appendingPathComponent(".\(name).\(UUID().uuidString).part")
        do {
            try data.write(to: temp)
            if FileManager.default.fileExists(atPath: target.path) {
                _ = try FileManager.default.replaceItemAt(target, withItemAt: temp)
            } else {
                try FileManager.default.moveItem(at: temp, to: target)
            }
        } catch {
            try? FileManager.default.removeItem(at: temp)
            throw error
        }
    }
}
