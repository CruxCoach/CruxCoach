import SwiftUI
import Vision
import VisionKit

/// Full-screen barcode scanner for food packs (Android `BarcodeScannerDialog`):
/// VisionKit's live scanner reads EAN-13, EAN-8 and UPC-E (UPC-A arrives as
/// EAN-13 with a leading 0); nothing is stored or sent, the code is looked up
/// in the on-device product database by the caller.
struct BarcodeScannerSheet: View {
    let onCode: (String) -> Void
    let onCancel: () -> Void

    var body: some View {
        NavigationStack {
            Group {
                if DataScannerViewController.isSupported && DataScannerViewController.isAvailable {
                    BarcodeScannerView(onCode: onCode)
                        .ignoresSafeArea()
                } else {
                    // No camera permission, or a device without the live scanner.
                    ContentUnavailableView(LI("nutrition_scan_unavailable"), systemImage: "barcode.viewfinder",
                                           description: Text(LI("nutrition_scan_unavailable_hint")))
                }
            }
            .navigationTitle(L("trf_scan"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(L("tr_action_cancel"), action: onCancel) }
            }
        }
    }
}

struct BarcodeScannerView: UIViewControllerRepresentable {
    let onCode: (String) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(onCode: onCode) }

    func makeUIViewController(context: Context) -> DataScannerViewController {
        let scanner = DataScannerViewController(
            recognizedDataTypes: [.barcode(symbologies: [.ean13, .ean8, .upce])],
            qualityLevel: .balanced,
            recognizesMultipleItems: false,
            isHighFrameRateTrackingEnabled: false,
            isPinchToZoomEnabled: true,
            isGuidanceEnabled: true,
            isHighlightingEnabled: true
        )
        scanner.delegate = context.coordinator
        return scanner
    }

    func updateUIViewController(_ controller: DataScannerViewController, context: Context) {
        // Scanning starts once the controller is on screen; it stops for good after the first code.
        if !context.coordinator.started {
            context.coordinator.started = true
            try? controller.startScanning()
        }
    }

    static func dismantleUIViewController(_ controller: DataScannerViewController, coordinator: Coordinator) {
        controller.stopScanning()
    }

    final class Coordinator: NSObject, DataScannerViewControllerDelegate {
        private let onCode: (String) -> Void
        private var delivered = false
        var started = false

        init(onCode: @escaping (String) -> Void) {
            self.onCode = onCode
        }

        func dataScanner(_ dataScanner: DataScannerViewController, didAdd addedItems: [RecognizedItem], allItems: [RecognizedItem]) {
            guard !delivered else { return }
            for item in addedItems {
                if case .barcode(let barcode) = item, let value = barcode.payloadStringValue, !value.isEmpty {
                    delivered = true
                    dataScanner.stopScanning()
                    onCode(value)
                    return
                }
            }
        }
    }
}
