const CACHE='lexisprint-v2';
const CORE=['./','./index.html','./styles.css','./manifest.webmanifest','./icons/icon.svg'];
self.addEventListener('install',event=>event.waitUntil((async()=>{const cache=await caches.open(CACHE);await cache.addAll(CORE);await self.skipWaiting()})()));
self.addEventListener('activate',event=>event.waitUntil((async()=>{const keys=await caches.keys();await Promise.all(keys.filter(key=>key.startsWith('lexisprint-')&&key!==CACHE).map(key=>caches.delete(key)));await self.clients.claim()})()));
self.addEventListener('fetch',event=>{
 const request=event.request;
 if(request.method!=='GET'||new URL(request.url).origin!==location.origin)return;
 const url=new URL(request.url);
 const isAppScript=url.pathname.endsWith('/app.js')||url.pathname.endsWith('/styles.css')||url.pathname.endsWith('/index.html')||url.pathname.endsWith('/sw.js');
 if(isAppScript){
  event.respondWith((async()=>{try{const response=await fetch(request,{cache:'no-store'});if(response.ok&& !url.pathname.endsWith('/sw.js')){const cache=await caches.open(CACHE);await cache.put(request,response.clone())}return response}catch(error){return (await caches.match(request))||Response.error()}})());
  return;
 }
 event.respondWith((async()=>{const cached=await caches.match(request);if(cached)return cached;try{const response=await fetch(request);if(response.ok){const cache=await caches.open(CACHE);await cache.put(request,response.clone())}return response}catch(error){return (await caches.match('./index.html'))||Response.error()}})());
});