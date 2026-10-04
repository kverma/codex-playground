import Foundation

public enum InspectorScript {
    public static func javascript(rules: ThemeRules) -> String {
        """
        (() => {
          const slide = Reveal.getCurrentSlide();
          if (!slide) return {error: 'No active slide'};
          const root = slide.getBoundingClientRect();
          const visible = [...slide.querySelectorAll('*')].filter(el => {
            const s = getComputedStyle(el); const r = el.getBoundingClientRect();
            return s.display !== 'none' && s.visibility !== 'hidden' && Number(s.opacity) !== 0 && r.width > 0 && r.height > 0;
          });
          const selector = el => {
            if (el.id) return '#' + CSS.escape(el.id);
            const cls = [...el.classList].slice(0,2).map(c => '.' + CSS.escape(c)).join('');
            return el.tagName.toLowerCase() + cls;
          };
          const issues = [];
          let minFont = 9999;
          let occupied = 0;
          for (const el of visible) {
            const r = el.getBoundingClientRect(); const s = getComputedStyle(el);
            occupied += Math.max(0, Math.min(r.right,root.right)-Math.max(r.left,root.left)) * Math.max(0, Math.min(r.bottom,root.bottom)-Math.max(r.top,root.top));
            const fs = parseFloat(s.fontSize || '9999'); if (el.textContent.trim()) minFont = Math.min(minFont, fs);
            const dx = Math.max(root.left-r.left, r.right-root.right, 0);
            const dy = Math.max(root.top-r.top, r.bottom-root.bottom, 0);
            if (dx > 1 || dy > 1) issues.push({severity:'error', rule:'layout.outOfBounds', message:`Element exceeds slide bounds by ${Math.ceil(Math.max(dx,dy))}px`, element:selector(el), amount:Math.max(dx,dy)});
            if ((el.scrollWidth > el.clientWidth + 1 || el.scrollHeight > el.clientHeight + 1) && ['hidden','clip'].includes(s.overflow)) {
              issues.push({severity:'error', rule:'layout.clipped', message:'Element clips overflowing content', element:selector(el)});
            }
          }
          const text = slide.innerText || '';
          const words = text.trim() ? text.trim().split(/\\s+/).length : 0;
          const occupancy = Math.min(1, occupied / Math.max(1, root.width*root.height*1.8));
          if (minFont !== 9999 && minFont < \(rules.minimumCaptionFontPx)) issues.push({severity:'warning', rule:'theme.fontTooSmall', message:`Smallest text is ${minFont.toFixed(1)}px`, amount:minFont});
          if (occupancy > \(rules.densityMaximum)) issues.push({severity:'error', rule:'density.excessive', message:`Visual density ${(occupancy*100).toFixed(0)}% exceeds maximum`, amount:occupancy});
          else if (occupancy > \(rules.densityWarningMax)) issues.push({severity:'warning', rule:'density.high', message:`Visual density ${(occupancy*100).toFixed(0)}% is high`, amount:occupancy});
          if (\(rules.requireTitle ? "true" : "false") && !slide.querySelector('h1,h2,[data-rs-title]')) issues.push({severity:'warning', rule:'theme.missingTitle', message:'Slide has no title heading'});
          return {words, characters:text.length, elementCount:visible.length, occupancy, smallestFontPx:minFont===9999 ? 0 : minFont, issues};
        })()
        """
    }

    public static let enumerateStates = """
    (() => Reveal.getSlides().map((s, index) => ({ index, fragments: s.querySelectorAll('.fragment').length })))()
    """
}
