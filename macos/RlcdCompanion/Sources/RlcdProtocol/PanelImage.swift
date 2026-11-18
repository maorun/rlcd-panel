import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers

/// The 400 x 300 1-bit image of the panel, decoded from the `screen.bin` body of the `SCREEN` reply.
///
/// The packing is the one of the `ST7305` framebuffer: column-major blocks of 2 x 4 pixels with an inverted Y,
/// `byte_index = (x / 2) * 75 + ((299 - y) / 4)` and `bit = 7 - (((299 - y) % 4) * 2 + (x % 2))`.
/// A set bit is a black pixel.
public struct PanelImage: Equatable {
    public static let width = 400
    public static let height = 300
    public static let byteCount = 15_000
    /// Name of the file in the `BEGIN` line of the `SCREEN` reply.
    public static let fileName = "screen.bin"

    private let bytes: Data

    public var width: Int { Self.width }
    public var height: Int { Self.height }

    /// Throws `ParseError.badLine` for a body that does not hold exactly 15,000 bytes.
    public static func decode(_ data: Data) throws -> PanelImage {
        guard data.count == byteCount else { throw ParseError.badLine("\(fileName) \(data.count) bytes") }
        return PanelImage(bytes: Data(data))
    }

    /// True for a black pixel. A position outside the image is white.
    public func isBlack(x: Int, y: Int) -> Bool {
        guard (0..<Self.width).contains(x), (0..<Self.height).contains(y) else { return false }
        let inverted = Self.height - 1 - y
        let index = (x / 2) * 75 + inverted / 4
        let bit = 7 - ((inverted % 4) * 2 + x % 2)
        return bytes[bytes.startIndex + index] & (1 << UInt8(bit)) != 0
    }

    /// 8-bit RGBA, row-major, opaque: black `0,0,0` and white `255,255,255`.
    public func rgba() -> [UInt8] {
        var out = [UInt8](repeating: 255, count: Self.width * Self.height * 4)
        for y in 0..<Self.height {
            for x in 0..<Self.width where isBlack(x: x, y: y) {
                let offset = (y * Self.width + x) * 4
                out[offset] = 0
                out[offset + 1] = 0
                out[offset + 2] = 0
            }
        }
        return out
    }

    /// PNG file of the image: 1-bit grayscale, black for a set pixel. `nil` when the encoder fails.
    public func pngData() -> Data? {
        let rowBytes = (Self.width + 7) / 8
        var packed = [UInt8](repeating: 0xFF, count: rowBytes * Self.height)
        for y in 0..<Self.height {
            for x in 0..<Self.width where isBlack(x: x, y: y) {
                packed[y * rowBytes + x / 8] &= ~UInt8(0x80 >> (x % 8))
            }
        }
        guard let provider = CGDataProvider(data: Data(packed) as CFData),
              let image = CGImage(
                width: Self.width, height: Self.height, bitsPerComponent: 1, bitsPerPixel: 1,
                bytesPerRow: rowBytes, space: CGColorSpaceCreateDeviceGray(),
                bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.none.rawValue),
                provider: provider, decode: nil, shouldInterpolate: false, intent: .defaultIntent)
        else { return nil }
        let out = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(out, UTType.png.identifier as CFString, 1, nil)
        else { return nil }
        CGImageDestinationAddImage(destination, image, nil)
        guard CGImageDestinationFinalize(destination) else { return nil }
        return out as Data
    }
}
