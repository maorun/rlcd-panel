import AppKit
import RlcdProtocol
import SwiftUI

/// Shows the last frame of the panel. The Refresh button sends `SCREEN`; needs the USB serial link.
struct PanelView: View {
    @Bindable var model: AppModel

    var body: some View {
        GroupBox("Panel view") {
            VStack(alignment: .leading, spacing: 6) {
                HStack {
                    Button("Refresh") { model.refreshPanelImage() }
                        .disabled(!model.consoleConnected || model.transport != .usb)
                    Button("Save PNG") { model.exportPanelPng() }
                        .disabled(model.panelImage == nil)
                    if model.transport != .usb {
                        Text("SCREEN needs the USB serial link.").font(.caption).foregroundStyle(.secondary)
                    }
                }
                if let image = model.panelImage, let cg = Self.cgImage(image) {
                    Image(nsImage: NSImage(cgImage: cg, size: NSSize(width: image.width, height: image.height)))
                        .interpolation(.none)
                        .resizable()
                        .aspectRatio(CGFloat(image.width) / CGFloat(image.height), contentMode: .fit)
                        .frame(maxWidth: 400)
                        .border(Color.secondary)
                } else {
                    Text("No frame yet.").font(.caption).foregroundStyle(.secondary)
                }
            }.frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private static func cgImage(_ image: PanelImage) -> CGImage? {
        let pixels = image.rgba()
        guard let provider = CGDataProvider(data: Data(pixels) as CFData) else { return nil }
        return CGImage(
            width: image.width, height: image.height, bitsPerComponent: 8, bitsPerPixel: 32,
            bytesPerRow: image.width * 4, space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedLast.rawValue),
            provider: provider, decode: nil, shouldInterpolate: false, intent: .defaultIntent)
    }
}
