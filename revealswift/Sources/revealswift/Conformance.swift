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
            'Reveal Math / KaTeX / MathJax rendering is intentionally out of scope for RevealSwift conformance.');
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
          deck.togglePause(true);
          check('core.pause', deck.isPaused() === true);
          deck.togglePause(false);
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
          const computed = content ? getComputedStyle(content).backgroundImage : '';
          const inlineImage = content?.style?.backgroundImage || '';
          const materialized = [computed, inlineImage].some(value =>
            value && value !== 'none' && (value.includes('data:image') || value.includes('svg+xml'))
          );
          check('core.background-image', materialized,
            'computed=' + computed + '; inline=' + inlineImage);
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
          const split = plugin?.slidify?.('A\\n---\\nB') || '';
          const sectionCount = (split.match(/<section/g) || []).length;
          check('plugin.markdown.slidify', sectionCount >= 2, 'sections=' + sectionCount);
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
          const slide = await goTo('transition-slide');
          check('core.transition.slide-attribute', slide.dataset.transition === 'zoom', slide.dataset.transition || null);
          check('core.transition.speed-attribute', slide.dataset.transitionSpeed === 'fast', slide.dataset.transitionSpeed || null);
          check('core.transition.background-attribute', slide.dataset.backgroundTransition === 'fade',
            slide.dataset.backgroundTransition || null);

          const before = {
            transition: deck.getConfig().transition,
            transitionSpeed: deck.getConfig().transitionSpeed,
            backgroundTransition: deck.getConfig().backgroundTransition
          };
          deck.configure({ transition:'fade', transitionSpeed:'fast', backgroundTransition:'slide' });
          check('core.transition.configure',
            deck.getConfig().transition === 'fade' &&
            deck.getConfig().transitionSpeed === 'fast' &&
            deck.getConfig().backgroundTransition === 'slide',
            JSON.stringify({
              transition:deck.getConfig().transition,
              transitionSpeed:deck.getConfig().transitionSpeed,
              backgroundTransition:deck.getConfig().backgroundTransition
            }));
          deck.configure(before);
        } catch (error) {
          fail('core.transition', String(error));
        }

        try {
          const slide = await goTo('layout-helpers');
          deck.layout();
          await delay(30);
          const fit = document.getElementById('fit-text');
          const stretch = document.getElementById('stretch-image');
          const fitFont = parseFloat(getComputedStyle(fit).fontSize || '0');
          const stretchRect = stretch.getBoundingClientRect();
          const slideRect = slide.getBoundingClientRect();
          check('core.layout.fit-text', fitFont >= 24, 'font=' + fitFont);
          check('core.layout.stretch-sized', stretchRect.height > 100 && stretchRect.width > 100,
            'size=' + stretchRect.width + 'x' + stretchRect.height);
          check('core.layout.stretch-contained',
            stretchRect.left >= slideRect.left - 1 &&
            stretchRect.right <= slideRect.right + 1 &&
            stretchRect.top >= slideRect.top - 1 &&
            stretchRect.bottom <= slideRect.bottom + 1);
          const computed = deck.getComputedSlideSize();
          check('core.layout.computed-size',
            computed && computed.width > 0 && computed.height > 0,
            computed ? JSON.stringify(computed) : null);
        } catch (error) {
          fail('core.layout-helpers', String(error));
        }

        try {
          const previousConfig = {
            controls: deck.getConfig().controls,
            progress: deck.getConfig().progress,
            slideNumber: deck.getConfig().slideNumber
          };
          deck.configure({ controls:true, progress:true, slideNumber:'c/t' });
          await goTo('links-slide');
          await delay(20);
          const controls = root.querySelector('.controls');
          const progress = root.querySelector('.progress');
          const number = root.querySelector('.slide-number');
          check('core.ui.controls', Boolean(controls) && getComputedStyle(controls).display !== 'none');
          check('core.ui.progress', Boolean(progress) && getComputedStyle(progress).display !== 'none');
          check('core.ui.slide-number',
            Boolean(number) && getComputedStyle(number).display !== 'none' && number.textContent.trim().length > 0,
            number?.textContent?.trim() || null);
          deck.configure(previousConfig);
        } catch (error) {
          fail('core.ui', String(error));
        }

        try {
          await goTo('links-slide');
          const link = document.getElementById('internal-link');
          link.click();
          await delay(30);
          check('core.links.internal-navigation', deck.getCurrentSlide()?.id === 'search-target',
            deck.getCurrentSlide()?.id || null);
        } catch (error) {
          fail('core.links.internal-navigation', String(error));
        }

        try {
          await goTo('vertical-b');
          const saved = deck.getState();
          const savedPath = deck.getSlidePath();
          const savedProgress = deck.getProgress();
          const attrs = deck.getSlidesAttributes();
          await goTo('search-target');
          deck.setState(saved);
          await delay(30);
          check('core.state.restore', deck.getCurrentSlide()?.id === 'vertical-b',
            deck.getCurrentSlide()?.id || null);
          check('core.api.slide-path', typeof savedPath === 'string' && savedPath.length > 0, savedPath || null);
          check('core.api.progress', typeof savedProgress === 'number' && savedProgress >= 0 && savedProgress <= 1,
            String(savedProgress));
          check('core.api.slides-attributes', Array.isArray(attrs) && attrs.length === deck.getSlides().length,
            'attributes=' + (Array.isArray(attrs) ? attrs.length : -1));
          const routes = deck.availableRoutes();
          check('core.api.available-routes',
            routes && ['left','right','up','down'].every(k => typeof routes[k] === 'boolean'),
            JSON.stringify(routes));
          const fragments = deck.availableFragments();
          check('core.api.available-fragments',
            fragments && typeof fragments.prev === 'boolean' && typeof fragments.next === 'boolean',
            JSON.stringify(fragments));

          const restoredIndices = deck.getIndices();
          const restoredSlide = deck.getSlide(restoredIndices.h, restoredIndices.v);
          const restoredBackground = deck.getSlideBackground(restoredIndices.h, restoredIndices.v);
          check('core.api.get-slide', restoredSlide === deck.getCurrentSlide(),
            restoredSlide?.id || null);
          check('core.api.get-slide-background',
            Boolean(restoredBackground) && restoredBackground === restoredSlide?.slideBackgroundElement);
          check('core.api.scale', typeof deck.getScale() === 'number' && deck.getScale() > 0,
            String(deck.getScale()));
          check('core.api.dom-handles',
            deck.getRevealElement() === root &&
            deck.getSlidesElement() === root.querySelector('.slides') &&
            Boolean(deck.getViewportElement()) &&
            Boolean(deck.getBackgroundsElement()));
          check('core.api.has-horizontal-slides', deck.hasHorizontalSlides() === true);
          check('core.api.has-vertical-slides', deck.hasVerticalSlides() === true);
          check('core.api.vertical-slide', deck.isVerticalSlide(restoredSlide) === true);
          check('core.api.vertical-stack', deck.isVerticalStack(restoredSlide?.parentElement) === true);

          const registeredPlugins = deck.getPlugins();
          check('core.api.plugin-registry',
            registeredPlugins && ['markdown','highlight','notes','search','zoom','custom-e2e']
              .every(id => Boolean(registeredPlugins[id])));

          const first = deck.getSlide(0, 0);
          const firstIndices = deck.getIndices(first);
          deck.slide(firstIndices.h, firstIndices.v);
          await delay(20);
          check('core.api.first-slide', deck.isFirstSlide() === true);

          const allSlides = deck.getSlides();
          const last = allSlides[allSlides.length - 1];
          const lastIndices = deck.getIndices(last);
          deck.slide(lastIndices.h, lastIndices.v);
          await delay(20);
          check('core.api.last-slide', deck.isLastSlide() === true);

          deck.setState(saved);
          await delay(20);
        } catch (error) {
          fail('core.state-api', String(error));
        }

        try {
          document.activeElement?.blur?.();
          let keyCount = 0;
          deck.addKeyBinding({keyCode:88, key:'X', description:'E2E binding'}, () => keyCount++);
          deck.triggerKey(88);
          await delay(10);
          check('core.keyboard.custom-binding', keyCount === 1, 'count=' + keyCount);
          deck.removeKeyBinding(88);
          deck.triggerKey(88);
          await delay(10);
          check('core.keyboard.remove-binding', keyCount === 1, 'count=' + keyCount);
        } catch (error) {
          fail('core.keyboard', String(error));
        }

        try {
          const oldAutoSlide = deck.getConfig().autoSlide;
          deck.configure({ autoSlide:10000 });
          deck.toggleAutoSlide(true);
          check('core.auto-slide.start', deck.isAutoSliding() === true);
          deck.toggleAutoSlide(false);
          check('core.auto-slide.stop', deck.isAutoSliding() === false);
          deck.configure({ autoSlide:oldAutoSlide });
        } catch (error) {
          fail('core.auto-slide', String(error));
        }

        try {
          const previousView = deck.getConfig().view;
          const previousActivationWidth = deck.getConfig().scrollActivationWidth;

          // Reveal's responsive layout automatically exits manually-enabled
          // scroll mode when the presentation is wider than
          // scrollActivationWidth. Mark the view as explicitly scroll while
          // testing so layout() doesn't immediately undo the activation.
          deck.configure({ view:'scroll' });
          deck.toggleScrollView(true);
          await delay(30);
          check('core.scroll-view.enter', deck.isScrollView() === true);

          deck.toggleScrollView(false);
          await delay(30);
          check('core.scroll-view.exit', deck.isScrollView() === false);

          deck.configure({
            view: previousView,
            scrollActivationWidth: previousActivationWidth
          });
        } catch (error) {
          fail('core.scroll-view', String(error));
          try { deck.toggleScrollView(false); } catch (_) {}
          try { deck.configure({ view:null }); } catch (_) {}
        }

        try {
          // Scroll-view deactivation reconstructs the slide DOM, so query
          // fresh nodes here rather than holding references from before it.
          const hidden = document.getElementById('hidden-slide');
          const uncounted = document.getElementById('uncounted-slide');
          check('core.visibility.hidden-removed', hidden === null);
          check('core.visibility.uncounted-retained',
            Boolean(uncounted) && uncounted.dataset.visibility === 'uncounted');

          const nextSlide = uncounted?.nextElementSibling;
          const pastBefore = uncounted ? deck.getSlidePastCount(uncounted) : -1;
          const pastAfter = nextSlide ? deck.getSlidePastCount(nextSlide) : pastBefore;
          check('core.visibility.uncounted-not-counted',
            Boolean(uncounted) && Boolean(nextSlide) && pastAfter === pastBefore,
            'next=' + (nextSlide?.id || 'none') + ',before=' + pastBefore + ',after=' + pastAfter);
        } catch (error) {
          fail('core.visibility', String(error));
        }

        try {
          const slide = document.getElementById('lazy-media');
          const image = document.getElementById('lazy-image');
          const frame = document.getElementById('lazy-frame');

          // Reset to the pre-load shape in case Reveal's view-distance logic
          // already promoted these while initializing nearby slides.
          if (image?.getAttribute('src') && !image?.getAttribute('data-src')) {
            image.setAttribute('data-src', image.getAttribute('src'));
            image.removeAttribute('src');
            image.removeAttribute('data-lazy-loaded');
          }
          if (frame?.getAttribute('src') && !frame?.getAttribute('data-src')) {
            frame.setAttribute('data-src', frame.getAttribute('src'));
            frame.removeAttribute('src');
            frame.removeAttribute('data-lazy-loaded');
          }

          const oldPreload = deck.getConfig().preloadIframes;
          deck.configure({ preloadIframes:true });
          deck.loadSlide(slide);
          await delay(30);
          check('core.lazy-media.image-load',
            Boolean(image?.getAttribute('src')) && image?.hasAttribute('data-lazy-loaded'));
          check('core.lazy-media.iframe-load',
            Boolean(frame?.getAttribute('src')) && frame?.hasAttribute('data-lazy-loaded'));

          deck.unloadSlide(slide);
          check('core.lazy-media.iframe-unload',
            !frame?.getAttribute('src') && Boolean(frame?.getAttribute('data-src')));
          deck.configure({ preloadIframes:oldPreload });
        } catch (error) {
          fail('core.lazy-media', String(error));
        }

        try {
          const slide = await goTo('background-iframe');
          await delay(30);
          const background = deck.getSlideBackground(slide);
          const frame = background?.querySelector('iframe');
          check('core.background-iframe.generated', Boolean(frame));
          check('core.background-iframe.source',
            Boolean(frame?.getAttribute('src') || frame?.getAttribute('data-src')));
        } catch (error) {
          fail('core.background-iframe', String(error));
        }

        try {
          const slide = await goTo('markdown-advanced');
          const notes = slide.querySelector('aside.notes');
          check('plugin.markdown.notes',
            notes?.textContent?.includes('Markdown speaker note E2E') === true,
            notes?.textContent?.trim() || null);
          check('plugin.markdown.notes-api',
            String(deck.getSlideNotes() || '').includes('Markdown speaker note E2E'),
            String(deck.getSlideNotes() || '').trim() || null);
        } catch (error) {
          fail('plugin.markdown.notes', String(error));
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
