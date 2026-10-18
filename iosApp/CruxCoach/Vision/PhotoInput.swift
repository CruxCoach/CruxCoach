import UIKit

/// A photo scaled for the model, as packed RGB (Android `PreparedImage`).
struct PreparedImage {
    let rgb: [UInt8]
    let width: Int32
    let height: Int32
    let preview: UIImage
}

/// Android `PhotoInput`: decodes a photo with its orientation applied, scales it
/// to at most 640 px on the longest side and hands the model packed RGB. The
/// photo stays in memory on the device; nothing is written or sent.
enum PhotoInput {
    /// Longest side handed to the model, as on Android: about 300 image tokens
    /// for a 4:3 photo, and the encoder's time grows with its square.
    static let maxSide: CGFloat = 640

    static func prepare(data: Data) -> PreparedImage? {
        guard let image = UIImage(data: data) else { return nil }
        return prepare(image: image)
    }

    static func prepare(image: UIImage) -> PreparedImage? {
        let pixelWidth: CGFloat = image.size.width * image.scale
        let pixelHeight: CGFloat = image.size.height * image.scale
        let longest: CGFloat = max(pixelWidth, pixelHeight)
        guard longest > 0 else { return nil }
        let scale: CGFloat = min(1, maxSide / longest)
        let width = max(1, Int((pixelWidth * scale).rounded()))
        let height = max(1, Int((pixelHeight * scale).rounded()))
        let scaled = render(width: width, height: height) { _ in
            // UIImage.draw applies the EXIF orientation.
            image.draw(in: CGRect(x: 0, y: 0, width: width, height: height))
        }
        return packed(scaled)
    }

    /// A drawn plate for checking the engine: the same picture as Android's
    /// probe image, so no photo of anyone's meal is involved.
    static func probeImage() -> PreparedImage? {
        let width = Int(maxSide)
        let height = Int(maxSide) * 3 / 4
        let image = render(width: width, height: height) { context in
            let cg = context.cgContext
            cg.setFillColor(rgb(196, 160, 120))
            cg.fill(CGRect(x: 0, y: 0, width: width, height: height))
            disc(cg, 320, 240, 210, rgb(255, 255, 255))
            disc(cg, 250, 210, 90, rgb(245, 240, 225))
            disc(cg, 400, 220, 70, rgb(60, 140, 60))
            disc(cg, 330, 340, 60, rgb(200, 120, 60))
        }
        return packed(image)
    }

    private static func rgb(_ r: CGFloat, _ g: CGFloat, _ b: CGFloat) -> CGColor {
        UIColor(red: r / 255, green: g / 255, blue: b / 255, alpha: 1).cgColor
    }

    private static func disc(_ cg: CGContext, _ x: CGFloat, _ y: CGFloat, _ r: CGFloat, _ color: CGColor) {
        cg.setFillColor(color)
        cg.fillEllipse(in: CGRect(x: x - r, y: y - r, width: 2 * r, height: 2 * r))
    }

    private static func render(width: Int, height: Int, draw: (UIGraphicsImageRendererContext) -> Void) -> UIImage {
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: width, height: height), format: format)
        return renderer.image(actions: draw)
    }

    /// RGB bytes of an image whose pixel size is its point size (scale 1).
    private static func packed(_ image: UIImage) -> PreparedImage? {
        guard let cgImage = image.cgImage else { return nil }
        let width = cgImage.width
        let height = cgImage.height
        guard width > 0, height > 0 else { return nil }
        var rgbx = [UInt8](repeating: 0, count: width * height * 4)
        let space = CGColorSpace(name: CGColorSpace.sRGB) ?? CGColorSpaceCreateDeviceRGB()
        let info = CGImageAlphaInfo.noneSkipLast.rawValue
        let drawn: Bool = rgbx.withUnsafeMutableBytes { (buffer: UnsafeMutableRawBufferPointer) -> Bool in
            guard let context = CGContext(data: buffer.baseAddress, width: width, height: height, bitsPerComponent: 8,
                                          bytesPerRow: width * 4, space: space, bitmapInfo: info) else { return false }
            context.draw(cgImage, in: CGRect(x: 0, y: 0, width: width, height: height))
            return true
        }
        guard drawn else { return nil }
        var rgb = [UInt8](repeating: 0, count: width * height * 3)
        for i in 0..<(width * height) {
            rgb[i * 3] = rgbx[i * 4]
            rgb[i * 3 + 1] = rgbx[i * 4 + 1]
            rgb[i * 3 + 2] = rgbx[i * 4 + 2]
        }
        return PreparedImage(rgb: rgb, width: Int32(width), height: Int32(height), preview: image)
    }
}

/// The prompt files Android ships in `assets/foodvision` (the app bundle's
/// `foodvision` folder): the same model input on both platforms.
struct VisionPrompt {
    let system: String
    let user: String
    let grammar: String
    let textSystem: String
    let textUser: String

    private static let version = "v1"

    static func load(bundle: Bundle = .main) -> VisionPrompt? {
        func read(_ name: String, _ ext: String, trim: Bool) -> String? {
            guard let url = bundle.url(forResource: "\(name)-\(version)", withExtension: ext, subdirectory: "foodvision"),
                  let text = try? String(contentsOf: url, encoding: .utf8) else { return nil }
            return trim ? text.trimmingCharacters(in: .whitespacesAndNewlines) : text
        }
        guard let system = read("system", "txt", trim: true),
              let user = read("user", "txt", trim: true),
              let grammar = read("grammar", "gbnf", trim: false),
              let textSystem = read("text-system", "txt", trim: true),
              let textUser = read("text-user", "txt", trim: true) else { return nil }
        return VisionPrompt(system: system, user: user, grammar: grammar, textSystem: textSystem, textUser: textUser)
    }
}
