import CruxCoachCore
import Foundation
import libzstd

/// Streaming file-to-file zstd decompression with an output cap (decompression-bomb guard).
final class ZstdFileDecompressor: ZstdDecompressor {
    func decompressFile(sourcePath: String, destinationPath: String, maxOutputBytes: Int64) -> Int64 {
        guard let input = InputStream(fileAtPath: sourcePath),
              let output = OutputStream(toFileAtPath: destinationPath, append: false),
              let stream = ZSTD_createDStream() else { return -1 }
        defer { ZSTD_freeDStream(stream) }
        input.open(); output.open()
        defer { input.close(); output.close() }

        let inCapacity = ZSTD_DStreamInSize(), outCapacity = ZSTD_DStreamOutSize()
        let inBuffer = UnsafeMutablePointer<UInt8>.allocate(capacity: inCapacity)
        let outBuffer = UnsafeMutablePointer<UInt8>.allocate(capacity: outCapacity)
        defer { inBuffer.deallocate(); outBuffer.deallocate() }

        var written: Int64 = 0
        var lastResult = 0
        var sawInput = false
        func fail() -> Int64 { try? FileManager.default.removeItem(atPath: destinationPath); return -1 }

        while true {
            let read = input.read(inBuffer, maxLength: inCapacity)
            if read < 0 { return fail() }
            if read == 0 { break }
            sawInput = true
            var inState = ZSTD_inBuffer(src: inBuffer, size: read, pos: 0)
            while inState.pos < inState.size {
                var outState = ZSTD_outBuffer(dst: outBuffer, size: outCapacity, pos: 0)
                lastResult = ZSTD_decompressStream(stream, &outState, &inState)
                if ZSTD_isError(lastResult) != 0 { return fail() }
                written += Int64(outState.pos)
                if written > maxOutputBytes { return fail() }
                var offset = 0
                while offset < outState.pos {
                    let n = output.write(outBuffer + offset, maxLength: outState.pos - offset)
                    if n <= 0 { return fail() }
                    offset += n
                }
            }
        }
        // A non-zero result at end of input means the frame was truncated.
        if !sawInput || lastResult != 0 { return fail() }
        return written
    }
}
