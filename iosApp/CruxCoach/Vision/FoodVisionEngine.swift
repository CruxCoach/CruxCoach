import Foundation
#if canImport(CruxVision)
import CruxVision
#endif

/// Swift side of the on-device food photo engine: Android's `food_vision.cpp`
/// over llama.cpp + libmtmd, reached through the C bridge in `iosApp/Vision`.
/// It is linked only when the project is generated from `project-vision.yml`
/// (which adds `CruxVision.xcframework`); without it `isAvailable` is false and
/// nothing loads. Calls block for seconds: never on the main thread.
final class FoodVisionEngine {
    /// Same limits as Android's `VisionProtocol`.
    static let contextTokens: Int32 = 2048
    static let imageMaxTokens: Int32 = 512
    static let maxTokens: Int32 = 384

    static var isAvailable: Bool {
        #if canImport(CruxVision)
        return true
        #else
        return false
        #endif
    }

    private let handle: OpaquePointer

    private init(handle: OpaquePointer) {
        self.handle = handle
    }

    deinit {
        #if canImport(CruxVision)
        crux_vision_free(handle)
        #endif
    }

    /// Loads the weights and the vision projector. On failure the engine is nil
    /// and `error` carries the engine's code ("model_load_failed", …).
    static func load(modelPath: String, projectorPath: String, threads: Int32) -> (engine: FoodVisionEngine?, error: String) {
        #if canImport(CruxVision)
        var buffer = [CChar](repeating: 0, count: 96)
        let capacity = Int32(buffer.count)
        let session: OpaquePointer? = crux_vision_load(modelPath, projectorPath, threads, contextTokens, imageMaxTokens, &buffer, capacity)
        guard let session else {
            let bytes: [UInt8] = buffer.prefix { $0 != 0 }.map { UInt8(bitPattern: $0) }
            let code = String(decoding: bytes, as: UTF8.self)
            return (nil, code.isEmpty ? "model_load_failed" : code)
        }
        return (FoodVisionEngine(handle: session), "")
        #else
        return (nil, "engine_not_built")
        #endif
    }

    /// Runs one request and returns the engine's JSON envelope, the same as
    /// Android's `nativeRun`: ok, error, text, promptTokens, outputTokens,
    /// imageMs, generateMs. An empty `rgb` is a text-only request.
    func run(rgb: [UInt8], width: Int32, height: Int32, system: String, user: String, grammar: String, maxTokens: Int32) -> String {
        #if canImport(CruxVision)
        let session = handle
        let result: UnsafeMutablePointer<CChar>? = rgb.withUnsafeBufferPointer { (pixels: UnsafeBufferPointer<UInt8>) -> UnsafeMutablePointer<CChar>? in
            crux_vision_run(session, pixels.baseAddress, Int64(pixels.count), width, height, system, user, grammar, maxTokens)
        }
        guard let result else { return "{\"ok\":false,\"error\":\"out_of_memory\"}" }
        defer { crux_vision_string_free(result) }
        return String(cString: result)
        #else
        return "{\"ok\":false,\"error\":\"engine_not_built\"}"
        #endif
    }

    /// Stops a running `run` at the next token; safe from any thread.
    func cancel() {
        #if canImport(CruxVision)
        crux_vision_cancel(handle)
        #endif
    }
}
