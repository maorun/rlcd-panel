import Foundation
import Testing
@testable import RlcdProtocol

/// Loads an `EVENTS` reply from `tests/fixtures/<name>` through the `ReplyAssembler`, so the `CRC32` line is checked.
func loadEventsFixture(named name: String, file: String = #filePath) throws -> [EventRow] {
    var url = URL(fileURLWithPath: file)
    for _ in 0..<5 { url.deleteLastPathComponent() }
    let data = try Data(contentsOf: url.appendingPathComponent("tests/fixtures/\(name)"))
    var assembler = ReplyAssembler()
    let replies = assembler.append(data)
    guard replies.count == 1, case .frame(let lines)? = replies.first else {
        Issue.record("\(name) is not one valid frame: \(replies)")
        return []
    }
    return try EventsReply.parse(lines: lines)
}
