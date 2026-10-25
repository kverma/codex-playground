import React from 'react';
import { defineComponent } from '@openuidev/react-lang';
import { openuiLibrary } from '@openuidev/react-ui';
import { z } from 'zod/v4';

// ALL upstream OpenUI base components are inherited, not hand-picked or recreated.
const panel: React.CSSProperties={border:'1px solid #c6d2e0',borderRadius:14,padding:16,background:'var(--panel,#fff)'};
const safeUrl=(v:string)=>{try{const x=new URL(v);return x.protocol==='https:'?v:undefined;}catch{return undefined;}};
const MetricCard=defineComponent({name:'MetricCard',description:'Value from a verified data binding',props:z.object({label:z.string(),value:z.string(),detail:z.string().optional()}),component:({props})=><article style={panel}><small>{props.label}</small><h2>{props.value}</h2><small>{props.detail}</small></article>});
const StatusPill=defineComponent({name:'StatusPill',description:'Status with textual label',props:z.object({label:z.string(),status:z.string()}),component:({props})=><span className={'pill '+props.status.toLowerCase()}>{props.label}</span>});
const IncidentCard=defineComponent({name:'IncidentCard',description:'Incident and evidence link',props:z.object({title:z.string(),status:z.string(),service:z.string(),url:z.string()}),component:({props})=><article style={panel}><strong>{props.title}</strong><p>{props.service} · {props.status}</p><a href={safeUrl(props.url)} target="_blank" rel="noopener noreferrer">View incident</a></article>});
const BuildRunCard=defineComponent({name:'BuildRunCard',description:'CI run and source link',props:z.object({name:z.string(),conclusion:z.string(),repository:z.string(),url:z.string()}),component:({props})=><article style={panel}><strong>{props.name}</strong><p>{props.repository} · {props.conclusion}</p><a href={safeUrl(props.url)} target="_blank" rel="noopener noreferrer">View build</a></article>});
const EvidenceCard=defineComponent({name:'EvidenceCard',description:'Source-backed evidence',props:z.object({title:z.string(),snippet:z.string(),sourceUrl:z.string()}),component:({props})=><article style={panel}><strong>{props.title}</strong><p>{props.snippet}</p><a href={safeUrl(props.sourceUrl)} target="_blank" rel="noopener noreferrer">Source</a></article>});
const ServiceHealth=defineComponent({name:'ServiceHealth',description:'Health of a service',props:z.object({service:z.string(),health:z.string(),detail:z.string()}),component:({props})=><article style={panel}><strong>{props.service}</strong><p>{props.health}</p><small>{props.detail}</small></article>});
const TimelineEvent=defineComponent({name:'TimelineEvent',description:'Time-stamped event',props:z.object({time:z.string(),title:z.string(),detail:z.string()}),component:({props})=><article style={panel}><time>{props.time}</time><h4>{props.title}</h4><p>{props.detail}</p></article>});
const KeyValue=defineComponent({name:'KeyValue',description:'Key and value',props:z.object({label:z.string(),value:z.string()}),component:({props})=><div><span>{props.label}: </span><strong>{props.value}</strong></div>});
const JsonInspector=defineComponent({name:'JsonInspector',description:'Read-only structured JSON inspector',props:z.object({title:z.string(),json:z.string()}),component:({props})=><article style={panel}><h4>{props.title}</h4><pre>{props.json}</pre></article>});
const EmptyState=defineComponent({name:'EmptyState',description:'No data, failure or denied operation',props:z.object({title:z.string(),message:z.string()}),component:({props})=><article style={panel}><h3>{props.title}</h3><p>{props.message}</p></article>});
const SongCard=defineComponent({name:'SongCard',description:'Song in an album',props:z.object({title:z.string(),artist:z.string(),duration:z.string()}),component:({props})=><article style={panel}><b>{props.title}</b><p>{props.artist} · {props.duration}</p></article>});
const AlbumCard=defineComponent({name:'AlbumCard',description:'Composes nested song cards',props:z.object({title:z.string(),artist:z.string(),songs:z.array(SongCard.ref)}),component:({props,renderNode})=><article style={panel}><h3>{props.title}</h3><p>{props.artist}</p>{renderNode(props.songs)}</article>});
const BookCard=defineComponent({name:'BookCard',description:'Book details',props:z.object({title:z.string(),author:z.string(),isbn:z.string()}),component:({props})=><article style={panel}><h3>{props.title}</h3><p>{props.author}</p><small>{props.isbn}</small></article>});
const OfferCard=defineComponent({name:'OfferCard',description:'Commercial offer',props:z.object({name:z.string(),price:z.string(),eligibility:z.string()}),component:({props})=><article style={panel}><b>{props.name}</b><h3>{props.price}</h3><p>{props.eligibility}</p></article>});
const SubscriptionDealCard=defineComponent({name:'SubscriptionDealCard',description:'B2B subscription pricing',props:z.object({customer:z.string(),sku:z.string(),price:z.string(),cohort:z.string()}),component:({props})=><article style={panel}><strong>{props.customer}</strong><p>{props.sku}: {props.price}</p><small>{props.cohort}</small></article>});
const AtlasSkuCard=defineComponent({name:'AtlasSkuCard',description:'Per-country SKU commercial state',props:z.object({sku:z.string(),country:z.string(),availability:z.string(),offerCount:z.string()}),component:({props})=><article style={panel}><strong>{props.sku}</strong><p>{props.country} · {props.availability}</p><small>{props.offerCount} offers</small></article>});

/* App Store-style components are explicitly MOCK and use Zod-typed structured props. */
const appSummary=z.object({
 id:z.string(),name:z.string(),developer:z.string(),subtitle:z.string(),
 category:z.string(),icon:z.string(),color:z.string(),price:z.string(),
 rating:z.number(),ratingCount:z.number(),platforms:z.array(z.string())
});
const appDetails=appSummary.extend({
 description:z.string(),features:z.array(z.string()),whatsNew:z.string(),
 ageRating:z.string(),size:z.string(),version:z.string(),privacy:z.array(z.string()),
 screenshots:z.array(z.object({caption:z.string(),color:z.string(),symbol:z.string()}))
});
type Summary=z.infer<typeof appSummary>;
const appMessage=(text:string)=>window.dispatchEvent(new CustomEvent('appstore:chat',{detail:text}));
const AppTile=({app}:{app:Summary})=><article data-testid={'app-summary-'+app.id} className="app-tile">
 <div className="app-icon" style={{background:app.color}}>{app.icon}</div>
 <div className="app-copy"><strong>{app.name}</strong><small>{app.subtitle}</small><small>{app.developer}</small>
 <span className="app-stars">★ {app.rating.toFixed(1)} <span>({app.ratingCount.toLocaleString()} ratings)</span></span></div>
 <div className="app-controls"><span className="app-price">{app.price}</span><button type="button" onClick={()=>appMessage('Show details for '+app.name)}>View details</button></div>
 </article>;
const AppSummaryCard=defineComponent({name:'AppSummaryCard',description:'Single App Store-like search result (mock data)',props:z.object({app:appSummary}),component:({props})=><AppTile app={props.app}/>});
const AppSearchGrid=defineComponent({name:'AppSearchGrid',description:'Responsive gallery of mock App Store app summary cards',props:z.object({query:z.string(),apps:z.array(appSummary)}),component:({props})=><section data-testid="app-search-grid" className="app-store">
 <div className="app-store-head"><div><span className="app-eyebrow">DISCOVER APPS</span><h2>{props.query?('Results for “'+props.query+'”'):'Explore apps'}</h2><p>{props.apps.length} matching apps · Illustrative catalog</p></div><span className="mock-ribbon">MOCK DATA</span></div>
 <div className="app-grid">{props.apps.map(app=><AppTile key={app.id} app={app}/>)}</div>
 {props.apps.length===0&&<p className="app-empty">No mock apps match those filters. Try “drawing”, “productivity”, or “travel”.</p>}
 </section>});
const AppDetailView=defineComponent({name:'AppDetailView',description:'Detailed Apple App Store-style app page with screenshot previews',props:z.object({app:appDetails.nullable()}),component:({props})=>{
 const app=props.app;if(!app)return <article data-testid="app-details-missing" className="app-store">This mock app was not found.</article>;
 return <section className="app-store app-detail" data-testid={'app-detail-'+app.id}>
  <div className="app-detail-hero"><div className="app-icon large" style={{background:app.color}}>{app.icon}</div>
   <div className="app-detail-title"><span className="app-eyebrow">APP STORE PREVIEW · MOCK</span><h2>{app.name}</h2><p>{app.subtitle}</p><small>{app.developer}</small></div>
   <div className="app-get"><strong>{app.price}</strong><button type="button" onClick={()=>appMessage('Compare the first two apps')}>Compare</button></div>
  </div>
  <div className="app-stats"><div><strong>{app.rating.toFixed(1)} ★</strong><small>{app.ratingCount.toLocaleString()} Ratings</small></div><div><strong>{app.ageRating}</strong><small>Age Rating</small></div><div><strong>{app.size}</strong><small>Size</small></div><div><strong>{app.version}</strong><small>Version</small></div></div>
  <div className="app-shots">{app.screenshots.map((shot,i)=><div key={i} className="app-shot" style={{background:shot.color}}><div className="app-screen-symbol">{shot.symbol}</div><span>{shot.caption}</span></div>)}</div>
  <h3>About this app</h3><p className="app-description">{app.description}</p>
  <h3>Features</h3><div className="app-features">{app.features.map(feature=><span key={feature}>✓ {feature}</span>)}</div>
  <h3>What's New</h3><p className="app-description">{app.whatsNew}</p>
  <h3>App Privacy</h3><p className="app-description">{app.privacy.join(' · ')}</p>
  <p className="app-footer">Supported: {app.platforms.join(', ')} · All listing details are fictional test data.</p>
 </section>;
}});
const AppComparisonView=defineComponent({name:'AppComparisonView',description:'Deterministic cross-app comparison',props:z.object({apps:z.array(appSummary)}),component:({props})=><section className="app-store" data-testid="app-comparison">
 <div className="app-store-head"><div><span className="app-eyebrow">SIDE BY SIDE</span><h2>Compare apps</h2><p>All values come from the mock MCP response</p></div><span className="mock-ribbon">MOCK DATA</span></div>
 <div className="app-comparison-grid">{props.apps.map(app=><div key={app.id} className="app-comparison-col" data-testid={'app-compare-'+app.id}>
 <div className="app-icon" style={{background:app.color}}>{app.icon}</div><h3>{app.name}</h3><p>{app.subtitle}</p>
 <dl><dt>Price</dt><dd>{app.price}</dd><dt>Rating</dt><dd>★ {app.rating.toFixed(1)}</dd><dt>Category</dt><dd>{app.category}</dd><dt>Platforms</dt><dd>{app.platforms.join(', ')}</dd></dl>
 <button type="button" onClick={()=>appMessage('Show details for '+app.name)}>View details</button>
 </div>)}</div>
 </section>});


const qualitySchema=z.object({
 status:z.enum(['ok','partial','unavailable']),source:z.string(),
 fields:z.record(z.string(),z.object({status:z.enum(['present','coerced','extracted','missing','invalid','recovered','unavailable']),source:z.string(),reason:z.string().optional(),evidence:z.string().optional()}))
});
const AppRichDetailView=defineComponent({
 name:'AppRichDetailView',
 description:'Multi-source rich app detail: independently retrieved reviews, pricing, privacy and device compatibility',
 props:z.object({
  app:appDetails.nullable(),
  reviews:z.object({items:z.array(z.object({author:z.string(),rating:z.number(),title:z.string(),body:z.string()})),status:z.string(),source:z.string(),quality:qualitySchema.optional()}),
  privacy:z.object({report:z.object({collects:z.string(),tracking:z.string(),notes:z.string()}),status:z.string(),source:z.string(),quality:qualitySchema.optional()}),
  pricing:z.object({offer:z.object({price:z.string(),currency:z.string(),purchaseType:z.string()}),status:z.string(),source:z.string(),quality:qualitySchema.optional()}),
  compatibility:z.object({devices:z.array(z.object({name:z.string(),supported:z.boolean()})),status:z.string(),source:z.string(),quality:qualitySchema.optional()})
 }),
 component:({props})=>{
  const app=props.app;
  if(!app)return <section data-testid="rich-app-missing" className="app-store">App not found in the mock catalog.</section>;
  const status=(v:string)=><span className={'rich-source '+(v==='ok'?'ok':'missing')}>{v==='ok'?'Available':v==='partial'?'Partial data':'Unavailable'}</span>;
  return <section className="app-store rich-detail" data-testid={'rich-app-'+app.id}>
    <div className="app-detail-hero"><div className="app-icon large" style={{background:app.color}}>{app.icon}</div><div className="app-detail-title"><span className="app-eyebrow">MULTI-SOURCE MCP · MOCK DATA</span><h2>{app.name}</h2><p>{app.subtitle}</p><small>{app.developer}</small></div></div>
    <p className="app-description">{app.description}</p>
    <div className="rich-sections">
      <article className="rich-section" data-testid="rich-pricing"><header><b>Price & purchase</b>{status(props.pricing.status)}</header><h3>{props.pricing.offer.price}</h3><p>{props.pricing.offer.purchaseType} · {props.pricing.offer.currency}</p>
      {props.pricing.quality?.fields['offer.price']&&<small data-testid="price-field-quality">Price field: {props.pricing.quality.fields['offer.price'].status}</small>}<small>Source: {props.pricing.source}</small></article>
      <article className="rich-section" data-testid="rich-reviews"><header><b>Reviews</b>{status(props.reviews.status)}</header>{props.reviews.items.map((review,i)=><div key={i} className="rich-review"><b>★ {review.rating} · {review.title}</b><p>{review.body}</p><small>{review.author}</small></div>)}{!props.reviews.items.length&&<p>Review data unavailable; app details are still shown.</p>}<small>Source: {props.reviews.source}</small></article>
      <article className="rich-section" data-testid="rich-privacy"><header><b>App privacy</b>{status(props.privacy.status)}</header><p>Collection: {props.privacy.report.collects}</p><p>Tracking: {props.privacy.report.tracking}</p>
      {props.privacy.quality?.fields['report.tracking']&&<small data-testid="privacy-tracking-quality">Tracking field: {props.privacy.quality.fields['report.tracking'].status}</small>}
      <small>{props.privacy.report.notes}</small></article>
      <article className="rich-section" data-testid="rich-devices"><header><b>Device compatibility</b>{status(props.compatibility.status)}</header>{props.compatibility.devices.map(d=><p key={d.name}>{d.supported?'✓':'×'} {d.name}</p>)}{!props.compatibility.devices.length&&<p>Compatibility information unavailable.</p>}<small>Source: {props.compatibility.source}</small></article>
    </div>
    <p className="app-footer">All information is fictional test data from separate mock MCP servers.</p>
  </section>;
 }
});

export const customComponents=[MetricCard,StatusPill,IncidentCard,BuildRunCard,EvidenceCard,ServiceHealth,TimelineEvent,KeyValue,JsonInspector,EmptyState,SongCard,AlbumCard,BookCard,OfferCard,SubscriptionDealCard,AtlasSkuCard,AppSummaryCard,AppSearchGrid,AppDetailView,AppComparisonView,AppRichDetailView];
export const library=openuiLibrary.extend!({components:{add:customComponents}});
export { openuiLibrary };
