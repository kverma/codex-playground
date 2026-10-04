import Foundation

public enum InspectorScript {
    public static func javascript(rules: ThemeRules, allowedComponents: [String] = [], appearance: String? = nil) -> String {
        let allowedJSON: String = {
            guard let data = try? JSONEncoder().encode(allowedComponents),
                  let string = String(data: data, encoding: .utf8) else { return "[]" }
            return string
        }()

        let appearanceJS = appearance.map { "\"\($0)\"" } ?? "null"

        return """
        JSON.stringify((() => {
          const RevealSwiftDeck = window.__revealswiftDeck || window.Reveal;
          const slide = RevealSwiftDeck.getCurrentSlide();
          if (!slide) return {error:'No active slide'};
          const root = slide.getBoundingClientRect();
          const allowedComponents = new Set(\(allowedJSON));
          const visible = [...slide.querySelectorAll('*')].filter(el => {
            const s = getComputedStyle(el), r = el.getBoundingClientRect();
            return s.display !== 'none' && s.visibility !== 'hidden' && Number(s.opacity) !== 0 && r.width > 0 && r.height > 0;
          });
          const selector = el => {
            if (el.id) return '#' + CSS.escape(el.id);
            const cls = [...el.classList].slice(0,2).map(c => '.' + CSS.escape(c)).join('');
            return el.tagName.toLowerCase() + cls;
          };
          const issues = [];
          const expectedAppearance = \(appearanceJS);
          const viewport = document.querySelector('.reveal-viewport') || document.body;
          const viewportBackground = getComputedStyle(viewport).backgroundColor;
          const rgb = (viewportBackground.match(/[0-9.]+/g) || []).slice(0,3).map(Number);
          if (expectedAppearance && rgb.length === 3) {
            const luminance = (0.2126*rgb[0] + 0.7152*rgb[1] + 0.0722*rgb[2]) / 255;
            if (expectedAppearance === 'dark' && luminance > 0.35) {
              issues.push({severity:'error', rule:'theme.appearanceMismatch',
                message:`Theme declares dark appearance but viewport is light (${viewportBackground})`, amount:luminance});
            }
            if (expectedAppearance === 'light' && luminance < 0.55) {
              issues.push({severity:'error', rule:'theme.appearanceMismatch',
                message:`Theme declares light appearance but viewport is dark (${viewportBackground})`, amount:luminance});
            }
          }

          let minFont = 9999;
          const densityRects = [];
          const densityContributors = [];

          for (const el of visible) {
            const r = el.getBoundingClientRect(), s = getComputedStyle(el);
            const hasDirectText = [...el.childNodes].some(n => n.nodeType === Node.TEXT_NODE && n.textContent.trim());
            const backgroundVisible =
              (s.backgroundImage && s.backgroundImage !== 'none') ||
              (s.backgroundColor && !['transparent','rgba(0, 0, 0, 0)'].includes(s.backgroundColor));
            const borderVisible =
              ['Top','Right','Bottom','Left'].some(side => parseFloat(s['border' + side + 'Width'] || '0') > 0);
            const mediaLike = ['IMG','CANVAS','SVG','IFRAME','VIDEO','PRE','TABLE'].includes(el.tagName);
            const semanticVisual = el.hasAttribute('data-rs-component');
            if (hasDirectText || backgroundVisible || borderVisible || mediaLike || semanticVisual) {
              densityRects.push(r);
              densityContributors.push({
                element: selector(el),
                width: r.width,
                height: r.height,
                area: Math.max(0, r.width) * Math.max(0, r.height),
                background: s.backgroundColor || ''
              });
            }
            if (hasDirectText) {
              const fs = parseFloat(s.fontSize || '9999');
              minFont = Math.min(minFont, fs);
              const caption = el.classList.contains('caption') || el.classList.contains('eyebrow') ||
                              el.classList.contains('footnote') || el.hasAttribute('data-rs-caption');
              const minimum = caption ? \(rules.minimumCaptionFontPx) : \(rules.minimumBodyFontPx);
              if (fs + 0.1 < minimum) {
                issues.push({severity:'warning', rule:'theme.fontTooSmall',
                  message:`Text is ${fs.toFixed(1)}px; theme minimum is ${minimum}px`,
                  element:selector(el), amount:fs});
              }
            }

            const dx = Math.max(root.left-r.left, r.right-root.right, 0);
            const dy = Math.max(root.top-r.top, r.bottom-root.bottom, 0);
            if (dx > 1 || dy > 1) issues.push({severity:'error', rule:'layout.outOfBounds',
              message:`Element exceeds slide bounds by ${Math.ceil(Math.max(dx,dy))}px`, element:selector(el), amount:Math.max(dx,dy)});

            if ((el.scrollWidth > el.clientWidth + 1 || el.scrollHeight > el.clientHeight + 1) &&
                ['hidden','clip'].includes(s.overflow)) {
              issues.push({severity:'error', rule:'layout.clipped', message:'Element clips overflowing content', element:selector(el)});
            }

            if (el.hasAttribute('style') && !el.hasAttribute('data-rs-inline-ok')) {
              issues.push({severity:'warning', rule:'theme.inlineStyle', message:'Inline style bypasses shared theme', element:selector(el)});
            }

            if (s.display === 'grid') {
              const columns = s.gridTemplateColumns.split(/\\s+/).filter(Boolean).length;
              if (columns > \(rules.maximumColumns)) {
                issues.push({severity:'warning', rule:'theme.tooManyColumns',
                  message:`Grid has ${columns} columns; theme maximum is \(rules.maximumColumns)`,
                  element:selector(el), amount:columns});
              }
            }

            const component = el.getAttribute('data-rs-component');
            if (component && allowedComponents.size > 0 && !allowedComponents.has(component)) {
              issues.push({severity:'warning', rule:'theme.unknownComponent',
                message:`Component "${component}" is not declared by the theme`, element:selector(el)});
            }
          }

          const topLevel = [...slide.children].filter(el => {
            if (['SCRIPT','STYLE'].includes(el.tagName) || el.dataset.rsBleed === 'allow') return false;
            const s = getComputedStyle(el), r = el.getBoundingClientRect();
            return s.display !== 'none' && s.visibility !== 'hidden' && r.width > 0 && r.height > 0 && s.position !== 'absolute';
          });
          for (const el of topLevel) {
            const r = el.getBoundingClientRect();
            const nearest = Math.min(r.left-root.left, root.right-r.right, r.top-root.top, root.bottom-r.bottom);
            if (nearest + 1 < \(rules.minimumMarginPx)) {
              issues.push({severity:'warning', rule:'theme.safeMargin',
                message:`Content enters the theme safe margin (${nearest.toFixed(1)}px < \(rules.minimumMarginPx)px)`,
                element:selector(el), amount:nearest});
            }
          }

          for (const parent of [...slide.querySelectorAll('*')].filter(el => el.children.length > 1)) {
            const kids = [...parent.children].filter(el => visible.includes(el) && el.dataset.rsOverlap !== 'allow');
            for (let a=0; a<kids.length; a++) for (let b=a+1; b<kids.length; b++) {
              const x=kids[a].getBoundingClientRect(), y=kids[b].getBoundingClientRect();
              const iw=Math.min(x.right,y.right)-Math.max(x.left,y.left), ih=Math.min(x.bottom,y.bottom)-Math.max(x.top,y.top);
              const area=Math.max(0,iw)*Math.max(0,ih);
              if (area > 64 && getComputedStyle(kids[a]).position !== 'absolute' && getComputedStyle(kids[b]).position !== 'absolute') {
                issues.push({severity:'error', rule:'layout.overlap', message:`Sibling content overlaps by ${Math.round(area)}px²`,
                  element:selector(kids[a])+' <> '+selector(kids[b]), amount:area});
              }
            }
          }

          const clippedDensityRects = densityRects.map(r => ({
            left: Math.max(root.left, r.left),
            right: Math.min(root.right, r.right),
            top: Math.max(root.top, r.top),
            bottom: Math.min(root.bottom, r.bottom)
          })).filter(r => r.right > r.left && r.bottom > r.top);

          const densityXs = [...new Set(
            clippedDensityRects.flatMap(r => [r.left, r.right])
          )].sort((a,b) => a-b);

          let occupied = 0;
          for (let i=0; i<densityXs.length-1; i++) {
            const x1 = densityXs[i], x2 = densityXs[i+1];
            if (x2 <= x1) continue;
            const mid = (x1 + x2) / 2;
            const intervals = clippedDensityRects
              .filter(r => r.left < mid && r.right > mid)
              .map(r => [r.top, r.bottom])
              .sort((a,b) => a[0]-b[0]);
            if (!intervals.length) continue;

            let start = intervals[0][0], end = intervals[0][1], coveredY = 0;
            for (let j=1; j<intervals.length; j++) {
              const [nextStart, nextEnd] = intervals[j];
              if (nextStart <= end) end = Math.max(end, nextEnd);
              else {
                coveredY += end - start;
                start = nextStart;
                end = nextEnd;
              }
            }
            coveredY += end - start;
            occupied += (x2 - x1) * coveredY;
          }

          const text = slide.innerText || '';
          const words = text.trim() ? text.trim().split(/\\s+/).length : 0;
          const occupancy = Math.min(1, occupied / Math.max(1, root.width*root.height));
          if (occupancy > \(rules.densityMaximum))
            issues.push({severity:'error', rule:'density.excessive', message:`Visual density ${(occupancy*100).toFixed(0)}% exceeds maximum`, amount:occupancy});
          else if (occupancy > \(rules.densityWarningMax))
            issues.push({severity:'warning', rule:'density.high', message:`Visual density ${(occupancy*100).toFixed(0)}% is high`, amount:occupancy});

          if (\(rules.requireTitle ? "true" : "false") && !slide.querySelector('h1,h2,[data-rs-title]'))
            issues.push({severity:'warning', rule:'theme.missingTitle', message:'Slide has no title heading'});

          return {words, characters:text.length, elementCount:visible.length, occupancy,
                  smallestFontPx:minFont===9999 ? 0 : minFont, issues};
        })())
        """
    }

    public static let enumerateStates = """
    JSON.stringify((() => {
      const deck = window.__revealswiftDeck || window.Reveal;
      return deck.getSlides().map((s, index) => {
        const p = deck.getIndices(s);
        return { index, h:p.h, v:p.v, fragments:s.querySelectorAll('.fragment').length };
      });
    })())
    """
}
