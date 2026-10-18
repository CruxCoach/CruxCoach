import CoreImage
import CoreImage.CIFilterBuiltins
import CruxCoachCore
import SwiftUI
import UIKit

/// Android's gift flow (`PaymentBottomSheet` → `ZapAmountDialog` → `InvoiceDialog`, `NoWalletDialog`):
/// private Lightning or a public zap to the maintainer. The result is a BOLT 11 invoice for the
/// user's wallet; the app never pays and never claims a payment happened (Kotlin `ZapModel`).
struct ZapSheet: View {
    let core: AppCore
    @Environment(\.dismiss) private var dismiss
    @State private var host: ScreenHost<ZapModel, ZapUi>?

    var body: some View {
        NavigationStack {
            Group {
                if let host {
                    ZapStepView(model: host.model, ui: host.state)
                } else {
                    ProgressView()
                }
            }
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L("action_cancel")) { dismiss() }
                        .disabled(host?.state.isSending ?? false)
                }
            }
        }
        .interactiveDismissDisabled(host?.state.isSending ?? false)
        .task {
            guard host == nil else { return }
            let model = core.makeZapToMaintainer()
            host = ScreenHost(model: model, initial: model.currentState,
                              subscribe: { model, onState in model.watch(onState: onState) },
                              onClose: { model in model.close() })
        }
    }
}

/// One step at a time, each its own small view (keeps the type-checker fast).
private struct ZapStepView: View {
    let model: ZapModel
    let ui: ZapUi

    var body: some View {
        switch ui.stepCode {
        case "amount":
            ZapAmountForm(model: model, ui: ui)
        case "invoice":
            ZapInvoiceView(invoice: ui.invoice)
        default:
            ZapChoiceList(model: model)
        }
    }
}

/// Android `PaymentBottomSheet`: two Lightning options, the card option shown as not yet available.
private struct ZapChoiceList: View {
    let model: ZapModel

    var body: some View {
        List {
            Section {
                option(title: L("payment_lightning_private_title"),
                       subtitle: L("payment_lightning_private_subtitle")) { model.selectLightning(privately: true) }
                option(title: L("payment_lightning_zap_title"),
                       subtitle: L("payment_lightning_zap_subtitle")) { model.selectLightning(privately: false) }
            }
            Section {
                cardRow
            } footer: {
                Text(L("payment_gift_disclaimer"))
            }
        }
        .navigationTitle(L("payment_sheet_title"))
        .navigationBarTitleDisplayMode(.inline)
    }

    private func option(title: String, subtitle: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Text("\u{26A1}").font(.title2)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.body.bold()).foregroundStyle(.primary)
                    Text(subtitle).font(.subheadline).foregroundStyle(.secondary)
                }
                Spacer()
                Image(systemName: "chevron.right").foregroundStyle(.secondary)
            }
        }
    }

    private var cardRow: some View {
        HStack(spacing: 12) {
            Text("\u{20AC}").font(.title2)
            VStack(alignment: .leading, spacing: 2) {
                Text(L("payment_card_title")).font(.body.bold())
                Text(L("payment_coming_soon")).font(.subheadline)
            }
        }
        .foregroundStyle(.secondary)
        .opacity(0.5)
    }
}

/// Android `ZapAmountDialog`: preset chips (5k preselected), a custom amount, an optional message.
private struct ZapAmountForm: View {
    let model: ZapModel
    let ui: ZapUi
    @State private var selected: Int64 = 5_000
    @State private var custom = ""
    @State private var message = ""

    private var effectiveAmount: Int64 {
        custom.isEmpty ? selected : (Int64(custom) ?? 0)
    }

    var body: some View {
        Form {
            presetSection
            fieldSection
            sendSection
        }
        .navigationTitle(ui.isPrivate ? L("payment_zap_title_private") : L("payment_zap_title_public"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                Button(L("action_back")) { model.backToChoice() }.disabled(ui.isSending)
            }
        }
        .onAppear { selected = model.defaultAmount }
    }

    private var presets: [Int64] {
        let values: [Int64] = model.presetAmounts.map { $0.int64Value }
        return values
    }

    private var presetSection: some View {
        Section(L("payment_zap_amount")) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(presets, id: \.self) { amount in
                        chip(amount)
                    }
                }
            }
        }
    }

    private func chip(_ amount: Int64) -> some View {
        let isOn: Bool = custom.isEmpty && selected == amount
        let label: String = "\(amount / 1000)k sats"
        return Button(label) {
            selected = amount
            custom = ""
        }
        .buttonStyle(.bordered)
        .tint(isOn ? Color.accentColor : Color.secondary)
        .disabled(ui.isSending)
    }

    private var fieldSection: some View {
        Section {
            TextField(L("payment_custom_amount"), text: $custom)
                .keyboardType(.numberPad)
                .onChange(of: custom) { _, value in
                    let digits: String = String(value.filter { $0.isASCII && $0.isNumber }.prefix(15))
                    if digits != value { custom = digits }
                }
            TextField(ui.isPrivate ? L("payment_zap_message_private") : L("payment_zap_message_public"),
                      text: $message)
        }
        .disabled(ui.isSending)
    }

    @ViewBuilder
    private var sendSection: some View {
        Section {
            if ui.isSending {
                HStack(spacing: 12) {
                    ProgressView()
                    Text(L("payment_sending"))
                }
            } else {
                Button(L("action_send")) { model.requestInvoice(amountSats: effectiveAmount, message: message) }
                    .disabled(effectiveAmount <= 0)
            }
            if ui.errorCode != "none" {
                Text(ZapTexts.error(ui))
                    .font(.footnote)
                    .foregroundStyle(.red)
            }
        }
    }
}

/// Android `InvoiceDialog`: QR code, shortened invoice, copy, open in a wallet. Without a wallet,
/// Android's `NoWalletDialog` content follows below.
private struct ZapInvoiceView: View {
    let invoice: String
    @State private var qr: UIImage?
    @State private var copied = false
    @State private var noWallet = false

    var body: some View {
        List {
            qrSection
            actionSection
            if noWallet {
                ZapNoWalletSection()
            }
        }
        .navigationTitle(L("payment_invoice_title"))
        .navigationBarTitleDisplayMode(.inline)
        .task(id: invoice) { qr = ZapQrCode.image("lightning:" + invoice) }
    }

    private var shortInvoice: String {
        guard invoice.count > 40 else { return invoice }
        return String(invoice.prefix(30)) + "..." + String(invoice.suffix(10))
    }

    private var qrSection: some View {
        Section {
            VStack(spacing: 12) {
                if let qr {
                    Image(uiImage: qr)
                        .interpolation(.none)
                        .resizable()
                        .scaledToFit()
                        .frame(width: 220, height: 220)
                        .padding(8)
                        .background(Color.white)
                        .clipShape(RoundedRectangle(cornerRadius: 12))
                        .accessibilityLabel(L("payment_invoice_qr"))
                }
                Text(shortInvoice)
                    .font(.caption.monospaced())
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
            .frame(maxWidth: .infinity)
        } footer: {
            Text(LI("zap_invoice_hint"))
        }
    }

    private var actionSection: some View {
        Section {
            Button {
                UIPasteboard.general.string = invoice
                copied = true
            } label: {
                Label(copied ? L("payment_invoice_copied") : L("action_copy"), systemImage: "doc.on.doc")
            }
            Button {
                openWallet()
            } label: {
                Label("Wallet", systemImage: "arrow.up.forward.app")
            }
        }
    }

    private func openWallet() {
        guard let url = URL(string: "lightning:" + invoice), UIApplication.shared.canOpenURL(url) else {
            noWallet = true
            return
        }
        UIApplication.shared.open(url)
    }
}

/// Android `NoWalletDialog`: the same four recommended wallets.
private struct ZapWallet: Identifiable {
    let name: String
    let url: String
    var id: String { name }
}

private struct ZapNoWalletSection: View {
    private static let wallets: [ZapWallet] = [
        ZapWallet(name: "Phoenix", url: "https://phoenix.acinq.co"),
        ZapWallet(name: "Wallet of Satoshi", url: "https://www.walletofsatoshi.com"),
        ZapWallet(name: "Zeus", url: "https://zeusln.com"),
        ZapWallet(name: "Alby Go", url: "https://alby.com/go"),
    ]

    var body: some View {
        Section {
            Text(L("payment_no_wallet_message")).foregroundStyle(.secondary)
            Text(L("payment_recommended_wallets")).font(.body.bold())
            ForEach(Self.wallets) { wallet in
                walletLink(wallet)
            }
        } header: {
            Text(L("payment_no_wallet_title"))
        }
    }

    @ViewBuilder
    private func walletLink(_ wallet: ZapWallet) -> some View {
        if let url = URL(string: wallet.url) {
            Link(destination: url) {
                Label(wallet.name, systemImage: "arrow.up.right.square")
            }
        }
    }
}

enum ZapTexts {
    static func error(_ ui: ZapUi) -> String {
        switch ui.errorCode {
        case "noAddress": return LI("zap_error_no_address")
        case "payInfoUnavailable": return LI("zap_error_pay_info")
        case "outOfRange": return LI("zap_error_out_of_range", ui.minSats, ui.maxSats)
        case "noNostrSupport": return LI("zap_error_no_nostr")
        case "signingFailed": return LI("zap_error_signing")
        case "invoiceUnavailable": return LI("zap_error_invoice")
        case "invoiceMismatch": return LI("zap_error_mismatch")
        default: return L("payment_error")
        }
    }
}

/// QR code of the invoice, as Android's ZXing image: black on white, no smoothing.
enum ZapQrCode {
    static func image(_ text: String) -> UIImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage else { return nil }
        let scaled = output.transformed(by: CGAffineTransform(scaleX: 8, y: 8))
        guard let image = CIContext().createCGImage(scaled, from: scaled.extent) else { return nil }
        return UIImage(cgImage: image)
    }
}

/// Settings row (Android's "Send a Gift" in the developer-contact section).
struct ZapGiftRow: View {
    let core: AppCore
    @State private var showSheet = false

    var body: some View {
        Button {
            showSheet = true
        } label: {
            Label(L("devcontact_support_developer"), systemImage: "heart")
        }
        .sheet(isPresented: $showSheet) {
            ZapSheet(core: core)
        }
    }
}
