import Testing
@testable import RlcdProtocol

struct SlotAssignmentTests {
    private let mac = "AA:BB:CC:DD:EE:01"

    @Test func batchGivesMacThenNameCommand() {
        let lines = SlotAssignment.batch(slot: 2, mac: "aa:bb:cc:dd:ee:01", name: "KITCHEN")?.map(\.line)
        #expect(lines == ["SET slot2_mac=AA:BB:CC:DD:EE:01", "SET slot2_name=KITCHEN"])
    }

    @Test func batchGivesOneCommandForEmptyName() {
        #expect(SlotAssignment.batch(slot: 3, mac: mac, name: "")?.map(\.line) == ["SET slot3_mac=AA:BB:CC:DD:EE:01"])
        #expect(SlotAssignment.batch(slot: 3, mac: mac, name: nil)?.map(\.line) == ["SET slot3_mac=AA:BB:CC:DD:EE:01"])
    }

    @Test func batchAcceptsTwelveCharactersAndSlotTen() {
        let lines = SlotAssignment.batch(slot: 10, mac: mac, name: "ABCDEFGHIJKL")?.map(\.line)
        #expect(lines == ["SET slot10_mac=AA:BB:CC:DD:EE:01", "SET slot10_name=ABCDEFGHIJKL"])
        #expect(SlotAssignment.maxNameChars == 12)
    }

    @Test func batchRejectsNameOverTwelveCharacters() {
        #expect(SlotAssignment.batch(slot: 1, mac: mac, name: "ABCDEFGHIJKLM") == nil)
    }

    @Test func batchRejectsSpaceAndEqualsSign() {
        #expect(SlotAssignment.batch(slot: 1, mac: mac, name: "LIVING ROOM") == nil)
        #expect(SlotAssignment.batch(slot: 1, mac: mac, name: "A=B") == nil)
    }

    @Test func batchKeepsEveryLineWithinFortyBytes() {
        // The longest valid batch stays at 40 bytes or less; the console refuses a longer line.
        let longest = SlotAssignment.batch(slot: 10, mac: mac, name: "ABCDEFGHIJKL") ?? []
        #expect(longest.count == 2)
        #expect(longest.allSatisfy { ($0.line?.utf8.count ?? 99) <= 40 })
        #expect(ConsoleCommand.set(key: "slot10_name", value: String(repeating: "A", count: 25)).line == nil)
        #expect(SlotAssignment.fitsConsoleLine([.set(key: "slot10_name", value: String(repeating: "A", count: 25))]) == false)
        #expect(SlotAssignment.fitsConsoleLine(longest))
    }

    @Test func batchRejectsBadSlotAndMac() {
        #expect(SlotAssignment.batch(slot: 0, mac: mac, name: "A") == nil)
        #expect(SlotAssignment.batch(slot: 11, mac: mac, name: "A") == nil)
        #expect(SlotAssignment.batch(slot: 1, mac: "AA:BB", name: "A") == nil)
        #expect(SlotAssignment.batch(slot: 1, mac: "00:00:00:00:00:00", name: "A") == nil)
    }

    @Test func batchRejectsNameThatFirmwareRefuses() {
        #expect(SlotAssignment.batch(slot: 1, mac: mac, name: "kitchen")?.map(\.line).last == "SET slot1_name=KITCHEN")
        #expect(SlotAssignment.batch(slot: 1, mac: mac, name: "KÜCHE") == nil)
        #expect(SlotAssignment.batch(slot: 1, mac: mac, name: "-")?.count == 2)
    }

    @Test func batchRunsThroughConfigBatch() throws {
        let commands = try #require(SlotAssignment.batch(slot: 1, mac: mac, name: "HALL"))
        var run = ConfigBatch(commands: commands)
        #expect(run.next()?.line == "SET slot1_mac=AA:BB:CC:DD:EE:01")
        run.receive(status: "OK SET slot1_mac")
        #expect(run.next()?.line == "SET slot1_name=HALL")
        run.receive(status: "OK SET slot1_name")
        #expect(run.next() == .config)
        run.receive(status: "OK")
        #expect(run.isFinished)
        #expect(run.applied == ["slot1_mac", "slot1_name"])
    }

    @Test func errorOnMacSkipsName() throws {
        var run = ConfigBatch(commands: try #require(SlotAssignment.batch(slot: 1, mac: mac, name: "HALL")))
        _ = run.next()
        run.receive(status: "ERR BAD_VALUE")
        #expect(run.next() == .config)
        #expect(run.failure?.key == "slot1_mac")
        #expect(run.applied.isEmpty)
    }

    @Test func errorOnNameKeepsAppliedMac() throws {
        var run = ConfigBatch(commands: try #require(SlotAssignment.batch(slot: 1, mac: mac, name: "HALL")))
        _ = run.next()
        run.receive(status: "OK SET slot1_mac")
        _ = run.next()
        run.receive(status: "ERR BAD_VALUE")
        #expect(run.next() == .config)
        #expect(run.failure?.key == "slot1_name")
        #expect(run.applied == ["slot1_mac"])
    }

    @Test func reportsDuplicateMac() throws {
        let config = try ConfigReply.parse(lines: ["slot3_mac=AA:BB:CC:DD:EE:01"])
        #expect(SlotAssignment.conflicts(slot: 1, mac: "aa:bb:cc:dd:ee:01", config: config) == [3])
        #expect(SlotAssignment.warning(slot: 1, mac: mac, config: config) != nil)
        #expect(SlotAssignment.conflicts(slot: 3, mac: mac, config: config).isEmpty)
    }
}
