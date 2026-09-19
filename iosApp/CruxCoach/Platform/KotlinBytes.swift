import CruxCoachCore
import Foundation

extension KotlinByteArray {
    /// Copies into Swift-owned memory. Empty-safe.
    func toData() -> Data {
        let count = Int(size)
        guard count > 0 else { return Data() }
        var data = Data(count: count)
        data.withUnsafeMutableBytes { (buffer: UnsafeMutableRawBufferPointer) in
            let bytes = buffer.bindMemory(to: Int8.self)
            for index in 0..<count { bytes[index] = get(index: Int32(index)) }
        }
        return data
    }
}

extension Data {
    func toKotlinByteArray() -> KotlinByteArray {
        let array = KotlinByteArray(size: Int32(count))
        withUnsafeBytes { (buffer: UnsafeRawBufferPointer) in
            let bytes = buffer.bindMemory(to: Int8.self)
            for index in 0..<count { array.set(index: Int32(index), value: bytes[index]) }
        }
        return array
    }
}
