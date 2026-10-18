import CruxCoachCore
import PhotosUI
import SwiftUI
import UIKit

/// "Log a meal from a photo" (Android `FoodPhotoSheet`): the one-time model
/// download the athlete starts, a timed test run, then camera or library →
/// the on-device engine → the same review list as a typed meal. The photo
/// never leaves the phone and is not stored.
struct MealPhotoSheet: View {
    let core: AppCore
    let dayIso: String
    let mealCode: String
    let unitsCode: String
    let onDone: () -> Void
    @State private var host: ScreenHost<MealPhotoScreenModel, MealPhotoUi>?
    @State private var reviewHost: ScreenHost<MealTextScreenModel, MealTextUi>?
    @State private var runner: FoodVisionRunner?
    @State private var unavailable = false
    @State private var preview: UIImage?
    @State private var pickerItem: PhotosPickerItem?
    @State private var showCamera = false
    @State private var allowMobile = false

    var body: some View {
        NavigationStack {
            content
                .navigationTitle(L("fvp_title"))
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button(L("tr_action_cancel"), action: onDone) }
                }
        }
        .task { start() }
        .onChange(of: pickerItem) { _, item in load(item) }
        .sheet(isPresented: $showCamera) {
            CameraCapture(onImage: { image in
                showCamera = false
                analyze(image)
            }, onCancel: { showCamera = false })
            .ignoresSafeArea()
        }
    }

    /// The tile state before the sheet opens: "supported", a reason code, or "too_slow".
    static func support(_ core: AppCore) -> String {
        core.foodPhotoSupport(physicalMemoryBytes: VisionDeviceFacts.physicalMemoryBytes,
                              dotProduct: VisionDeviceFacts.dotProduct,
                              halfPrecision: VisionDeviceFacts.halfPrecision,
                              activeProcessors: VisionDeviceFacts.activeProcessors,
                              engineBuilt: FoodVisionEngine.isAvailable)
    }

    /// Why this phone or build gets no photo recognition (Android `FoodPhotoUnavailableDialog`).
    static func reason(_ code: String) -> String {
        switch code {
        case "no_engine": return LI("nutrition_photo_no_engine")
        case "low_ram":
            let gb: Double = (Double(VisionDeviceFacts.physicalMemoryBytes) / 100_000_000).rounded() / 10
            let text: String = gb.formatted(.number.precision(.fractionLength(1)))
            return L("fvp_unavailable_ram", 6, text)
        case "cpu": return L("fvp_unavailable_cpu")
        case "abi": return L("fvp_unavailable_abi")
        default: return L("fvp_unavailable_short")
        }
    }

    static func size(_ bytes: Int64) -> String {
        ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)
    }

    @ViewBuilder
    private var content: some View {
        if let host {
            phaseView(model: host.model, ui: host.state)
        } else if unavailable {
            ContentUnavailableView(LI("nutrition_unavailable"), systemImage: "camera")
        } else {
            ProgressView()
        }
    }

    private func start() {
        guard host == nil, !unavailable else { return }
        let engine = runner ?? FoodVisionRunner()
        runner = engine
        guard let model = core.makeMealPhotoScreen(
            dayIso: dayIso, mealCode: mealCode, unitsCode: unitsCode,
            physicalMemoryBytes: VisionDeviceFacts.physicalMemoryBytes,
            dotProduct: VisionDeviceFacts.dotProduct,
            halfPrecision: VisionDeviceFacts.halfPrecision,
            activeProcessors: VisionDeviceFacts.activeProcessors,
            engineBuilt: FoodVisionEngine.isAvailable,
            host: engine
        ) else {
            unavailable = true
            return
        }
        let review: MealTextScreenModel = model.review
        reviewHost = ScreenHost(model: review, initial: review.currentState,
                                subscribe: { m, onState in m.watch(onState: onState) })
        host = ScreenHost(model: model, initial: model.currentState,
                          subscribe: { m, onState in m.watch(onState: onState) },
                          onClose: { m in m.close() })
        model.onOpen()
    }

    // MARK: Phases

    @ViewBuilder
    private func phaseView(model: MealPhotoScreenModel, ui: MealPhotoUi) -> some View {
        switch ui.phase {
        case "failed":
            errorPane(model: model, ui: ui)
        case "probing":
            Form { VisionWaitPane(label: L("fvp_probing"), startedMs: ui.phaseStartedMs, estimateMs: -1, onCancel: { model.cancel() }) }
        case "analyzing":
            analyzingPane(model: model, ui: ui)
        case "review":
            reviewPane(ui: ui)
        case "saved":
            ProgressView().onAppear { onDone() }
        default:
            idlePane(model: model, ui: ui)
        }
    }

    @ViewBuilder
    private func idlePane(model: MealPhotoScreenModel, ui: MealPhotoUi) -> some View {
        if ui.support != "supported" {
            unsupportedPane(model: model, ui: ui)
        } else {
            switch ui.model {
            case "downloading":
                downloadPane(model: model, ui: ui)
            case "verifying":
                Form { VisionWaitPane(label: L("fvp_verifying"), startedMs: 0, estimateMs: -1, onCancel: nil) }
            case "ready":
                readyPane(model: model, ui: ui)
            default:
                setupPane(model: model, ui: ui)
            }
        }
    }

    private func unsupportedPane(model: MealPhotoScreenModel, ui: MealPhotoUi) -> some View {
        Form {
            Section {
                Text(Self.reason(ui.support))
                Text(L("fvp_unavailable_alternatives")).font(.footnote).foregroundStyle(.secondary)
            }
            if ui.model != "missing" {
                Section {
                    Button(L("fvp_remove_model", Self.size(ui.downloadBytes)), role: .destructive) { model.removeModel() }
                }
            }
        }
    }

    private func setupPane(model: MealPhotoScreenModel, ui: MealPhotoUi) -> some View {
        let size: String = Self.size(ui.downloadBytes)
        return Form {
            Section {
                Text(L("fvp_setup_text", ui.modelName, size))
                Text(L("fvp_setup_estimate"))
                Text(L("fvp_setup_source")).font(.footnote).foregroundStyle(.secondary)
                Text(LI("nutrition_photo_background_download")).font(.footnote).foregroundStyle(.secondary)
            }
            Section {
                Toggle(L("fvp_allow_mobile"), isOn: $allowMobile)
                Button(L("fvp_download", size)) { model.startDownload(allowMobile: allowMobile) }
            }
        }
    }

    private func downloadPane(model: MealPhotoScreenModel, ui: MealPhotoUi) -> some View {
        let total: Int64 = max(ui.downloadBytes, 1)
        let fraction: Double = min(max(Double(ui.doneBytes) / Double(total), 0), 1)
        return Form {
            Section {
                Text(L("fvp_downloading", Self.size(ui.doneBytes), Self.size(ui.downloadBytes)))
                ProgressView(value: fraction)
                if ui.waiting {
                    Text(L("fvp_download_waiting")).font(.footnote)
                }
                Text(LI("nutrition_photo_background_download")).font(.footnote).foregroundStyle(.secondary)
            }
            Section {
                Button(L("tr_action_cancel"), role: .destructive) { model.removeModel() }
            }
        }
        // A locked screen suspends the app and with it the transfer.
        .onAppear { UIApplication.shared.isIdleTimerDisabled = true }
        .onDisappear { UIApplication.shared.isIdleTimerDisabled = false }
    }

    private func readyPane(model: MealPhotoScreenModel, ui: MealPhotoUi) -> some View {
        Form {
            Section {
                if ui.hasProbe {
                    Text(L("fvp_probe_estimate", Int(ui.probeSeconds)))
                }
                Text(L("fvp_on_device")).font(.footnote).foregroundStyle(.secondary)
            }
            photoButtons(model: model, ui: ui)
            Section {
                Button(L("fvp_remove_model", Self.size(ui.downloadBytes)), role: .destructive) { model.removeModel() }
            }
        }
    }

    @ViewBuilder
    private func photoButtons(model: MealPhotoScreenModel, ui: MealPhotoUi) -> some View {
        if ui.canTakePhotos {
            Section {
                if UIImagePickerController.isSourceTypeAvailable(.camera) {
                    Button { showCamera = true } label: { Label(L("fvp_take_photo"), systemImage: "camera") }
                }
                PhotosPicker(selection: $pickerItem, matching: .images) {
                    Label(L("fvp_pick_photo"), systemImage: "photo.on.rectangle")
                }
            }
        } else if !ui.hasProbe {
            Section {
                Button(L("fvp_run_probe")) { model.probe() }
            }
        } else if ui.tooSlow {
            Section {
                Text(L("fvp_unavailable_slow", Int(ui.probeSeconds)))
            }
        }
    }

    private func analyzingPane(model: MealPhotoScreenModel, ui: MealPhotoUi) -> some View {
        Form {
            if let preview {
                Section {
                    Image(uiImage: preview).resizable().scaledToFill().frame(height: 160).clipped()
                        .accessibilityLabel(L("fvp_photo_description"))
                }
            }
            VisionWaitPane(label: L("fvp_analyzing"), startedMs: ui.phaseStartedMs, estimateMs: ui.estimateMs,
                           onCancel: { model.cancel() })
            Section {
                Text(L("fvp_on_device")).font(.footnote).foregroundStyle(.secondary)
            }
        }
    }

    @ViewBuilder
    private func reviewPane(ui: MealPhotoUi) -> some View {
        if let reviewHost {
            MealReviewForm(model: reviewHost.model, ui: reviewHost.state,
                           hint: L("fvp_review_hint", Int(ui.reviewSeconds)), preview: preview)
        } else {
            ProgressView()
        }
    }

    private func errorPane(model: MealPhotoScreenModel, ui: MealPhotoUi) -> some View {
        Form {
            Section {
                Text(Self.errorText(ui)).foregroundStyle(.red)
                Button(L("fvp_retry")) { model.dismissError() }
            }
        }
    }

    private static func errorText(_ ui: MealPhotoUi) -> String {
        switch ui.error {
        case "memory": return L("fvp_error_memory")
        case "image": return L("fvp_error_image")
        case "download": return L("fvp_error_download")
        case "corrupt": return L("fvp_error_corrupt")
        case "no_storage": return L("fvp_error_no_storage")
        case "space": return L("fvp_error_space", size(ui.neededBytes), size(ui.freeBytes))
        case "app_memory": return LI("nutrition_photo_app_memory")
        case "save": return LI("nutrition_photo_save_failed")
        default: return L("fvp_error_engine", ui.errorCode)
        }
    }

    // MARK: Photo input

    private func load(_ item: PhotosPickerItem?) {
        guard let item, let model = host?.model else { return }
        pickerItem = nil
        Task {
            let data: Data? = try? await item.loadTransferable(type: Data.self)
            let prepared: PreparedImage? = await Task.detached(priority: .userInitiated) {
                data.flatMap { PhotoInput.prepare(data: $0) }
            }.value
            submit(prepared, model: model)
        }
    }

    private func analyze(_ image: UIImage) {
        guard let model = host?.model else { return }
        Task {
            let prepared: PreparedImage? = await Task.detached(priority: .userInitiated) {
                PhotoInput.prepare(image: image)
            }.value
            submit(prepared, model: model)
        }
    }

    private func submit(_ prepared: PreparedImage?, model: MealPhotoScreenModel) {
        guard let prepared else {
            model.photoUnreadable()
            return
        }
        preview = prepared.preview
        runner?.setPhoto(prepared)
        model.analyzePhoto()
    }
}

/// Spinner, elapsed seconds and – with an estimate – a progress bar (Android `WaitPane`).
private struct VisionWaitPane: View {
    let label: String
    let startedMs: Int64
    let estimateMs: Int64
    let onCancel: (() -> Void)?

    var body: some View {
        Section {
            HStack(spacing: 12) {
                ProgressView()
                Text(label)
            }
            if startedMs > 0 {
                TimelineView(.periodic(from: .now, by: 1)) { context in
                    elapsed(now: context.date)
                }
            }
            if let onCancel {
                Button(L("tr_action_cancel"), action: onCancel)
            }
        }
    }

    @ViewBuilder
    private func elapsed(now: Date) -> some View {
        let started = Date(timeIntervalSince1970: Double(startedMs) / 1000)
        let seconds: Int = max(0, Int(now.timeIntervalSince(started)))
        VStack(alignment: .leading, spacing: 6) {
            if estimateMs > 0 {
                let fraction: Double = min(Double(seconds) * 1000 / Double(estimateMs), 0.97)
                ProgressView(value: fraction)
            }
            Text(L("fvp_elapsed", seconds)).font(.footnote).foregroundStyle(.secondary)
        }
    }
}

/// The system camera for one meal photo; the picture is handed over in memory
/// and not saved to the photo library.
private struct CameraCapture: UIViewControllerRepresentable {
    let onImage: (UIImage) -> Void
    let onCancel: () -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator(onImage: onImage, onCancel: onCancel)
    }

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.sourceType = .camera
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ controller: UIImagePickerController, context: Context) {}

    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let onImage: (UIImage) -> Void
        let onCancel: () -> Void

        init(onImage: @escaping (UIImage) -> Void, onCancel: @escaping () -> Void) {
            self.onImage = onImage
            self.onCancel = onCancel
        }

        func imagePickerController(_ picker: UIImagePickerController,
                                   didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
            if let image = info[.originalImage] as? UIImage {
                onImage(image)
            } else {
                onCancel()
            }
        }

        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
            onCancel()
        }
    }
}
