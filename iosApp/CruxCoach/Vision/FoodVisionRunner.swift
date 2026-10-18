import CruxCoachCore
import Foundation
import os

/// What the core needs to know about the phone before it offers a model
/// (Android `DeviceFactsReader`). The engine is compiled for ARMv8.2 with dot
/// product and FP16 arithmetic: the two sysctl flags say whether this CPU has
/// them, and a missing flag counts as "no".
enum VisionDeviceFacts {
    static var physicalMemoryBytes: Int64 { Int64(ProcessInfo.processInfo.physicalMemory) }
    static var activeProcessors: Int32 { Int32(ProcessInfo.processInfo.activeProcessorCount) }
    static var dotProduct: Bool { flag("hw.optional.arm.FEAT_DotProd") }
    static var halfPrecision: Bool { flag("hw.optional.arm.FEAT_FP16") }

    /// What iOS still lets this app use before it ends it; 0 when there is no limit (the simulator).
    static func availableMemoryBytes() -> Int64 {
        Int64(os_proc_available_memory())
    }

    private static func flag(_ name: String) -> Bool {
        var value: Int32 = 0
        var size = MemoryLayout<Int32>.size
        guard sysctlbyname(name, &value, &size, nil, 0) == 0 else { return false }
        return value == 1
    }
}

/// The core's `FoodVisionHost` over `FoodVisionEngine`: one serial queue, the
/// model stays loaded between photos (as in Android's ":vision" process) until
/// `unload()`. The chosen photo is handed over with `setPhoto` right before the
/// core asks for a run, and dropped once the run has it. A typed meal runs the
/// same engine without an image (`runText`).
final class FoodVisionRunner: NSObject, FoodVisionHost {
    /// What one run feeds the engine.
    private enum Request {
        case probe
        case photo(PreparedImage?)
        /// The typed meal, already cleaned and capped by the core (`MealTextVision.forMeal`).
        case text(String)
    }

    private let queue = DispatchQueue(label: "org.cruxcoach.foodvision", qos: .userInitiated)
    private let lock = NSLock()
    // Guarded by `lock`.
    private var photo: PreparedImage?
    private var current: FoodVisionEngine?
    private var generation = 0
    private var cancelledGeneration = -1
    // Only touched on `queue`.
    private var engine: FoodVisionEngine?
    private var loadedKey = ""
    private var prompt: VisionPrompt?

    func setPhoto(_ image: PreparedImage?) {
        lock.lock()
        photo = image
        lock.unlock()
    }

    func run(modelPath: String, projectorPath: String, threads: Int32, imageKind: String, maxTokens: Int32,
             onResult: @escaping (String) -> Void) {
        lock.lock()
        generation += 1
        let ticket = generation
        let image: PreparedImage? = imageKind == "photo" ? photo : nil
        if imageKind == "photo" { photo = nil }
        lock.unlock()
        let request: Request = imageKind == "probe" ? .probe : .photo(image)
        enqueue(modelPath: modelPath, projectorPath: projectorPath, threads: threads, request: request,
                maxTokens: maxTokens, ticket: ticket, onResult: onResult)
    }

    func runText(modelPath: String, projectorPath: String, threads: Int32, meal: String, maxTokens: Int32,
                 onResult: @escaping (String) -> Void) {
        lock.lock()
        generation += 1
        let ticket = generation
        lock.unlock()
        enqueue(modelPath: modelPath, projectorPath: projectorPath, threads: threads, request: .text(meal),
                maxTokens: maxTokens, ticket: ticket, onResult: onResult)
    }

    private func enqueue(modelPath: String, projectorPath: String, threads: Int32, request: Request,
                         maxTokens: Int32, ticket: Int, onResult: @escaping (String) -> Void) {
        queue.async { [weak self] in
            let reply: String
            if let self {
                reply = self.runNow(modelPath: modelPath, projectorPath: projectorPath, threads: threads,
                                    request: request, maxTokens: maxTokens, ticket: ticket)
            } else {
                reply = FoodVisionRunner.failure("not_loaded")
            }
            DispatchQueue.main.async { onResult(reply) }
        }
    }

    func cancel() {
        lock.lock()
        cancelledGeneration = generation
        let running = current
        lock.unlock()
        running?.cancel()
    }

    func unload() {
        queue.async { [weak self] in
            self?.engine = nil
            self?.loadedKey = ""
        }
    }

    func availableMemoryBytes() -> Int64 {
        VisionDeviceFacts.availableMemoryBytes()
    }

    private func isCancelled(_ ticket: Int) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return cancelledGeneration >= ticket
    }

    private func setCurrent(_ value: FoodVisionEngine?) {
        lock.lock()
        current = value
        lock.unlock()
    }

    private func runNow(modelPath: String, projectorPath: String, threads: Int32, request: Request,
                        maxTokens: Int32, ticket: Int) -> String {
        guard FoodVisionEngine.isAvailable else { return Self.failure("engine_not_built") }
        if prompt == nil { prompt = VisionPrompt.load() }
        guard let prompt else { return Self.failure("prompt_missing") }
        let key = "\(modelPath)|\(projectorPath)|\(threads)"
        if engine == nil || loadedKey != key {
            engine = nil
            let loaded = FoodVisionEngine.load(modelPath: modelPath, projectorPath: projectorPath, threads: threads)
            guard let fresh = loaded.engine else { return Self.failure(loaded.error) }
            engine = fresh
            loadedKey = key
        }
        // Loading takes seconds and cannot be interrupted; a cancel meanwhile still counts.
        if isCancelled(ticket) { return Self.failure("cancelled") }
        guard let engine else { return Self.failure("not_loaded") }
        let input: PreparedImage?
        switch request {
        case .probe:
            input = PhotoInput.probeImage()
        case .photo(let image):
            input = image
        case .text(let meal):
            setCurrent(engine)
            defer { setCurrent(nil) }
            if isCancelled(ticket) { return Self.failure("cancelled") }
            // No pixels: the C bridge passes no image, and food_vision.cpp builds
            // the prompt without the media marker, as Android's text-only request.
            let user: String = prompt.textUser.replacingOccurrences(of: "{meal}", with: meal)
            return engine.run(rgb: [], width: 0, height: 0,
                              system: prompt.textSystem, user: user, grammar: prompt.grammar, maxTokens: maxTokens)
        }
        guard let input else { return Self.failure("invalid_image") }
        setCurrent(engine)
        defer { setCurrent(nil) }
        if isCancelled(ticket) { return Self.failure("cancelled") }
        return engine.run(rgb: input.rgb, width: input.width, height: input.height,
                          system: prompt.system, user: prompt.user, grammar: prompt.grammar, maxTokens: maxTokens)
    }

    private static func failure(_ code: String) -> String {
        let safe = code.filter { $0.isLetter || $0.isNumber || $0 == "_" }
        return "{\"ok\":false,\"error\":\"\(safe)\"}"
    }
}
