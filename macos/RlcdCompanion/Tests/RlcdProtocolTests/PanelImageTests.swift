import CoreGraphics
import Foundation
import ImageIO
import Testing
@testable import RlcdProtocol

/// Sets one pixel with the packing of the firmware (`St7305Framebuffer::computeByteAndMask`).
private func frame(black: [(Int, Int)]) -> Data {
    var bytes = Data(count: PanelImage.byteCount)
    for (x, y) in black {
        let inv = 299 - y
        bytes[(x / 2) * 75 + inv / 4] |= UInt8(1 << (7 - ((inv % 4) * 2 + x % 2)))
    }
    return bytes
}

private func blackPixels(_ image: PanelImage) -> [(Int, Int)] {
    var out: [(Int, Int)] = []
    for y in 0..<PanelImage.height {
        for x in 0..<PanelImage.width where image.isBlack(x: x, y: y) { out.append((x, y)) }
    }
    return out
}

struct PanelImageTests {
    @Test func onePixelAtThreeFive() throws {
        let image = try PanelImage.decode(frame(black: [(3, 5)]))
        #expect(image.width == 400 && image.height == 300)
        let black = blackPixels(image)
        #expect(black.count == 1 && black[0] == (3, 5))
    }

    @Test func cornersMapToTheirBits() throws {
        var bytes = Data(count: PanelImage.byteCount)
        bytes[0 * 75 + 74] = 0b0000_0010 // x 0, y 0: inverted y 299 = block 74, local 3, bit 7 - 6 = 1
        let first = try PanelImage.decode(bytes)
        #expect(blackPixels(first).count == 1 && first.isBlack(x: 0, y: 0))
        var last = Data(count: PanelImage.byteCount)
        // x 399, y 299: block 0, local 0, odd x: bit 7 - 1 = 6
        last[199 * 75 + 0] = 0b0100_0000
        let decoded = try PanelImage.decode(last)
        #expect(blackPixels(decoded).count == 1 && decoded.isBlack(x: 399, y: 299))
        #expect(try PanelImage.decode(frame(black: [(0, 0), (399, 299)])).isBlack(x: 399, y: 299))
    }

    @Test func emptyAndFullFrames() throws {
        #expect(blackPixels(try PanelImage.decode(Data(count: PanelImage.byteCount))).isEmpty)
        let full = try PanelImage.decode(Data(repeating: 0xFF, count: PanelImage.byteCount))
        #expect(blackPixels(full).count == 400 * 300)
    }

    @Test func wrongByteCountThrowsBadLine() {
        #expect(throws: ParseError.self) { try PanelImage.decode(Data(count: 14_999)) }
        #expect(throws: ParseError.self) { try PanelImage.decode(Data(count: 15_001)) }
        #expect(throws: ParseError.badLine("screen.bin 14999 bytes")) { try PanelImage.decode(Data(count: 14_999)) }
    }

    @Test func outsidePixelIsWhite() throws {
        let image = try PanelImage.decode(Data(repeating: 0xFF, count: PanelImage.byteCount))
        #expect(!image.isBlack(x: 400, y: 0) && !image.isBlack(x: 0, y: -1))
    }

    @Test func rgbaHasOneBytePerChannel() throws {
        let image = try PanelImage.decode(frame(black: [(3, 5)]))
        let rgba = image.rgba()
        #expect(rgba.count == 400 * 300 * 4)
        let offset = (5 * 400 + 3) * 4
        #expect(Array(rgba[offset..<offset + 4]) == [0, 0, 0, 255])
        #expect(Array(rgba[0..<4]) == [255, 255, 255, 255])
    }

    @Test func pngRoundTripKeepsCornersAndCheckerboard() throws {
        // black: corners (0,0), (0,299), (399,299) and checkerboard pixels (1,1), (2,0); (399,0), (1,0) and (0,1) stay white
        let black = [(0, 0), (0, 299), (399, 299), (1, 1), (2, 0)]
        let image = try PanelImage.decode(frame(black: black))
        let png = try #require(image.pngData())
        let source = try #require(CGImageSourceCreateWithData(png as CFData, nil))
        let cg = try #require(CGImageSourceCreateImageAtIndex(source, 0, nil))
        #expect(cg.width == 400 && cg.height == 300)
        var gray = [UInt8](repeating: 0, count: 400 * 300)
        let context = try #require(CGContext(
            data: &gray, width: 400, height: 300, bitsPerComponent: 8, bytesPerRow: 400,
            space: CGColorSpaceCreateDeviceGray(), bitmapInfo: CGImageAlphaInfo.none.rawValue))
        context.draw(cg, in: CGRect(x: 0, y: 0, width: 400, height: 300))
        func value(_ x: Int, _ y: Int) -> UInt8 { gray[y * 400 + x] }
        #expect(value(0, 0) == 0)
        #expect(value(399, 0) == 255)
        #expect(value(0, 299) == 0)
        #expect(value(399, 299) == 0)
        #expect(value(1, 1) == 0 && value(2, 0) == 0)
        #expect(value(1, 0) == 255 && value(0, 1) == 255)
        // each pixel of the PNG equals the decoded image
        let mismatches = (0..<300).reduce(0) { sum, y in
            sum + (0..<400).filter { (value($0, y) == 0) != image.isBlack(x: $0, y: y) }.count
        }
        #expect(mismatches == 0)
    }

    @Test func commandLine() {
        #expect(ConsoleCommand.screen.line == "SCREEN")
    }

    @Test func assemblerReturnsFileForScreenBin() {
        var a = ReplyAssembler()
        let body = frame(black: [(3, 5)])
        var data = Data("BEGIN screen.bin 15000\n".utf8) + body
        data.append(Data(String(format: "CRC32 %08x\n", Checksums.crc32(body)).utf8))
        #expect(a.append(data) == [.file(name: "screen.bin", bytes: body)])
    }

    @Test func assemblerRefusesWrongCrcForScreenBin() {
        var a = ReplyAssembler()
        let body = frame(black: [(3, 5)])
        let data = Data("BEGIN screen.bin 15000\n".utf8) + body + Data("CRC32 00000000\n".utf8)
        let out = a.append(data)
        #expect(out == [.corrupt(.badCrc(expected: Checksums.crc32(body), actual: 0))])
    }

    @Test func assemblerKeepsRefusingOtherNamesAndSizes() {
        var a = ReplyAssembler()
        // another size is no frame: the header stays an ordinary line, never a binary body
        let out = a.append(Data("BEGIN screen.bin 14999\n".utf8))
        #expect(out.isEmpty)
        var b = ReplyAssembler()
        #expect(b.append(Data("BEGIN other.bin 15000\n".utf8)).isEmpty)
    }

    @Test func errNoFrameIsAStatus() {
        var a = ReplyAssembler()
        #expect(a.append(Data("ERR NO_FRAME\n".utf8)) == [.status("ERR NO_FRAME")])
    }
}
