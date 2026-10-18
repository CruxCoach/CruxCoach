import CruxCoachCore
import UIKit
import XCTest
@testable import CruxCoach

/// The meal photo path on iOS: image → RGB as Android's `PhotoInput`, and one
/// real run of Android's native engine (llama.cpp b11396 + libmtmd) with
/// Android's prompt and grammar, parsed by the shared `FoodVisionParser`.
///
/// The engine test needs a build generated from `project-vision.yml` and a
/// small public vision model named by CRUX_VISION_MODEL / CRUX_VISION_MMPROJ
/// (the CI `vision` job passes them, with CRUX_VISION_REQUIRED=1 so a missing
/// engine or model fails instead of skipping). It proves build, link, engine
/// and parser on iOS; recognition quality and the speed of the real 2B model on
/// a phone are not tested here.
final class FoodVisionEngineTests: XCTestCase {

    private func pixel(_ image: PreparedImage, _ x: Int, _ y: Int) -> [UInt8] {
        let i = (y * Int(image.width) + x) * 3
        return [image.rgb[i], image.rgb[i + 1], image.rgb[i + 2]]
    }

    func testProbeImageIsAndroidsDrawnPlateAsPackedRgb() throws {
        let probe = try XCTUnwrap(PhotoInput.probeImage())
        XCTAssertEqual(probe.width, 640)
        XCTAssertEqual(probe.height, 480)
        XCTAssertEqual(probe.rgb.count, 640 * 480 * 3)
        XCTAssertEqual(pixel(probe, 2, 2), [196, 160, 120], "table colour, R G B order")
        XCTAssertEqual(pixel(probe, 320, 50), [255, 255, 255], "plate")
        XCTAssertEqual(pixel(probe, 400, 220), [60, 140, 60], "green disc")
        XCTAssertEqual(pixel(probe, 330, 340), [200, 120, 60], "orange disc")
    }

    func testPhotosAreScaledToAtMost640PixelsAndNeverUp() throws {
        func jpeg(_ width: Int, _ height: Int) -> Data {
            let format = UIGraphicsImageRendererFormat()
            format.scale = 1
            let renderer = UIGraphicsImageRenderer(size: CGSize(width: width, height: height), format: format)
            return renderer.jpegData(withCompressionQuality: 0.9) { context in
                UIColor(red: 0.2, green: 0.6, blue: 0.3, alpha: 1).setFill()
                context.fill(CGRect(x: 0, y: 0, width: width, height: height))
            }
        }
        let large = try XCTUnwrap(PhotoInput.prepare(data: jpeg(2000, 1000)))
        XCTAssertEqual(large.width, 640)
        XCTAssertEqual(large.height, 320)
        XCTAssertEqual(large.rgb.count, 640 * 320 * 3)
        let middle = pixel(large, 320, 160)
        XCTAssertEqual(Double(middle[1]), 153, accuracy: 6, "green survives decoding and scaling")

        let small = try XCTUnwrap(PhotoInput.prepare(data: jpeg(300, 200)))
        XCTAssertEqual(small.width, 300)
        XCTAssertEqual(small.height, 200)
        XCTAssertNil(PhotoInput.prepare(data: Data([0x00, 0x01, 0x02])), "not an image")
    }

    func testTheEngineAnswersAPhotoWithJsonTheSharedParserReads() throws {
        let env = ProcessInfo.processInfo.environment
        let required = env["CRUX_VISION_REQUIRED"] == "1"
        guard FoodVisionEngine.isAvailable else {
            if required { XCTFail("CruxVision is not linked into this build") }
            throw XCTSkip("built without the vision engine (project.yml)")
        }
        guard let modelPath = env["CRUX_VISION_MODEL"], let projectorPath = env["CRUX_VISION_MMPROJ"] else {
            if required { XCTFail("CRUX_VISION_MODEL / CRUX_VISION_MMPROJ not passed to the test") }
            throw XCTSkip("no test model given")
        }
        let prompt = try XCTUnwrap(VisionPrompt.load(), "foodvision prompt files missing from the app bundle")
        XCTAssertTrue(prompt.grammar.contains("root"), "the GBNF has a root rule")
        let probe = try XCTUnwrap(PhotoInput.probeImage())

        let loaded = FoodVisionEngine.load(modelPath: modelPath, projectorPath: projectorPath, threads: 2)
        let engine = try XCTUnwrap(loaded.engine, "load failed: \(loaded.error)")

        let envelope = engine.run(rgb: probe.rgb, width: probe.width, height: probe.height,
                                  system: prompt.system, user: prompt.user, grammar: prompt.grammar,
                                  maxTokens: FoodVisionEngine.maxTokens)
        print("VISION_RESULT \(envelope)")
        if let report = env["CRUX_VISION_REPORT"] {
            // CI turns this into a public annotation (timings and the model's answer).
            try? Data(envelope.utf8).write(to: URL(fileURLWithPath: report))
        }
        let reply = try XCTUnwrap(try JSONSerialization.jsonObject(with: Data(envelope.utf8)) as? [String: Any])
        XCTAssertEqual(reply["ok"] as? Bool, true, envelope)
        XCTAssertEqual(reply["error"] as? String, "", envelope)
        let promptTokens = (reply["promptTokens"] as? Int) ?? 0
        let outputTokens = (reply["outputTokens"] as? Int) ?? 0
        XCTAssertGreaterThan(promptTokens, 64, "the prompt holds the image tokens")
        XCTAssertGreaterThan(outputTokens, 0)

        // The grammar forces {"items":[…]}: the answer is JSON on its own.
        let text = try XCTUnwrap(reply["text"] as? String)
        let answer = try XCTUnwrap(try JSONSerialization.jsonObject(with: Data(text.utf8)) as? [String: Any], text)
        let items = try XCTUnwrap(answer["items"] as? [Any], text)
        XCTAssertLessThanOrEqual(items.count, 8)

        let foods = FoodVisionParser.shared.parse(text: text)
        XCTAssertLessThanOrEqual(foods.count, items.count)
        for food in foods {
            XCTAssertFalse(food.nameDe.isEmpty)
            XCTAssertGreaterThan(food.grams, 0)
            XCTAssertLessThanOrEqual(food.grams, 2000)
        }

        // Android's input check: pixels that do not match the size are refused.
        let wrong = engine.run(rgb: [1, 2, 3, 4, 5], width: probe.width, height: probe.height,
                               system: prompt.system, user: prompt.user, grammar: prompt.grammar, maxTokens: 8)
        XCTAssertTrue(wrong.contains("\"error\":\"invalid_image\""), wrong)
    }

    /// The path the app takes: the core's FoodVisionHost (FoodVisionRunner) with
    /// the drawn plate, answered on the main thread and read by the core's
    /// envelope parser; then a run cancelled right after it was asked for.
    func testTheRunnerServesTheCoreAndHonoursCancel() throws {
        let env = ProcessInfo.processInfo.environment
        let required = env["CRUX_VISION_REQUIRED"] == "1"
        guard FoodVisionEngine.isAvailable, let modelPath = env["CRUX_VISION_MODEL"], let projectorPath = env["CRUX_VISION_MMPROJ"] else {
            if required { XCTFail("engine or test model missing") }
            throw XCTSkip("built without the vision engine or without a test model")
        }
        // The engine is compiled for these two features; the simulator's Apple-silicon host has them.
        XCTAssertTrue(VisionDeviceFacts.dotProduct, "hw.optional.arm.FEAT_DotProd")
        XCTAssertTrue(VisionDeviceFacts.halfPrecision, "hw.optional.arm.FEAT_FP16")
        XCTAssertGreaterThan(VisionDeviceFacts.physicalMemoryBytes, 0)

        let runner = FoodVisionRunner()
        let probed = expectation(description: "probe answered")
        var envelope = ""
        runner.run(modelPath: modelPath, projectorPath: projectorPath, threads: 2, imageKind: "probe", maxTokens: 32) { reply in
            XCTAssertTrue(Thread.isMainThread)
            envelope = reply
            probed.fulfill()
        }
        wait(for: [probed], timeout: 600)
        let outcome = VisionOutcome.companion.parse(envelope: envelope)
        XCTAssertTrue(outcome.ok, envelope)
        XCTAssertGreaterThan(outcome.outputTokens, 0)
        XCTAssertLessThanOrEqual(outcome.outputTokens, 32)
        XCTAssertGreaterThan(outcome.imageMs, 0)

        let probe = try XCTUnwrap(PhotoInput.probeImage())
        runner.setPhoto(probe)
        let cancelled = expectation(description: "cancelled run answered")
        var cancelReply = ""
        runner.run(modelPath: modelPath, projectorPath: projectorPath, threads: 2, imageKind: "photo", maxTokens: 384) { reply in
            cancelReply = reply
            cancelled.fulfill()
        }
        runner.cancel()
        wait(for: [cancelled], timeout: 600)
        XCTAssertEqual(VisionOutcome.companion.parse(envelope: cancelReply).error, "cancelled", cancelReply)
        runner.unload()
    }

    func testAMissingModelFileIsAnErrorCodeNotACrash() throws {
        guard FoodVisionEngine.isAvailable else { throw XCTSkip("built without the vision engine (project.yml)") }
        let loaded = FoodVisionEngine.load(modelPath: "/nonexistent/model.gguf", projectorPath: "/nonexistent/mmproj.gguf", threads: 1)
        XCTAssertNil(loaded.engine)
        XCTAssertEqual(loaded.error, "model_load_failed")
    }
}
