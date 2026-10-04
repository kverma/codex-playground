import Foundation
import RevealSwiftCore

#if canImport(WebKit) && canImport(AppKit)
import WebKit
#endif

struct ConformanceAssertion: Codable, Sendable {
    var name: String
    var status: String
    var detail: String?
}

struct ConformanceReport: Codable, Sendable {
    var deck: String
    var generatedAt: Date
    var passed: Int
    var failed: Int
    var skipped: Int
    var assertions: [ConformanceAssertion]
}

extension RevealSwiftCLI {
    @MainActor
    static func runConformance(_ args: [String]) async throws {
        guard let input = args.first else {
            throw CLIError("Usage: revealswift conformance <deck.html> [--theme <dir>] [--output <report.json>]")
        }

        let options = Options(args: Array(args.dropFirst()))
        #if canImport(WebKit) && canImport(AppKit)
        let deckURL = URL(fileURLWithPath: input)
        let themePath = try resolveThemePath(explicit: options.value("--theme"), deckURL: deckURL)
        let runner = try await WebKitRunner(deck: deckURL, themePath: themePath)
        let assertions = try await runner.runRevealConformance()
        let report = ConformanceReport(
            deck: deckURL.lastPathComponent,
            generatedAt: Date(),
            passed: assertions.filter { $0.status == "pass" }.count,
            failed: assertions.filter { $0.status == "fail" }.count,
            skipped: assertions.filter { $0.status == "skip" }.count,
            assertions: assertions
        )

        let data = try JSONIO.encode(report)
        if let output = options.value("--output") {
            let url = URL(fileURLWithPath: output)
            try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
            try data.write(to: url)
        }
        print(String(decoding: data, as: UTF8.self))

        if report.failed > 0 {
            throw CLIError("Reveal conformance failed: \(report.failed) assertion(s)")
        }
        #else
        throw CLIError("Reveal conformance requires macOS with WebKit")
        #endif
    }
}

#if canImport(WebKit) && canImport(AppKit)
extension WebKitRunner {
    @MainActor
    func runRevealConformance() async throws -> [ConformanceAssertion] {
        let script = """
        const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
        const results = [];
        const add = (name, status, detail = null) => results.push({ name, status, detail });
        const pass = (name, detail = null) => add(name, 'pass', detail);
        const fail = (name, detail = null) => add(name, 'fail', detail);
        const skip = (name, detail = null) => add(name, 'skip', detail);
        const check = (name, condition, detail = null) => condition ? pass(name, detail) : fail(name, detail);

        const deck = window.__revealswiftDeck;
        const root = document.querySelector('.reveal');
        const viewport = document.querySelector('.reveal-viewport') || document.documentElement;

        const goTo = async id => {
          const slide = document.getElementById(id);
          if (!slide) throw new Error('Missing slide #' + id);
          const indices = deck.getIndices(slide);
          deck.slide(indices.h, indices.v, -1);
          await delay(30);
          return slide;
        };

        try {
          check('runtime.ready', Boolean(window.__revealswiftReady && deck));
          check('runtime.offline', window.__revealswiftRuntime?.offline === true);
          check('runtime.reveal-version', typeof window.__revealswiftRuntime?.revealVersion === 'string' &&
            window.__revealswiftRuntime.revealVersion.length > 0, window.__revealswiftRuntime?.revealVersion || null);
          check('runtime.chart-version', typeof window.Chart?.version === 'string', window.Chart?.version || null);
          check('runtime.math-plugin-bundle', Boolean(window.RevealMath));
        } catch (error) {
          fail('runtime.bootstrap', String(error));
        }

        try {
          const ids = ['markdown','highlight','notes','search','zoom'];
          for (const id of ids) check('plugin.registered.' + id, Boolean(deck.getPlugin(id)));
          check('plugin.registered.custom', Boolean(deck.getPlugin('custom-e2e')));
          check('plugin.custom-init', root?.dataset?.customPlugin === 'initialized', root?.dataset?.customPlugin || null);
        } catch (error) {
          fail('plugin.registration', String(error));
        }

        try {
          const mathFactories = Boolean(
            window.RevealMath &&
            typeof RevealMath.KaTeX === 'function' &&
            typeof RevealMath.MathJax2 === 'function' &&
            typeof RevealMath.MathJax3 === 'function' &&
            typeof RevealMath.MathJax4 === 'function'
          );
          check('plugin.math.factories', mathFactories);
          skip('plugin.math.rendering',
            'Reveal Math is bundled, but an offline KaTeX/MathJax engine is not bundled yet; rendering is intentionally not asserted.');
        } catch (error) {
          fail('plugin.math.factories', String(error));
        }

        try {
          const slides = deck.getSlides();
          check('core.slide-count', slides.length >= 12, 'count=' + slides.length);
          check('core.horizontal-slides', deck.getHorizontalSlides().length >= 10,
            'count=' + deck.getHorizontalSlides().length);
          check('core.vertical-slides', deck.getVerticalSlides().length >= 2,
            'count=' + deck.getVerticalSlides().length);
        } catch (error) {
          fail('core.slide-inventory', String(error));
        }

        try {
          await goTo('vertical-b');
          check('core.vertical-navigation', deck.getCurrentSlide()?.id === 'vertical-b',
            deck.getCurrentSlide()?.id || null);
          const idx = deck.getIndices();
          check('core.vertical-indices', idx.v === 1, JSON.stringify(idx));
        } catch (error) {
          fail('core.vertical-navigation', String(error));
        }

        try {
          let shown = 0;
          let hidden = 0;
          const onShown = () => shown++;
          const onHidden = () => hidden++;
          deck.on('fragmentshown', onShown);
          deck.on('fragmenthidden', onHidden);

          await goTo('fragments');
          const slide = document.getElementById('fragments');
          const fragments = [...slide.querySelectorAll('.fragment')];
          check('core.fragments.declared', fragments.length === 3, 'count=' + fragments.length);

          deck.nextFragment();
          await delay(15);
          check('core.fragments.next', fragments.filter(f => f.classList.contains('visible')).length === 1);
          deck.nextFragment();
          await delay(15);
          check('core.fragments.sequence', fragments.filter(f => f.classList.contains('visible')).length === 2);
          deck.prevFragment();
          await delay(15);
          check('core.fragments.previous', fragments.filter(f => f.classList.contains('visible')).length === 1);
          check('core.fragments.events', shown >= 2 && hidden >= 1, 'shown=' + shown + ',hidden=' + hidden);

          deck.off('fragmentshown', onShown);
          deck.off('fragmenthidden', onHidden);
        } catch (error) {
          fail('core.fragments', String(error));
        }

        try {
          let changed = 0;
          const handler = () => changed++;
          deck.on('slidechanged', handler);
          await goTo('state-slide');
          await goTo('search-target');
          deck.off('slidechanged', handler);
          check('core.slidechanged-event', changed >= 1, 'count=' + changed);
        } catch (error) {
          fail('core.slidechanged-event', String(error));
        }

        try {
          deck.toggleOverview(true);
          await delay(20);
          check('core.overview-enter', deck.isOverview() === true);
          deck.toggleOverview(false);
          await delay(20);
          check('core.overview-exit', deck.isOverview() === false);
        } catch (error) {
          fail('core.overview', String(error));
        }

        try {
          deck.pause();
          check('core.pause', deck.isPaused() === true);
          deck.resume();
          check('core.resume', deck.isPaused() === false);
        } catch (error) {
          fail('core.pause-resume', String(error));
        }

        try {
          await goTo('state-slide');
          const hasState = [
            document.documentElement,
            document.body,
            viewport,
            root
          ].filter(Boolean).some(el => el.classList.contains('conformance-state'));
          check('core.data-state', hasState);
        } catch (error) {
          fail('core.data-state', String(error));
        }

        try {
          const slide = await goTo('background-color');
          const bg = deck.getSlideBackground(slide);
          const color = bg ? getComputedStyle(bg).backgroundColor : '';
          check('core.background-color', /rgb\\(18,\\s*52,\\s*86\\)/.test(color), color || null);
        } catch (error) {
          fail('core.background-color', String(error));
        }

        try {
          const slide = await goTo('background-gradient');
          const bg = deck.getSlideBackground(slide);
          const image = bg ? getComputedStyle(bg).backgroundImage : '';
          check('core.background-gradient', image.includes('linear-gradient'), image || null);
        } catch (error) {
          fail('core.background-gradient', String(error));
        }

        try {
          const slide = await goTo('background-image');
          const bg = deck.getSlideBackground(slide);
          const content = bg?.querySelector('.slide-background-content');
          const image = content ? getComputedStyle(content).backgroundImage : '';
          check('core.background-image', image.includes('data:image/svg+xml'), image ? 'data-uri loaded' : null);
        } catch (error) {
          fail('core.background-image', String(error));
        }

        try {
          let autoAnimateEvents = 0;
          const handler = () => autoAnimateEvents++;
          deck.on('autoanimate', handler);
          await goTo('auto-a');
          await goTo('auto-b');
          await delay(50);
          deck.off('autoanimate', handler);

          const target = document.querySelector('#auto-b [data-id="box"]');
          check('core.auto-animate.enabled', deck.getConfig().autoAnimate !== false);
          check('core.auto-animate.event', autoAnimateEvents >= 1, 'count=' + autoAnimateEvents);
          check('core.auto-animate.target', Boolean(target?.dataset?.autoAnimateTarget) ||
            document.getElementById('auto-b')?.dataset?.autoAnimate === 'running' ||
            document.getElementById('auto-b')?.dataset?.autoAnimate === 'pending');
        } catch (error) {
          fail('core.auto-animate', String(error));
        }

        try {
          const md = document.getElementById('markdown-slide');
          const plugin = deck.getPlugin('markdown');
          check('plugin.markdown.converted', Boolean(md?.querySelector('h2') && md?.querySelectorAll('li').length === 2),
            md?.innerText?.trim() || null);
          check('plugin.markdown.parsed-marker', md?.hasAttribute('data-markdown-parsed') === true);
          const split = plugin?.slidify?.('A\\n---\\nB\\n--\\nC') || '';
          check('plugin.markdown.slidify', split.includes('<section') && split.split('<section').length >= 4);
        } catch (error) {
          fail('plugin.markdown', String(error));
        }

        try {
          const code = document.querySelector('#highlight-slide pre code');
          check('plugin.highlight.hljs', Boolean(code?.classList.contains('hljs')));
          check('plugin.highlight.tokens', Boolean(code?.querySelector('[class*="hljs-"]')));
          check('plugin.highlight.line-numbers', Boolean(code?.querySelector('table.hljs-ln')));
          const generatedSteps = document.querySelectorAll('#highlight-slide pre code.fragment').length;
          check('plugin.highlight.step-fragments', generatedSteps >= 1, 'generated=' + generatedSteps);
        } catch (error) {
          fail('plugin.highlight', String(error));
        }

        try {
          const notes = deck.getPlugin('notes');
          const aside = document.querySelector('#notes-slide aside.notes');
          check('plugin.notes.api', typeof notes?.open === 'function');
          check('plugin.notes.content', aside?.textContent?.includes('Speaker note E2E') === true,
            aside?.textContent?.trim() || null);
        } catch (error) {
          fail('plugin.notes', String(error));
        }

        try {
          const search = deck.getPlugin('search');
          search.open();
          await delay(10);
          const box = root.querySelector('.searchbox');
          const input = box?.querySelector('.searchinput');
          check('plugin.search.open', Boolean(box && input && box.style.display === 'inline'));

          if (input) {
            input.value = 'E2E-needle-unique';
            const typeEvent = new KeyboardEvent('keyup', {key:'e', bubbles:true});
            Object.defineProperty(typeEvent, 'keyCode', {get: () => 69});
            input.dispatchEvent(typeEvent);
            const enterEvent = new KeyboardEvent('keyup', {key:'Enter', bubbles:true});
            Object.defineProperty(enterEvent, 'keyCode', {get: () => 13});
            input.dispatchEvent(enterEvent);
            await delay(30);
          }
          check('plugin.search.navigation', deck.getCurrentSlide()?.id === 'search-target',
            deck.getCurrentSlide()?.id || null);
          search.close();
          check('plugin.search.close', box?.style.display === 'none');
        } catch (error) {
          fail('plugin.search', String(error));
        }

        try {
          await goTo('zoom-target');
          const zoom = deck.getPlugin('zoom');
          const event = new MouseEvent('mousedown', {
            bubbles:true,
            clientX:Math.max(40, window.innerWidth / 2),
            clientY:Math.max(40, window.innerHeight / 2),
            altKey:true
          });
          root.dispatchEvent(event);
          await delay(20);
          const zoomed = document.documentElement.classList.contains('zoomed') ||
            (document.body.style.transform || '').includes('scale');
          check('plugin.zoom.activate', zoomed, document.body.style.transform || null);
          zoom?.destroy?.();
          await delay(10);
          check('plugin.zoom.reset',
            !document.documentElement.classList.contains('zoomed') &&
            !(document.body.style.transform || '').includes('scale'));
        } catch (error) {
          fail('plugin.zoom', String(error));
        }

        try {
          await goTo('chart-slide');
          await delay(80);
          const canvas = document.getElementById('conformance-chart');
          const chart = window.Chart?.getChart?.(canvas);
          check('library.chartjs.instance', Boolean(chart));
          check('library.chartjs.data', chart?.data?.labels?.length === 3 && chart?.data?.datasets?.[0]?.data?.length === 3);
        } catch (error) {
          fail('library.chartjs', String(error));
        }

        try {
          await goTo('media-slide');
          const image = document.getElementById('inline-image');
          const frame = document.getElementById('inline-frame');
          check('core.image-data-uri', Boolean(image?.complete && image?.naturalWidth > 0),
            image ? 'naturalWidth=' + image.naturalWidth : null);
          let frameText = '';
          try { frameText = frame?.contentDocument?.body?.textContent || ''; } catch (_) {}
          check('core.iframe-srcdoc', frameText.includes('Inline iframe E2E'), frameText.trim() || null);
        } catch (error) {
          fail('core.embedded-media', String(error));
        }

        try {
          const custom = deck.getPlugin('custom-e2e');
          check('plugin.custom.api', typeof custom?.ping === 'function' && custom.ping() === 'pong');
        } catch (error) {
          fail('plugin.custom.api', String(error));
        }

        return JSON.stringify(results);
        """

        let value = try await webView.callAsyncJavaScript(
            "return await (async () => { \(script) })();",
            arguments: [:],
            in: nil,
            contentWorld: .page
        )

        guard let json = value as? String, let data = json.data(using: .utf8) else {
            throw CLIError("Conformance bridge returned a non-JSON value")
        }
        return try JSONDecoder().decode([ConformanceAssertion].self, from: data)
    }
}
#endif
