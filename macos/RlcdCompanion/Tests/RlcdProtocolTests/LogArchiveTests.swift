import Foundation
import Testing
@testable import RlcdProtocol

@Suite struct LogArchiveTests {
    static let listLines = [
        "FILE climate_v4_202611.csv 1200",
        "FILE daily_v2_2026.csv 300",
        "FILE events_v1_2026.csv 0",
        "END LIST 3",
    ]

    /// A body with LF bytes and text that looks like a `CRC32` line.
    static let body = Data("a,b\nCRC32 00000000\n\u{0}\u{1}\r\nlast".utf8)

    static func dumpReply(name: String = "climate_v4_202611.csv", body: Data = body, size: Int? = nil, crc: UInt32? = nil) -> Data {
        var out = Data("BEGIN \(name) \(size ?? body.count)\n".utf8)
        out.append(body)
        out.append(Data(String(format: "CRC32 %08x\n", crc ?? Checksums.crc32(body)).utf8))
        return out
    }

    // MARK: LIST

    @Test func parsesRecordedListReply() throws {
        let files = try ListReply.parse(lines: Self.listLines)
        #expect(files == [
            LogFile(name: "climate_v4_202611.csv", bytes: 1200),
            LogFile(name: "daily_v2_2026.csv", bytes: 300),
            LogFile(name: "events_v1_2026.csv", bytes: 0),
        ])
        #expect(try ListReply.parse(lines: ["END LIST 0"]).isEmpty)
    }

    @Test func rejectsWrongEndCountAndBadRows() {
        #expect(throws: ParseError.self) { try ListReply.parse(lines: Array(Self.listLines.dropLast()) + ["END LIST 2"]) }
        #expect(throws: ParseError.self) { try ListReply.parse(lines: Array(Self.listLines.dropLast())) }
        #expect(throws: ParseError.self) { try ListReply.parse(lines: ["FILE ../x.csv 5", "END LIST 1"]) }
        #expect(throws: ParseError.self) { try ListReply.parse(lines: ["FILE climate_v4_202611.csv x", "END LIST 1"]) }
        #expect(throws: ParseError.self) { try ListReply.parse(lines: ["FILE other.csv 5", "END LIST 1"]) }
    }

    @Test func rejectsMissingEndLineAndLinesAfterIt() {
        #expect(throws: ParseError.self) { try ListReply.parse(lines: []) }
        #expect(throws: ParseError.self) { try ListReply.parse(lines: ["FILE daily_v2_2026.csv 5"]) }
        #expect(throws: ParseError.self) { try ListReply.parse(lines: ["END LIST 1", "FILE daily_v2_2026.csv 5"]) }
        #expect(throws: ParseError.self) { try ListReply.parse(lines: ["END LIST x"]) }
    }

    @Test func listReplyEndsAtEndLineWithoutCrc() {
        var a = ReplyAssembler()
        let out = a.append(Data((Self.listLines.joined(separator: "\n") + "\n").utf8))
        #expect(out == [.frame(lines: Self.listLines)])
        #expect(ReplyAssembler().appendingOne(Data("END LIST 0\n".utf8)) == [.frame(lines: ["END LIST 0"])])
    }

    // MARK: commands and names

    @Test func buildsListAndDumpLines() {
        #expect(ConsoleCommand.list.line == "LIST")
        #expect(ConsoleCommand.dump("climate_v4_202611.csv").line == "DUMP climate_v4_202611.csv")
        #expect(ConsoleCommand.dump("daily_v1_2025.csv").line == "DUMP daily_v1_2025.csv")
        #expect(ConsoleCommand.dump("climate_v5_202611.csv").line == "DUMP climate_v5_202611.csv")
        #expect(ConsoleCommand.dump("daily_v3_2026.csv").line == "DUMP daily_v3_2026.csv")
        #expect(ConsoleCommand.dump("daily_v4_2026.csv").line == "DUMP daily_v4_2026.csv")
        #expect(ConsoleCommand.dump("events_v1_2026.csv").line == "DUMP events_v1_2026.csv")
    }

    @Test func refusesPathsAndUnknownPatterns() {
        for bad in ["../climate_v4_202611.csv", "/etc/passwd", "sub/climate_v4_202611.csv", "climate_v6_202611.csv",
                    "climate_v4_202613.csv", "climate_v1_202611.csv", "daily_v5_2026.csv", "climate_v5_202613.csv", "daily_v2_202611.csv",
                    "events_v1_202611.csv", "other.csv", "climate_v4_202611.csv ", "climate_v4_202611.csv\n", ""] {
            #expect(!LogArchive.isValidName(bad), "\(bad)")
            #expect(ConsoleCommand.dump(bad).line == nil, "\(bad)")
        }
        for good in ["climate_v2_202601.csv", "climate_v3_202612.csv", "climate_v5_202611.csv", "daily_v3_2026.csv", "daily_v4_2026.csv", "daily_v1_2024.csv", "daily_v2_2026.csv", "events_v1_2026.csv"] {
            #expect(LogArchive.isValidName(good), "\(good)")
        }
    }

    // MARK: DUMP assembly

    @Test func assemblesDumpWithLfAndCrcTextInBody() {
        var a = ReplyAssembler()
        #expect(a.append(Self.dumpReply()) == [.file(name: "climate_v4_202611.csv", bytes: Self.body)])
    }

    @Test func assemblesDumpInChunksOfOneByte() {
        var a = ReplyAssembler()
        var out: [ConsoleReply] = []
        for byte in Self.dumpReply() { out += a.append(Data([byte])) }
        #expect(out == [.file(name: "climate_v4_202611.csv", bytes: Self.body)])
    }

    @Test func assemblesEmptyFileAndTwoRepliesInOneChunk() {
        var a = ReplyAssembler()
        let two = Self.dumpReply(name: "events_v1_2026.csv", body: Data()) + Self.dumpReply()
        #expect(a.append(two) == [.file(name: "events_v1_2026.csv", bytes: Data()), .file(name: "climate_v4_202611.csv", bytes: Self.body)])
    }

    @Test func rejectsChangedByteAndWrongCrc() {
        var changed = Self.body
        changed[3] ^= 0x01
        var a = ReplyAssembler()
        let out = a.append(Self.dumpReply(body: changed, crc: Checksums.crc32(Self.body)))
        #expect(out == [.corrupt(.badCrc(expected: Checksums.crc32(changed), actual: Checksums.crc32(Self.body)))])
        // The assembler is ready for the next reply.
        #expect(a.append(Self.dumpReply()) == [.file(name: "climate_v4_202611.csv", bytes: Self.body)])
    }

    @Test func rejectsShortBodyAndLongBody() {
        // The header promises 5 bytes more than sent: the CRC line is read as body bytes.
        var short = ReplyAssembler()
        let shortOut = short.append(Self.dumpReply(size: Self.body.count + 5))
        #expect(shortOut.count == 1)
        if case .corrupt(.malformedCrcLine) = shortOut[0] {} else { Issue.record("expected malformedCrcLine, got \(shortOut[0])") }
        // The header promises 1 byte less: the last body byte stands before the CRC line.
        var long = ReplyAssembler()
        let longOut = long.append(Self.dumpReply(size: Self.body.count - 1))
        #expect(longOut.count == 1)
        if case .corrupt(.malformedCrcLine) = longOut[0] {} else { Issue.record("expected malformedCrcLine, got \(longOut[0])") }
        // A body that stops early stays incomplete until the timeout of the app.
        var cut = ReplyAssembler()
        #expect(cut.append(Data("BEGIN daily_v2_2026.csv 50\nabc".utf8)).isEmpty)
    }

    @Test func resetLeavesBinaryModeAndAcceptsTheLimit() {
        var a = ReplyAssembler()
        #expect(a.append(Data("BEGIN daily_v2_2026.csv 50\nabc".utf8)).isEmpty)
        a.reset()
        #expect(a.append(Self.dumpReply()) == [.file(name: "climate_v4_202611.csv", bytes: Self.body)])
        var edge = ReplyAssembler()
        #expect(edge.append(Data("BEGIN daily_v2_2026.csv \(ReplyAssembler.maxFileBytes)\n".utf8)).isEmpty)
    }

    @Test func errAfterBodyEndsTheReplyAndOversizeIsRefused() {
        var a = ReplyAssembler()
        let out = a.append(Data("BEGIN daily_v2_2026.csv 3\nabc".utf8) + Data("ERR READ_FAILED\n".utf8))
        #expect(out == [.status("ERR READ_FAILED")])
        var big = ReplyAssembler()
        #expect(big.append(Data("BEGIN daily_v2_2026.csv \(ReplyAssembler.maxFileBytes + 1)\n".utf8)) == [.corrupt(.tooLarge)])
    }

    @Test func otherBeginRepliesStayLineBased() {
        var a = ReplyAssembler()
        let lines = ["BEGIN HISTORY", "sensor,age_h,temp_c,rh_pct,noise_db", "0,0,21.4,45.0,38"]
        let text = lines.joined(separator: "\n") + "\n"
        let crc = String(format: "%08x", Checksums.crc32(Data(text.utf8)))
        #expect(a.append(Data((text + "CRC32 \(crc)\n").utf8)) == [.frame(lines: lines)])
    }

    // MARK: plan

    @Test func plansDownloadsForEqualShorterAndCurrentMonth() {
        let remote = [
            LogFile(name: "climate_v4_202610.csv", bytes: 500),   // equal locally: skip
            LogFile(name: "climate_v4_202609.csv", bytes: 500),   // local copy is shorter: download
            LogFile(name: "climate_v4_202608.csv", bytes: 500),   // missing locally: download
            LogFile(name: "climate_v4_202611.csv", bytes: 900),   // current month: always download
            LogFile(name: "daily_v2_2025.csv", bytes: 100),       // equal, old year: skip
            LogFile(name: "daily_v2_2026.csv", bytes: 100),       // current year: always download
            LogFile(name: "events_v1_2026.csv", bytes: 40),       // current year: always download
        ]
        let local = [
            "climate_v4_202610.csv": 500, "climate_v4_202609.csv": 200, "climate_v4_202611.csv": 900,
            "daily_v2_2025.csv": 100, "daily_v2_2026.csv": 100, "events_v1_2026.csv": 40,
        ]
        let plan = LogArchive.plan(remote: remote, localSizes: local, year: 2026, month: 11)
        #expect(plan.map(\.name) == ["climate_v4_202609.csv", "climate_v4_202608.csv", "climate_v4_202611.csv",
                                     "daily_v2_2026.csv", "events_v1_2026.csv"])
    }

    @Test func rejectsDuplicateNamesAndSkipsOversizeFiles() {
        #expect(throws: ParseError.self) {
            try ListReply.parse(lines: ["FILE daily_v2_2026.csv 1", "FILE daily_v2_2026.csv 2", "END LIST 2"])
        }
        let big = LogFile(name: "climate_v4_202601.csv", bytes: ReplyAssembler.maxFileBytes + 1)
        let edge = LogFile(name: "climate_v4_202602.csv", bytes: ReplyAssembler.maxFileBytes)
        #expect(!LogArchive.isDownloadable(big))
        #expect(LogArchive.plan(remote: [big, edge], localSizes: [:], year: 2026, month: 11) == [edge])
    }

    @Test func notFoundStatusEndsOnlyThatReply() {
        var a = ReplyAssembler()
        #expect(a.append(Data("ERR NOT_FOUND\n".utf8)) == [.status("ERR NOT_FOUND")])
        #expect(a.append(Self.dumpReply()) == [.file(name: "climate_v4_202611.csv", bytes: Self.body)])
    }

    @Test func planIgnoresInvalidRemoteNames() {
        let plan = LogArchive.plan(remote: [LogFile(name: "../x.csv", bytes: 1)], localSizes: [:], year: 2026, month: 11)
        #expect(plan.isEmpty)
    }

    // MARK: store

    @Test func storesFileOnlyUnderItsFinalNameAndLeavesNoTempFile() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("rlcd-archive-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        try LogArchive.store(Self.body, name: "climate_v4_202611.csv", in: dir)
        try LogArchive.store(Data("new".utf8), name: "climate_v4_202611.csv", in: dir) // replaces
        #expect(try Data(contentsOf: dir.appendingPathComponent("climate_v4_202611.csv")) == Data("new".utf8))
        #expect(try FileManager.default.contentsOfDirectory(atPath: dir.path) == ["climate_v4_202611.csv"])
        #expect(throws: LogArchiveError.self) { try LogArchive.store(Self.body, name: "../evil.csv", in: dir) }
        #expect(LogArchive.localSizes(in: dir) == ["climate_v4_202611.csv": 3])
    }

    @Test func failedStoreKeepsTheOldFileAndLeavesNoTempFile() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("rlcd-archive-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer {
            try? FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: dir.path)
            try? FileManager.default.removeItem(at: dir)
        }
        try LogArchive.store(Data("old".utf8), name: "daily_v2_2026.csv", in: dir)
        try FileManager.default.setAttributes([.posixPermissions: 0o500], ofItemAtPath: dir.path)
        #expect(throws: Error.self) { try LogArchive.store(Data("new".utf8), name: "daily_v2_2026.csv", in: dir) }
        try FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: dir.path)
        #expect(try Data(contentsOf: dir.appendingPathComponent("daily_v2_2026.csv")) == Data("old".utf8))
        #expect(try FileManager.default.contentsOfDirectory(atPath: dir.path) == ["daily_v2_2026.csv"])
    }
}

private extension ReplyAssembler {
    /// Feeds one chunk to a copy of the assembler.
    func appendingOne(_ chunk: Data) -> [ConsoleReply] {
        var copy = self
        return copy.append(chunk)
    }
}
