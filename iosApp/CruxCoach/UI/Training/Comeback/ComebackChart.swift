import CruxCoachCore
import SwiftUI

/// The 14-day diary as three plain lines on a 0–10 scale: morning, pressure, session (dashed). Descriptive only:
/// no traffic-light colours (Android `ComebackChart`, research/safety-regulation.md §5). A missing day breaks a line.
struct ComebackChart: View {
    let points: [ComebackChartPointUi]
    let empty: Bool

    private static let morningColor: Color = .accentColor
    private static let pressureColor: Color = .teal
    private static let sessionColor: Color = .secondary

    private var morning: [Int] { points.map { Int($0.morning) } }
    private var pressure: [Int] { points.map { Int($0.pressure) } }
    private var session: [Int] { points.map { Int($0.session) } }

    var body: some View {
        if empty {
            Text(L("cb_chart_empty")).font(.caption).foregroundStyle(.secondary)
        } else {
            VStack(alignment: .leading, spacing: 6) {
                HStack(alignment: .top, spacing: 4) {
                    axis
                    plot
                }
                .frame(height: 120)
                legend
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(L("cb_chart_title"))
            .accessibilityIdentifier("comeback_chart")
        }
    }

    private var axis: some View {
        VStack(alignment: .trailing) {
            Text("10")
            Spacer()
            Text("5")
            Spacer()
            Text("0")
        }
        .font(.caption2)
        .foregroundStyle(.secondary)
    }

    private var plot: some View {
        GeometryReader { geo in
            ZStack {
                ComebackChartPaths.grid(geo.size).stroke(Color.secondary.opacity(0.3), lineWidth: 1)
                ComebackChartPaths.line(session, geo.size)
                    .stroke(Self.sessionColor, style: StrokeStyle(lineWidth: 2, dash: [5, 4]))
                ComebackChartPaths.dots(session, geo.size).fill(Self.sessionColor)
                ComebackChartPaths.line(pressure, geo.size).stroke(Self.pressureColor, lineWidth: 2)
                ComebackChartPaths.dots(pressure, geo.size).fill(Self.pressureColor)
                ComebackChartPaths.line(morning, geo.size).stroke(Self.morningColor, lineWidth: 2)
                ComebackChartPaths.dots(morning, geo.size).fill(Self.morningColor)
            }
        }
    }

    private var legend: some View {
        HStack(spacing: 12) {
            legendItem(Self.morningColor, L("cb_chart_morning"))
            legendItem(Self.pressureColor, L("cb_chart_pressure"))
            legendItem(Self.sessionColor, L("cb_chart_session"))
        }
    }

    private func legendItem(_ color: Color, _ label: String) -> some View {
        HStack(spacing: 4) {
            Circle().fill(color).frame(width: 8, height: 8)
            Text(label).font(.caption2)
        }
    }
}

/// The chart's shapes; -1 means nothing logged that day.
private enum ComebackChartPaths {
    static func point(_ index: Int, _ value: Int, _ count: Int, _ size: CGSize) -> CGPoint {
        let steps: CGFloat = CGFloat(max(count - 1, 1))
        let x: CGFloat = size.width * CGFloat(index) / steps
        let y: CGFloat = size.height * (1 - CGFloat(value) / 10)
        return CGPoint(x: x, y: y)
    }

    static func grid(_ size: CGSize) -> Path {
        var path = Path()
        for v in [0, 5, 10] {
            let y: CGFloat = size.height * (1 - CGFloat(v) / 10)
            path.move(to: CGPoint(x: 0, y: y))
            path.addLine(to: CGPoint(x: size.width, y: y))
        }
        return path
    }

    static func line(_ values: [Int], _ size: CGSize) -> Path {
        var path = Path()
        var penDown = false
        for (i, v) in values.enumerated() {
            if v < 0 {
                penDown = false
                continue
            }
            let p = point(i, v, values.count, size)
            if penDown {
                path.addLine(to: p)
            } else {
                path.move(to: p)
            }
            penDown = true
        }
        return path
    }

    static func dots(_ values: [Int], _ size: CGSize) -> Path {
        var path = Path()
        for (i, v) in values.enumerated() where v >= 0 {
            let p = point(i, v, values.count, size)
            path.addEllipse(in: CGRect(x: p.x - 3, y: p.y - 3, width: 6, height: 6))
        }
        return path
    }
}
