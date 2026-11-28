import Foundation
import Testing
@testable import RlcdProtocol

/// Fuzz test of `ReplyAssembler`: each fixture of `tests/fixtures` is damaged with fixed seeds.
/// A damaged reply must give a CRC error or an incomplete reply. It never gives other content.
@Suite struct ReplyAssemblerFuzzTests {
    static let seeds: [UInt64] = [1, 7, 42, 1234, 99991, 0xC0FFEE, 0xBADF00D, 20261128]
    static let mutationsPerSeed = 40
    static let cutLimit = 200

    /// Small deterministic generator (SplitMix64). Same sequence on each host.
    struct Rng {
        var state: UInt64
        mutating func next() -> UInt64 {
            state &+= 0x9E37_79B9_7F4A_7C15
            var z = state
            z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
            z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
            return z ^ (z >> 31)
        }
        mutating func below(_ n: Int) -> Int { Int(next() % UInt64(n)) }
    }

    static func fixturesDirectory() -> URL {
        var url = URL(fileURLWithPath: #filePath)
        for _ in 0..<5 { url.deleteLastPathComponent() }
        return url.appendingPathComponent("tests/fixtures")
    }

    /// Fixtures that the assembler accepts as one `frame` reply, with that reply.
    static func framedFixtures() throws -> [(name: String, data: Data, lines: [String])] {
        let dir = fixturesDirectory()
        let names = try FileManager.default.contentsOfDirectory(atPath: dir.path).sorted()
        var out: [(String, Data, [String])] = []
        for name in names where name.hasSuffix(".txt") {
            let data = try Data(contentsOf: dir.appendingPathComponent(name))
            var assembler = ReplyAssembler()
            let replies = assembler.append(data)
            if replies.count == 1, case .frame(let lines) = replies[0] { out.append((name, data, lines)) }
        }
        return out
    }

    /// Feeds `data` in BLE chunks of 20 bytes. The result is every reply, in order.
    static func feed(_ data: Data) -> [ConsoleReply] {
        var assembler = ReplyAssembler()
        var out: [ConsoleReply] = []
        var i = 0
        while i < data.count {
            let end = min(i + 20, data.count)
            out += assembler.append(data.subdata(in: i..<end))
            i = end
        }
        return out
    }

    /// A damaged reply may give no reply (incomplete), a CRC error or a parse error type reply.
    /// It may only give a frame with the content of the original (for example a flip of the hex digit case).
    static func acceptable(_ replies: [ConsoleReply], original: [String]) -> Bool {
        replies.allSatisfy { reply in
            switch reply {
            case .corrupt: return true
            case .frame(let lines): return lines == original
            case .file, .status: return false
            }
        }
    }

    @Test func fixturesAreFound() throws {
        let fixtures = try Self.framedFixtures()
        #expect(fixtures.count >= 20)
        for fixture in fixtures { #expect(Self.feed(fixture.data) == [.frame(lines: fixture.lines)], "\(fixture.name)") }
    }

    @Test func cutReplyIsIncompleteOrCorrupt() throws {
        for fixture in try Self.framedFixtures() {
            for position in 0..<min(Self.cutLimit, fixture.data.count) {
                let replies = Self.feed(fixture.data.prefix(position))
                #expect(Self.acceptable(replies, original: fixture.lines), "\(fixture.name) cut at \(position)")
            }
        }
    }

    @Test func droppedByteGivesNoWrongFrame() throws {
        for fixture in try Self.framedFixtures() {
            for seed in Self.seeds {
                var rng = Rng(state: seed)
                for _ in 0..<Self.mutationsPerSeed {
                    var data = fixture.data
                    let position = rng.below(data.count)
                    data.remove(at: position)
                    let replies = Self.feed(data)
                    #expect(Self.acceptable(replies, original: fixture.lines), "\(fixture.name) seed \(seed) drop \(position)")
                }
            }
        }
    }

    @Test func flippedByteGivesNoWrongFrame() throws {
        for fixture in try Self.framedFixtures() {
            for seed in Self.seeds {
                var rng = Rng(state: seed)
                for _ in 0..<Self.mutationsPerSeed {
                    var data = fixture.data
                    let position = rng.below(data.count)
                    data[position] ^= UInt8(1 << rng.below(8))
                    let replies = Self.feed(data)
                    #expect(Self.acceptable(replies, original: fixture.lines), "\(fixture.name) seed \(seed) flip \(position)")
                }
            }
        }
    }

    @Test func flippedBodyByteGivesCorruptReply() throws {
        // The CRC check must catch each change of the first line: a frame with other lines never passes.
        for fixture in try Self.framedFixtures() {
            var data = fixture.data
            guard let first = data.firstIndex(where: { $0 >= 0x41 && $0 <= 0x5A }) else { continue }
            data[first] ^= 0x01
            let replies = Self.feed(data)
            let wrong = replies.contains { if case .frame(let l) = $0 { return l != fixture.lines }; return false }
            #expect(!wrong, "\(fixture.name)")
        }
    }
}
