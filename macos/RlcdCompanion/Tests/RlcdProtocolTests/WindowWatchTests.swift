import Testing
@testable import RlcdProtocol

@Suite struct WindowWatchTests {
    @Test func firstMaskGivesNoTile() {
        var watch = WindowWatch()
        #expect(watch.update(mask: 0b101).isEmpty)
        // The first read stored the mask: an equal mask gives no tile, a new bit gives its tile.
        #expect(watch.update(mask: 0b101).isEmpty)
        #expect(watch.update(mask: 0b111) == [1])
    }

    @Test func risingBitGivesItsTileOnce() {
        var watch = WindowWatch()
        _ = watch.update(mask: 0)
        #expect(watch.update(mask: 0b100_0001) == [0, 6])
        #expect(watch.update(mask: 0b100_0001).isEmpty)
    }

    @Test func fallingBitAndEqualMaskGiveNoTile() {
        var watch = WindowWatch()
        _ = watch.update(mask: 0b11)
        #expect(watch.update(mask: 0b11).isEmpty)
        #expect(watch.update(mask: 0b01).isEmpty)
        #expect(watch.update(mask: 0b11) == [1])
    }

    @Test func nilMaskKeepsTheStoredMask() {
        var watch = WindowWatch()
        #expect(watch.update(mask: nil).isEmpty)
        _ = watch.update(mask: 0b01)
        #expect(watch.update(mask: nil).isEmpty)
        #expect(watch.update(mask: 0b01).isEmpty)
        #expect(watch.update(mask: 0b11) == [1])
    }

    @Test func nilMaskBeforeTheFirstReadKeepsTheFirstReadAsBaseline() {
        var watch = WindowWatch()
        _ = watch.update(mask: nil)
        #expect(watch.update(mask: 0b10).isEmpty)
    }

    @Test func bitAboveTileTenIsIgnored() {
        var watch = WindowWatch()
        _ = watch.update(mask: 0)
        #expect(watch.update(mask: 1 << 11).isEmpty)
    }

    @Test func bodyUsesTheRoomName() {
        #expect(WindowWatch.notificationBody(tile: 0, config: nil) == "Window open: Onboard")
        #expect(WindowWatch.notificationBody(tile: 3, config: nil) == "Window open: Slot 3")
    }
}
