import Foundation

public enum ReportScoring {
    public static func score(metrics: [SlideMetrics]) -> (score: Int, errors: Int, warnings: Int) {
        let issues = metrics.flatMap(\.issues)
        let errors = issues.filter { $0.severity == .error }.count
        let warnings = issues.filter { $0.severity == .warning }.count
        return (max(0, 100 - errors * 12 - warnings * 3), errors, warnings)
    }
}
