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
 assert.deepEqual(errors,[],'Real browser must not emit runtime errors');
 console.log('PASS: Actual OpenUI React rendered 3 failures, then 2 in a follow-up chat turn');
}finally{await browser.close();}
