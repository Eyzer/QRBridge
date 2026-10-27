const CACHE='qrbirdge-rx-v1';
self.addEventListener('install',e=>{self.skipWaiting();});
self.addEventListener('activate',e=>{e.waitUntil(self.clients.claim());});
self.addEventListener('fetch',e=>{
  if(e.request.method!=='GET')return;
  e.respondWith((async()=>{
    const cache=await caches.open(CACHE);
    let r=await cache.match(e.request);
    if(r)return r;
    try{r=await fetch(e.request);if(r&&r.ok)cache.put(e.request,r.clone());return r;}catch(_){}
    return r||new Response('离线',{status:503});
  })());
});
