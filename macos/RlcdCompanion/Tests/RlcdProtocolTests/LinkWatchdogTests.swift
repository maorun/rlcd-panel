import Foundation
import Testing
@testable import RlcdProtocol

/// Whole milliseconds, so 9.9 s and 10 s are exact.
private final class FakeClock {
    private var ms = 100_000
    var now: TimeInterval { TimeInterval(ms) / 1000 }
    func advance(_ seconds: TimeInterval) { ms += Int((seconds * 1000).rounded()) }
}

@Suite final class LinkWatchdogTests {
    private let clock = FakeClock()
    private var dog: LinkWatchdog

    init() {
        let c = clock
        dog = LinkWatchdog(clock: { c.now })
    }

    @Test func testIdleNeverFails() {
        clock.advance(1000)
        #expect(dog.check() == nil)
        #expect(dog.phase == .idle)
    }

    @Test func testConnectTimeoutAtTenSeconds() {
        dog.beginConnect()
        #expect(dog.phase == .connecting)
        clock.advance(9.9)
        #expect(dog.check() == nil)
        clock.advance(0.1)
        #expect(dog.check() == .connectTimeout)
        #expect(dog.phase == .idle)
        #expect(dog.check() == nil)
    }

    @Test func testConnectedStopsConnectTimer() {
        dog.beginConnect()
        clock.advance(5)
        dog.connected()
        clock.advance(60)
        #expect(dog.check() == nil)
    }

    @Test func testWriteTimeoutAtFiveSeconds() {
        dog.beginWrite()
        #expect(dog.phase == .writing)
        clock.advance(4.9)
        #expect(dog.check() == nil)
        clock.advance(0.1)
        #expect(dog.check() == .writeTimeout)
    }

    @Test func testWriteCompletedStopsWriteTimer() {
        dog.beginWrite()
        clock.advance(4)
        dog.writeCompleted()
        clock.advance(60)
        #expect(dog.check() == nil)
    }

    @Test func testCommandTimeoutAtTenSeconds() {
        dog.beginCommand()
        #expect(dog.phase == .awaitingChunk)
        clock.advance(9.9)
        #expect(dog.check() == nil)
        clock.advance(0.1)
        #expect(dog.check() == .commandTimeout)
    }

    @Test func testEachChunkRestartsCommandTimer() {
        dog.beginCommand()
        for _ in 0..<5 {
            clock.advance(9)
            #expect(dog.check() == nil)
            dog.chunkReceived()
        }
        clock.advance(9.9)
        #expect(dog.check() == nil)
        clock.advance(0.1)
        #expect(dog.check() == .commandTimeout)
    }

    @Test func testChunkWithoutCommandStartsNoTimer() {
        dog.chunkReceived()
        clock.advance(60)
        #expect(dog.check() == nil)
        #expect(dog.phase == .idle)
    }

    @Test func testCommandFinishedStopsCommandTimer() {
        dog.beginCommand()
        clock.advance(5)
        dog.commandFinished()
        clock.advance(60)
        #expect(dog.check() == nil)
    }

    @Test func testCancelPreventsAllTimeouts() {
        dog.beginConnect()
        dog.beginWrite()
        dog.beginCommand()
        clock.advance(4)
        dog.cancel()
        clock.advance(60)
        #expect(dog.check() == nil)
        #expect(dog.phase == .idle)
    }

    @Test func testFailureClearsOtherTimers() {
        dog.beginWrite()
        dog.beginCommand()
        clock.advance(5)
        #expect(dog.check() == .writeTimeout)
        clock.advance(60)
        #expect(dog.check() == nil)
    }

    @Test func testWriteThenCommandSequence() {
        dog.beginWrite()
        clock.advance(4.5)
        dog.chunkReceived() // no command waits: no timer starts
        dog.writeCompleted()
        dog.beginCommand()
        #expect(dog.phase == .awaitingChunk)
        clock.advance(9.5)
        #expect(dog.check() == nil)
        clock.advance(0.5)
        #expect(dog.check() == .commandTimeout)
    }

    @Test func testReasonTextsAreFixed() {
        #expect(LinkFailure.connectTimeout.reason == "Console connect timed out (10 s)")
        #expect(LinkFailure.writeTimeout.reason == "Console write timed out (5 s)")
        #expect(LinkFailure.commandTimeout.reason == "Console reply timed out (10 s)")
    }

    @Test func testTimerRestartAfterFailure() {
        dog.beginConnect()
        clock.advance(10)
        #expect(dog.check() == .connectTimeout)
        dog.beginConnect()
        clock.advance(9.9)
        #expect(dog.check() == nil)
    }
}

@Suite struct PanelPinTests {
    private let a = UUID(uuidString: "11111111-1111-1111-1111-111111111111")!
    private let b = UUID(uuidString: "22222222-2222-2222-2222-222222222222")!

    @Test func testStoreReadAndClear() {
        let pin = PanelPin(store: InMemoryPinStore())
        #expect(pin.pinned == nil)
        pin.pin(a)
        #expect(pin.pinned == a)
        pin.clear()
        #expect(pin.pinned == nil)
    }

    @Test func testPinFromText() throws {
        let pin = PanelPin(store: InMemoryPinStore())
        try pin.pin(text: " \(a.uuidString.lowercased())\n")
        #expect(pin.pinned == a)
    }

    @Test func testRejectsMalformedUUID() {
        let pin = PanelPin(store: InMemoryPinStore())
        #expect(throws: PanelPinError.invalidUUID("not-a-uuid")) { try pin.pin(text: "not-a-uuid") }
        #expect(throws: PanelPinError.self) { try PanelPin.parse("") }
        #expect(pin.pinned == nil)
    }

    @Test func testMalformedStoredTextCountsAsNoPin() {
        let store = InMemoryPinStore()
        store.set("garbage", forKey: PanelPin.storeKey)
        let pin = PanelPin(store: store)
        #expect(pin.pinned == nil)
        #expect(pin.accepts(a))
    }

    @Test func testFiltersSecondUUID() {
        let pin = PanelPin(store: InMemoryPinStore())
        #expect(pin.accepts(a))
        #expect(pin.accepts(b)) // no pin: current behavior
        pin.pin(a)
        #expect(pin.accepts(a))
        #expect(!pin.accepts(b))
        pin.clear()
        #expect(pin.accepts(b))
    }

    @Test func testDiscoveryListSortsAndUpdates() {
        var list = DiscoveryList()
        let t0 = Date(timeIntervalSince1970: 1000)
        list.record(id: a, name: "RLCD", rssi: -70, at: t0)
        list.record(id: b, name: nil, rssi: -50, at: t0)
        #expect(list.panels.map(\.id) == [b, a])
        list.record(id: a, name: "", rssi: -40, at: t0.addingTimeInterval(5))
        #expect(list.panels.map(\.id) == [a, b])
        #expect(list.panels[0].name == "RLCD") // an empty name keeps the old one
        #expect(list.panels[0].age(at: t0.addingTimeInterval(8)) == 3)
    }

    @Test func testDiscoveryListCapsEntriesAndKeepsStrongest() {
        var list = DiscoveryList()
        let t0 = Date(timeIntervalSince1970: 1000)
        for i in 0..<40 { list.record(id: UUID(), name: nil, rssi: -100 + i, at: t0) }
        #expect(list.panels.count == DiscoveryList.maxPanels)
        #expect(list.panels.first?.rssi == -61)
        #expect(list.panels.last?.rssi == -76)
    }

    @Test func testNameIsSanitized() {
        #expect(DiscoveryList.sanitizedName("RL\u{0007}CD\n") == "RLCD")
        #expect(DiscoveryList.sanitizedName("  \u{0001} ") == nil)
        #expect(DiscoveryList.sanitizedName(nil) == nil)
        #expect(DiscoveryList.sanitizedName(String(repeating: "x", count: 100))?.count == DiscoveryList.maxNameLength)
    }

    @Test func testEqualRssiOrderIsDeterministic() {
        var list = DiscoveryList()
        let t0 = Date(timeIntervalSince1970: 1000)
        list.record(id: b, name: nil, rssi: -60, at: t0)
        list.record(id: a, name: nil, rssi: -60, at: t0)
        #expect(list.panels.map(\.id) == [a, b])
    }

    @Test func testPinSurvivesNewInstanceOnSameStore() {
        let store = InMemoryPinStore()
        PanelPin(store: store).pin(a)
        #expect(PanelPin(store: store).pinned == a)
    }

    @Test func testDiscoveryListPruneAndKeepOnly() {
        var list = DiscoveryList()
        let t0 = Date(timeIntervalSince1970: 1000)
        list.record(id: a, name: nil, rssi: -60, at: t0)
        list.record(id: b, name: nil, rssi: -60, at: t0.addingTimeInterval(100))
        list.prune(olderThan: 60, at: t0.addingTimeInterval(120))
        #expect(list.panels.map(\.id) == [b])
        list.keepOnly(a)
        #expect(list.panels.isEmpty)
    }
}
