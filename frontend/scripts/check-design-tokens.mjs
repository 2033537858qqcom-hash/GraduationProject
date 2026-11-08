import { readFileSync, readdirSync, existsSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import path from 'node:path'
const root=fileURLToPath(new URL('../src/',import.meta.url))
const walk=d=>readdirSync(d,{withFileTypes:true}).flatMap(e=>e.isDirectory()?walk(path.join(d,e.name)):[path.join(d,e.name)])
const files=walk(root).filter(f=>/\.(css|vue)$/.test(f))
const definitions=new Set(),references=[],invalidFontAliases=[],report={files:files.length,tokenDefinitions:0,tokenReferences:0,literalColors:0,important:0,literalSmallFontDeclarations:0}
for(const f of files){
 const source=readFileSync(f,'utf8')
 const css=f.endsWith('.css')?source:[...source.matchAll(/<style\b[^>]*>([\s\S]*?)<\/style>/g)].map(m=>m[1]).join('\n')
 for(const m of css.matchAll(/(--[\w-]+)\s*:/g))definitions.add(m[1])
 for(const m of css.matchAll(/--(site-serif|campus-serif)\s*:\s*([^;}\r\n]+)/g))if(m[2].trim()!=='var(--campus-display-serif)')invalidFontAliases.push({file:path.relative(root,f),alias:m[1],value:m[2]})
 for(const m of css.matchAll(/var\((--[\w-]+)/g))references.push({name:m[1],file:path.relative(root,f)})
 report.literalColors+=(css.match(/#[\da-f]{3,8}\b/gi)||[]).length
 report.important+=(css.match(/!important/g)||[]).length
 report.literalSmallFontDeclarations+=[...css.matchAll(/font-size:\s*([\d.]+)px\b/g)].filter(m=>Number(m[1])<=12).length
}
const system=/^--(?:color-|radius-|space-|text-|font-family-|shadow-|focus-ring|campus-display-serif$|site-serif$|campus-serif$)/
const missing=references.filter(r=>system.test(r.name)&&!definitions.has(r.name))
const tokens=readFileSync(path.join(root,'styles/tokens.css'),'utf8')
for(const name of ['site-serif','campus-serif'])if(!new RegExp('--'+name+':\\s*var\\(--campus-display-serif\\)').test(tokens))throw new Error('Unresolved font alias '+name)
report.tokenDefinitions=definitions.size;report.tokenReferences=references.length
report.systemTokenReferences=references.filter(r=>system.test(r.name)).length
const family=tokens.match(/--font-family-display:\s*['"]([^'"]+)/)?.[1]
const face=readFileSync(path.join(root,'styles/display-font.css'),'utf8')
const fontFamilyAvailable=!!family&&face.includes('font-family: '+"'"+family+"'")
const fontPath=face.match(/url\(['"]?([^)'"\s]+)/)?.[1]
if(!fontFamilyAvailable||!fontPath||!existsSync(path.resolve(root,'styles',fontPath)))throw new Error('Display font family or asset is unavailable')
console.log(JSON.stringify({...report,undefinedSystemTokens:missing,invalidFontAliases,fontFamilyAvailable},null,2))
const budget=JSON.parse(readFileSync(fileURLToPath(new URL('../design-budget.json',import.meta.url)),'utf8'))
const exceeded=Object.entries(budget).filter(([key,max])=>report[key]>max).map(([key,max])=>key+': '+report[key]+' > '+max)
if(exceeded.length)console.error('Design debt budget exceeded:',exceeded.join(', '))
if(missing.length||invalidFontAliases.length||exceeded.length)process.exitCode=1
