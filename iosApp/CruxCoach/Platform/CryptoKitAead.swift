import CruxCoachCore
import CryptoKit
import Foundation

/// AES-256-GCM with a 12-byte nonce and 16-byte tag, no AAD: the Android backup format.
final class CryptoKitAead: AeadCipher {
    func aesGcmSeal(key: KotlinByteArray, nonce: KotlinByteArray, plaintext: KotlinByteArray) -> KotlinByteArray? {
        guard key.size == 32, nonce.size == 12,
              let gcmNonce = try? AES.GCM.Nonce(data: nonce.toData()),
              let box = try? AES.GCM.seal(plaintext.toData(), using: SymmetricKey(data: key.toData()), nonce: gcmNonce)
        else { return nil }
        return (box.ciphertext + box.tag).toKotlinByteArray()
    }

    func aesGcmOpen(key: KotlinByteArray, nonce: KotlinByteArray, ciphertextAndTag: KotlinByteArray) -> KotlinByteArray? {
        let sealed = ciphertextAndTag.toData()
        guard key.size == 32, nonce.size == 12, sealed.count >= 16,
              let gcmNonce = try? AES.GCM.Nonce(data: nonce.toData()),
              let box = try? AES.GCM.SealedBox(nonce: gcmNonce,
                                               ciphertext: sealed.dropLast(16),
                                               tag: sealed.suffix(16)),
              let plain = try? AES.GCM.open(box, using: SymmetricKey(data: key.toData()))
        else { return nil }
        return plain.toKotlinByteArray()
    }
}
