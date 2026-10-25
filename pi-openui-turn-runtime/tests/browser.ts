import {chromium} from 'playwright';
import {mkdir} from 'node:fs/promises';
import assert from 'node:assert/strict';
const browser=await chromium.launch({headless:true});
try{
 const page=await browser.newPage({viewport:{width:1280,height:900},deviceScaleFactor:1});
 const errors:string[]=[];
 page.on('pageerror',e=>errors.push(e.message));
 page.on('console',m=>{if(m.type()==='error')errors.push(m.text());});
 await page.goto('http://127.0.0.1:5173',{waitUntil:'networkidle'});
 await page.getByRole('button',{name:'Send',exact:true}).click();
 await page.getByText('Failed CI builds · atlas').first().waitFor({timeout:20000});
 const first=page.locator('.message.assistant article h2').first();
 await first.waitFor({timeout:20000});
 assert.equal((await first.innerText()).trim(),'3','Initial chat should render MetricCard with 3 fixture failures');
 await mkdir('artifacts',{recursive:true});
 await page.screenshot({path:'artifacts/first-turn.png',fullPage:true});
 await page.getByRole('textbox',{name:'Chat'}).fill('Only for main branch');
 await page.getByRole('button',{name:'Send',exact:true}).click();
 const second=page.locator('.message.assistant').nth(1).locator('article h2').first();
 await second.waitFor({timeout:20000});
 assert.equal((await second.innerText()).trim(),'2','Follow-up should preserve repository and filter to main');
 await page.screenshot({path:'artifacts/follow-up.png',fullPage:true});

 // The remaining flow uses a REAL MCP client/server and REAL OpenUI renderer.
 const input=page.getByRole('textbox',{name:'Chat'});
 const send=page.getByRole('button',{name:'Send',exact:true});
 await input.fill('Find drawing apps');await send.click();
 const grid=page.getByTestId('app-search-grid').last();
 await grid.waitFor({timeout:20000});
 assert.equal(await grid.locator('[data-testid^="app-summary-"]').count(),2,'Two mock drawing apps render');
 assert.match(await grid.innerText(),/SketchFlow Studio/);
 await page.screenshot({path:'artifacts/app-search.png',fullPage:true});
 // The app card button prepares the next chat turn.
 await grid.getByRole('button',{name:'View details'}).first().click();
 assert.match(await input.inputValue(),/Show details for SketchFlow Studio/);
 await send.click();
 const firstDetail=page.getByTestId('app-detail-sketchflow').last();
 await firstDetail.waitFor({timeout:20000});
 assert.match(await firstDetail.innerText(),/Layer-based illustration/);
 await page.screenshot({path:'artifacts/app-detail.png',fullPage:true});
 await input.fill('Tell me more about the second app');await send.click();
 await page.getByTestId('app-detail-pixelcraft').last().waitFor({timeout:20000});
 await input.fill('Compare the first two apps');await send.click();
 const comparison=page.getByTestId('app-comparison').last();await comparison.waitFor({timeout:20000});
 assert.equal(await comparison.locator('[data-testid^="app-compare-"]').count(),2);
 assert.match(await comparison.innerText(),/PixelCraft Pro/);
 await page.screenshot({path:'artifacts/app-comparison.png',fullPage:true});
 await input.fill('Only free ones');await send.click();
 const freeGrid=page.getByTestId('app-search-grid').last();
 await page.waitForFunction(()=>document.querySelectorAll('[data-testid="app-search-grid"]').length===2,{timeout:20000});
 assert.equal(await freeGrid.locator('[data-testid^="app-summary-"]').count(),1);
 assert.match(await freeGrid.innerText(),/PixelCraft Pro/);
 await page.screenshot({path:'artifacts/app-free-filter.png',fullPage:true});
 assert.deepEqual(errors,[],'Real browser must not emit runtime errors');
 console.log('PASS: Real OpenUI React + MCP: CI follow-up and App Store search → detail → detail → compare → filter');
}finally{await browser.close();}
