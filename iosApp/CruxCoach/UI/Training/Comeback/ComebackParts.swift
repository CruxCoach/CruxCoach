import SwiftUI

/// The notice on every comeback surface (owner 2026-10-20, Android `ComebackNotice`):
/// "Kein Ersatz für den Arztbesuch – nur in Absprache mit Ärztin, Arzt oder Physio nutzen."
/// With [onSigns] it also offers "Warnzeichen" (the injury questions).
struct ComebackNotice: View {
    var onSigns: (() -> Void)? = nil

    var body: some View {
        HStack(alignment: .center, spacing: 8) {
            Image(systemName: "info.circle")
                .font(.body)
                .accessibilityHidden(true)
            Text(L("cb_not_a_substitute"))
                .font(.footnote)
                .frame(maxWidth: .infinity, alignment: .leading)
                .fixedSize(horizontal: false, vertical: true)
            if let onSigns {
                Button(L("cb_warning_signs"), action: onSigns)
                    .font(.footnote.weight(.semibold))
                    .accessibilityIdentifier("comeback_notice_signs")
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.accentColor.opacity(0.12), in: RoundedRectangle(cornerRadius: 12))
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("comeback_notice")
    }
}

/// The notice as a plain line under a card's content (Android's `cb_not_a_substitute` in bodySmall).
struct ComebackNoticeLine: View {
    var body: some View {
        Text(L("cb_not_a_substitute"))
            .font(.caption)
            .foregroundStyle(.secondary)
            .fixedSize(horizontal: false, vertical: true)
            .accessibilityIdentifier("comeback_notice_line")
    }
}

/// 0–10 at the injured spot; tapping the chosen value again clears it (Android `PainScale`).
struct ComebackPainScale: View {
    let label: String
    let value: Int?
    /// Accessibility identifier prefix; each value gets `<tag>_<n>`.
    let tag: String
    var hint: String? = nil
    let onChange: (Int?) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(label).font(.subheadline)
            if let hint {
                Text(hint).font(.caption).foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            TrainingFlow(spacing: 6) {
                ForEach(0...10, id: \.self) { v in
                    chip(v)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func chip(_ v: Int) -> some View {
        let selected = value == v
        return Button {
            onChange(selected ? nil : v)
        } label: {
            Text("\(v)")
                .font(.subheadline.weight(selected ? .bold : .regular))
                .frame(minWidth: 30, minHeight: 30)
                .foregroundStyle(selected ? Color.white : Color.primary)
                .background(selected ? Color.accentColor : Color(.secondarySystemBackground), in: Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("\(label): \(v)")
        .accessibilityAddTraits(selected ? .isSelected : [])
        .accessibilityIdentifier("\(tag)_\(v)")
    }
}
