#!/usr/bin/env python3
import json
import sys
from pathlib import Path

REQUIRED_PASSES = {
    'runtime.ready','runtime.offline','runtime.reveal-version','runtime.chart-version','runtime.math-plugin-bundle','runtime.katex-version',
    'plugin.registered.markdown','plugin.registered.highlight','plugin.registered.notes','plugin.registered.katex','plugin.registered.search','plugin.registered.zoom','plugin.registered.custom','plugin.custom-init','plugin.math.factories','plugin.math.local-runtime','plugin.math.rendering','plugin.math.layout','plugin.math.fonts-loaded',
    'core.slide-count','core.horizontal-slides','core.vertical-slides','core.vertical-navigation','core.vertical-indices',
    'core.fragments.declared','core.fragments.next','core.fragments.sequence','core.fragments.previous','core.fragments.events','core.slidechanged-event',
    'core.overview-enter','core.overview-exit','core.pause','core.resume','core.data-state',
    'core.background-color','core.background-gradient','core.background-image','core.auto-animate.enabled','core.auto-animate.event','core.auto-animate.target',
    'plugin.markdown.converted','plugin.markdown.parsed-marker','plugin.markdown.slidify',
    'plugin.highlight.hljs','plugin.highlight.tokens','plugin.highlight.line-numbers','plugin.highlight.step-fragments',
    'plugin.notes.api','plugin.notes.content','plugin.search.open','plugin.search.navigation','plugin.search.close','plugin.zoom.activate','plugin.zoom.reset',
    'library.chartjs.instance','library.chartjs.data','core.image-data-uri','core.iframe-srcdoc',
    'core.transition.slide-attribute','core.transition.speed-attribute','core.transition.background-attribute','core.transition.configure',
    'core.layout.fit-text','core.layout.stretch-sized','core.layout.stretch-contained','core.layout.computed-size',
    'core.ui.controls','core.ui.progress','core.ui.slide-number','core.links.internal-navigation',
    'core.state.restore','core.api.slide-path','core.api.progress','core.api.slides-attributes','core.api.available-routes','core.api.available-fragments',
    'core.keyboard.custom-binding','core.keyboard.remove-binding','core.auto-slide.start','core.auto-slide.stop','core.scroll-view.enter','core.scroll-view.exit',
    'core.visibility.hidden-removed','core.visibility.uncounted-retained','core.visibility.uncounted-not-counted',
    'core.lazy-media.image-load','core.lazy-media.iframe-load','core.lazy-media.iframe-unload',
    'core.background-iframe.generated','core.background-iframe.source','plugin.markdown.notes','plugin.markdown.notes-api','plugin.custom.api',
}
EXPECTED_SKIPS = set()

def main() -> int:
    report_path = Path(sys.argv[1] if len(sys.argv) > 1 else 'conformance-artifacts/conformance.json')
    report = json.loads(report_path.read_text())
    assertions = {item['name']: item for item in report['assertions']}

    problems = []
    if report.get('failed') != 0:
        problems.append(f"reported failures={report.get('failed')}")
    if report.get('passed', 0) < len(REQUIRED_PASSES):
        problems.append(f"passed={report.get('passed')} < required floor {len(REQUIRED_PASSES)}")

    missing = sorted(name for name in REQUIRED_PASSES if assertions.get(name, {}).get('status') != 'pass')
    if missing:
        problems.append('required assertions not passing: ' + ', '.join(missing))

    skips = {name for name, item in assertions.items() if item.get('status') == 'skip'}
    if skips != EXPECTED_SKIPS:
        problems.append(f"skip set changed: expected {sorted(EXPECTED_SKIPS)}, got {sorted(skips)}")
    if report.get('skipped') != len(EXPECTED_SKIPS):
        problems.append(f"reported skipped={report.get('skipped')} but expected {len(EXPECTED_SKIPS)}")

    if problems:
        print('RevealSwift conformance gate FAILED:', file=sys.stderr)
        for problem in problems:
            print(f'- {problem}', file=sys.stderr)
        return 1

    print(f"RevealSwift conformance gate passed: {report['passed']} passed, {report['failed']} failed, {report['skipped']} skipped")
    return 0

if __name__ == '__main__':
    raise SystemExit(main())
