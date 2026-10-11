package com.alpdroid.browser

import org.json.JSONObject

/**
 * The JavaScript behind the element commands (`click`, `fill`, `waitfor`, ...). It runs inside the page as the body of
 * an async function, so every command can wait for its element (default 10 s) and report a clear error on timeout.
 * Kept as plain text so it can be syntax-checked outside Android.
 */
object AutomationJs {
    const val DEFAULT_TIMEOUT_MS = 10_000L

    private val LIB = """
const sleep=(ms)=>new Promise(r=>setTimeout(r,ms));
const vis=(el)=>{const r=el.getBoundingClientRect();const s=getComputedStyle(el);return r.width>0&&r.height>0&&s.visibility!=='hidden'&&s.display!=='none'};
const find=(sel)=>{try{return document.querySelector(sel)}catch(e){throw new Error('invalid selector: '+sel)}};
const waitFor=async(sel,state,timeout)=>{const t0=Date.now();for(;;){const el=find(sel);const ok=state==='attached'?!!el:state==='hidden'?(!el||!vis(el)):(!!el&&vis(el));if(ok)return el;if(Date.now()-t0>=timeout)throw new Error('timeout after '+(Date.now()-t0)+'ms waiting for '+sel+' to be '+state);await sleep(50)}};
const center=(el)=>{el.scrollIntoView({block:'center',inline:'center'});const r=el.getBoundingClientRect();return{x:r.left+r.width/2,y:r.top+r.height/2}};
const fire=(t,type,x,y,Cls,extra)=>t.dispatchEvent(new (Cls||MouseEvent)(type,Object.assign({bubbles:true,cancelable:true,composed:true,view:window,clientX:x,clientY:y,button:0,buttons:type.endsWith('down')?1:0},extra||{})));
const setVal=(el,v)=>{const proto=el instanceof HTMLTextAreaElement?HTMLTextAreaElement.prototype:el instanceof HTMLSelectElement?HTMLSelectElement.prototype:HTMLInputElement.prototype;const d=Object.getOwnPropertyDescriptor(proto,'value');if(d&&d.set)d.set.call(el,v);else el.value=v};
const ptr=typeof PointerEvent!=='undefined';
const actions={
async click(a){const el=await waitFor(a.selector,'visible',a.timeout);const c=center(el);let t=document.elementFromPoint(c.x,c.y);if(!t||!(el===t||el.contains(t)||t.contains(el)))t=el;
if(ptr)fire(t,'pointerdown',c.x,c.y,PointerEvent,{pointerId:1,isPrimary:true,pointerType:'mouse'});fire(t,'mousedown',c.x,c.y);if(el.focus)try{el.focus()}catch(e){}
if(ptr)fire(t,'pointerup',c.x,c.y,PointerEvent,{pointerId:1,isPrimary:true,pointerType:'mouse'});fire(t,'mouseup',c.x,c.y);fire(t,'click',c.x,c.y);
return{selector:a.selector,tag:el.tagName.toLowerCase(),x:Math.round(c.x),y:Math.round(c.y)}},
async fill(a){const el=await waitFor(a.selector,'visible',a.timeout);el.scrollIntoView({block:'center'});el.focus();
if(el.isContentEditable){el.textContent=a.text}else if('value' in el){setVal(el,'');el.dispatchEvent(new Event('input',{bubbles:true}));setVal(el,a.text)}else throw new Error('not fillable: '+a.selector);
el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}));return{selector:a.selector,chars:a.text.length}},
async type(a){const el=await waitFor(a.selector,'visible',a.timeout);el.focus();const d=a.delay||0;let n=0;
for(const ch of a.text){const k={key:ch,bubbles:true,cancelable:true};el.dispatchEvent(new KeyboardEvent('keydown',k));el.dispatchEvent(new KeyboardEvent('keypress',k));
if(el.isContentEditable)el.textContent+=ch;else setVal(el,el.value+ch);
el.dispatchEvent(new InputEvent('input',{bubbles:true,data:ch,inputType:'insertText'}));el.dispatchEvent(new KeyboardEvent('keyup',k));n++;if(d)await sleep(d)}
el.dispatchEvent(new Event('change',{bubbles:true}));return{selector:a.selector,typed:n}},
async press(a){const el=document.activeElement||document.body;const key=a.key;const codes={Enter:13,Tab:9,Escape:27,Backspace:8,Delete:46,ArrowDown:40,ArrowUp:38,ArrowLeft:37,ArrowRight:39,' ':32};
const kc=codes[key]||key.charCodeAt(0);const o={key:key,code:key.length===1?'Key'+key.toUpperCase():key,keyCode:kc,which:kc,bubbles:true,cancelable:true};
const go=el.dispatchEvent(new KeyboardEvent('keydown',o));
if(go){if(key==='Enter'){if(el.tagName==='INPUT'&&el.form){if(el.form.requestSubmit)el.form.requestSubmit();else el.form.submit()}else if(el.tagName==='BUTTON'||el.tagName==='A'||el.tagName==='SUMMARY')el.click()}
else if(key==='Tab'){const f=[...document.querySelectorAll('a[href],button,input,select,textarea,[tabindex]')].filter(x=>!x.disabled&&x.tabIndex>=0&&vis(x));if(f.length){const i=f.indexOf(el);f[(i+(a.shift?-1:1)+f.length)%f.length].focus()}}}
el.dispatchEvent(new KeyboardEvent('keyup',o));return{key:key,target:el.tagName.toLowerCase()}},
async text(a){const el=await waitFor(a.selector,'attached',a.timeout);return{selector:a.selector,text:el.innerText!==undefined?el.innerText:el.textContent}},
async attr(a){const el=await waitFor(a.selector,'attached',a.timeout);return{selector:a.selector,name:a.name,value:el.getAttribute(a.name)}},
async exists(a){const el=find(a.selector);return{selector:a.selector,exists:!!el,visible:!!el&&vis(el),count:document.querySelectorAll(a.selector).length}},
async waitfor(a){const t0=Date.now();await waitFor(a.selector,a.state||'visible',a.timeout);return{selector:a.selector,state:a.state||'visible',waitedMs:Date.now()-t0}},
async html(a){if(a.selector){const el=await waitFor(a.selector,'attached',a.timeout);return{html:el.outerHTML}}return{html:'<!DOCTYPE html>\n'+document.documentElement.outerHTML}}
};
const fn=actions[ARGS.cmd];if(!fn)throw new Error('unknown command: '+ARGS.cmd);
return await fn(ARGS);
"""

    /** Body of the async function that runs [cmd] (a JSON object with `cmd`, `selector`, `text`, `timeout`, ...). */
    fun commandBody(args: JSONObject): String {
        val a = JSONObject(args.toString())
        if (!a.has("timeout")) a.put("timeout", DEFAULT_TIMEOUT_MS)
        return "const ARGS=" + a.toString() + ";\n" + LIB
    }

    /**
     * Wraps [body] (the text of an async function body) so its outcome comes back through `__alpbridge.done(id, json)`:
     * evaluateJavascript cannot await a promise, so the script reports in when it settles.
     */
    fun asyncWrapper(id: String, body: String): String =
        "(async()=>{const __id=" + JSONObject.quote(id) + ";let __out;try{const __v=await (async()=>{\n" + body + "\n})();" +
            "__out=JSON.stringify({ok:true,v:(__v===undefined?null:__v)})}catch(e){__out=JSON.stringify({ok:false,error:String((e&&e.message)||e)})}" +
            "try{__alpbridge.done(__id,__out)}catch(e){}})();"

    /** Body for `eval --await`: runs the script text with indirect eval and awaits whatever it evaluates to. */
    fun evalBody(script: String): String = "return await (0,eval)(" + JSONObject.quote(script) + ");"
}
