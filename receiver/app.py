import asyncio, json, os, secrets, sqlite3, subprocess, threading, wave, math, struct
from pathlib import Path
from datetime import datetime, timezone
from fastapi import FastAPI, Header, HTTPException, Request
from fastapi.responses import HTMLResponse, StreamingResponse
import yaml

BASE=Path(__file__).resolve().parent
CFG=Path(os.getenv("MESHSDR_CONFIG",BASE/"config.yaml"))
DEFAULT={"token":"CHANGE_ME","database":str(BASE/"messages.sqlite3"),"channels_file":str(BASE/"channels.yaml"),
 "server_alerts":{"enabled":False,"command":"aplay","tts_command":"espeak-ng","tts_text":"SOS MESSAGE INCOMING READ NOW"}}
def load(path,default):
 if not path.exists():
  path.parent.mkdir(parents=True,exist_ok=True); path.write_text(yaml.safe_dump(default,sort_keys=False)); path.chmod(0o600)
 return yaml.safe_load(path.read_text()) or default
C=DEFAULT|load(CFG,DEFAULT)
if os.getenv("MESHSDR_TOKEN"): C["token"]=os.environ["MESHSDR_TOKEN"]
def channel_file(): return Path(C["channels_file"])
def channels(): return load(channel_file(),{"channels":[]}).get("channels",[])
def save_channels(items):
 p=channel_file(); p.parent.mkdir(parents=True,exist_ok=True); p.write_text(yaml.safe_dump({"channels":items},allow_unicode=True,sort_keys=False)); p.chmod(0o600)
def conn():
 p=Path(C["database"]); p.parent.mkdir(parents=True,exist_ok=True)
 d=sqlite3.connect(p,timeout=10); d.row_factory=sqlite3.Row
 d.execute("CREATE TABLE IF NOT EXISTS messages(id TEXT PRIMARY KEY,timestamp TEXT,channel_name TEXT,channel_index INTEGER,sender TEXT,text TEXT,rssi REAL,snr REAL,received_at TEXT)")
 d.commit(); return d
def classify(name):
 aliases={"public":"#public","sos":"#sos","private":"private chat"}
 wanted=aliases.get(name.casefold(),name).casefold()
 for c in channels():
  if c.get("name","").casefold()==wanted: return c.get("display_panel","PUBLIC").upper(),c.get("alert_type","none")
 n=name.casefold()
 if n in ("#sos","sos"): return "SOS","sos-alarm"
 if n in ("private chat","private"): return "PRIVATE CHAT","ping"
 return "PUBLIC","none"
def server_alert(kind):
 o=C.get("server_alerts",{})
 if not o.get("enabled") or kind!="sos-alarm": return
 alarm=BASE/"sos-alarm.wav"
 if not alarm.exists():
  with wave.open(str(alarm),"wb") as w:
   w.setnchannels(1); w.setsampwidth(2); w.setframerate(22050); b=bytearray()
   for i in range(44100):
    t=i/22050; b.extend(struct.pack("<h",int(9500*math.sin(2*math.pi*(880 if int(t*4)%2==0 else 660)*t))))
   w.writeframes(b)
 def run():
  try: subprocess.Popen([o.get("command","aplay"),str(alarm)],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
  except OSError: pass
  try: subprocess.Popen([o.get("tts_command","espeak-ng"),o.get("tts_text","SOS MESSAGE INCOMING READ NOW")],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
  except OSError: pass
 threading.Thread(target=run,daemon=True).start()
def make_alarm():
 p=BASE/"sos-alarm.wav"
 if p.exists(): return
 with wave.open(str(p),"wb") as w:
  w.setnchannels(1);w.setsampwidth(2);w.setframerate(22050);b=bytearray()
  for i in range(44100):
   t=i/22050; b.extend(struct.pack("<h",int(9500*math.sin(2*math.pi*(880 if int(t*4)%2==0 else 660)*t))))
  w.writeframes(b)
app=FastAPI(title="MeshSDR Receiver",docs_url=None,redoc_url=None)
queues=set(); qlock=threading.Lock()
@app.on_event("startup")
def start():
 conn().close()
 if not channel_file().exists(): save_channels([{"name":"#public","key":"","display_panel":"PUBLIC","alert_type":"none"},{"name":"#sos","key":"","display_panel":"SOS","alert_type":"sos-alarm"},{"name":"private chat","key":"","display_panel":"PRIVATE CHAT","alert_type":"ping"}])
@app.post("/api/messages")
async def receive(p:dict,x_meshsdr_token:str|None=Header(default=None)):
 token=str(C.get("token",""))
 if not token or token=="CHANGE_ME" or not secrets.compare_digest(x_meshsdr_token or "",token): raise HTTPException(401,"Invalid token")
 for k in ("id","timestamp","channel_name","sender","text"):
  if k not in p: raise HTTPException(422,"Missing "+k)
 try: datetime.fromisoformat(str(p["timestamp"]).replace("Z","+00:00"))
 except ValueError: raise HTTPException(422,"timestamp must be ISO 8601")
 m={"id":str(p["id"])[:128],"timestamp":str(p["timestamp"]),"channel_name":str(p["channel_name"])[:100],
 "channel_index":int(p.get("channel_index",0)),"sender":str(p["sender"])[:128],"text":str(p["text"])[:4000],
 "rssi":p.get("rssi"),"snr":p.get("snr"),"received_at":datetime.now(timezone.utc).isoformat()}
 d=conn()
 try: d.execute("INSERT OR IGNORE INTO messages VALUES(:id,:timestamp,:channel_name,:channel_index,:sender,:text,:rssi,:snr,:received_at)",m); d.commit()
 finally: d.close()
 panel,alert=classify(m["channel_name"]); event=m|{"panel":panel,"alert_type":alert}
 with qlock: targets=list(queues)
 for q in targets:
  try:q.put_nowait(event)
  except asyncio.QueueFull: pass
 server_alert(alert)
 return {"ok":True,"id":m["id"]}
@app.get("/api/messages")
async def recent(limit:int=100):
 d=conn()
 try: rows=d.execute("SELECT * FROM messages ORDER BY received_at DESC LIMIT ?",(max(1,min(limit,500)),)).fetchall()
 finally: d.close()
 out=[]
 for row in reversed(rows):
  m=dict(row);m["panel"],m["alert_type"]=classify(m["channel_name"]);out.append(m)
 return out
@app.get("/api/events")
async def events(req:Request):
 q=asyncio.Queue(100)
 with qlock: queues.add(q)
 async def gen():
  try:
   yield "retry: 2000\n\n"
   while not await req.is_disconnected():
    try: yield "data: "+json.dumps(await asyncio.wait_for(q.get(),15),ensure_ascii=False)+"\n\n"
    except asyncio.TimeoutError: yield ": keepalive\n\n"
  finally:
   with qlock: queues.discard(q)
 return StreamingResponse(gen(),media_type="text/event-stream",headers={"Cache-Control":"no-cache","X-Accel-Buffering":"no"})
@app.get("/api/channels")
async def get_channels():
 return [{"name":c.get("name",""),"display_panel":c.get("display_panel","PUBLIC"),"alert_type":c.get("alert_type","none"),"has_key":bool(c.get("key"))} for c in channels()]
@app.post("/api/channels")
async def put_channels(req:Request):
 body=await req.json()
 if not isinstance(body.get("channels"),list): raise HTTPException(422,"Expected channels list")
 old={c.get("name","").casefold():c for c in channels()}; clean=[]
 for x in body["channels"]:
  name=str(x.get("name","")).strip()[:100]; panel=x.get("display_panel","PUBLIC"); alert=x.get("alert_type","none")
  if not name or panel not in ("PUBLIC","SOS","PRIVATE CHAT") or alert not in ("none","ping","sos-alarm"): raise HTTPException(422,"Invalid channel")
  key=str(x.get("key") or old.get(name.casefold(),{}).get("key",""))
  clean.append({"name":name,"key":key,"display_panel":panel,"alert_type":alert})
 save_channels(clean); return await get_channels()
@app.get("/health")
async def health(): return {"ok":True}
@app.get("/",response_class=HTMLResponse)
async def home(): return DASH
@app.get("/settings",response_class=HTMLResponse)
async def settings(): return SETTINGS
DASH=r"""<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>MeshSDR Monitor</title><style>
*{box-sizing:border-box}body{margin:0;padding:18px;background:#080d18;color:#edf4ff;font:14px system-ui}header{display:flex;justify-content:space-between;align-items:center;gap:12px;flex-wrap:wrap}h1{font-size:20px}h1 b{color:#39d5e8}button,a{padding:10px 14px;border-radius:10px;background:#1a2b43;color:#fff;border:1px solid #30435e;text-decoration:none;cursor:pointer;font-weight:700}.primary{background:#39d5e8;color:#04131a}.hint{color:#91a4bd;font-size:12px}.grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px}.panel{background:#111b2d;border:1px solid #263750;border-radius:16px;min-height:440px;overflow:hidden}.panel h2{font-size:13px;letter-spacing:.12em;padding:14px;margin:0;border-bottom:1px solid #263750}.messages{padding:10px;display:flex;flex-direction:column;gap:8px;max-height:75vh;overflow:auto}.msg{background:#0b1524;border:1px solid #263750;padding:10px;border-radius:10px;overflow-wrap:anywhere}.meta{display:flex;justify-content:space-between;color:#91a4bd;font-size:11px}.sender{color:#b9eaf0;font-weight:700}.body{white-space:pre-wrap;margin-top:6px;line-height:1.4}.sos .msg{background:#321522;border-color:#873747}.sos h2{color:#ff7b89}.flash{animation:flash .65s infinite alternate;border-color:#ff334f;box-shadow:0 0 28px #ff294b88}@keyframes flash{to{background:#29101a}}#dismiss{display:none;margin:10px;background:#702538}.flash #dismiss{display:block}.empty{text-align:center;color:#6f839d;padding:30px}@media(max-width:850px){.grid{grid-template-columns:1fr}.panel{min-height:250px}.messages{max-height:340px}body{padding:12px}}</style></head><body><header><h1>MeshSDR <b>MONITOR</b></h1><div><button id="sound" class="primary">🔊 Enable sound</button> <a href="/settings" target="_blank">Channels ⚙</a></div></header><p class="hint">Browser audio and speech are blocked until you press Enable sound. SOS flashes until dismissed.</p><div class="grid"><section class="panel" id="p-public"><h2>PUBLIC · #public</h2><div class="messages" id="public"><div class="empty">Waiting for messages…</div></div></section><section class="panel sos" id="p-sos"><h2>SOS · #sos</h2><div class="messages" id="sos"><div class="empty">No SOS messages</div></div><button id="dismiss">DISMISS SOS ALERT</button></section><section class="panel" id="p-private"><h2>PRIVATE CHAT</h2><div class="messages" id="private"><div class="empty">Waiting for messages…</div></div></section></div><script>
let enabled=false,ctx,seen=new Set();const lists={PUBLIC:document.querySelector('#public'),SOS:document.querySelector('#sos'),'PRIVATE CHAT':document.querySelector('#private')};
function beep(f=800,d=.16){if(!enabled||!ctx)return;let o=ctx.createOscillator(),g=ctx.createGain();o.frequency.value=f;g.gain.value=.18;o.connect(g);g.connect(ctx.destination);o.start();o.stop(ctx.currentTime+d)}
function alarm(){document.querySelector('#p-sos').classList.add('flash');beep(920,.35);setTimeout(()=>beep(660,.35),400);if(enabled&&speechSynthesis)speechSynthesis.speak(new SpeechSynthesisUtterance('SOS MESSAGE INCOMING READ NOW'))}
document.querySelector('#sound').onclick=async()=>{ctx=new(window.AudioContext||window.webkitAudioContext)();await ctx.resume();enabled=true;document.querySelector('#sound').textContent='✓ Sound enabled';beep(650,.1)};
document.querySelector('#dismiss').onclick=()=>document.querySelector('#p-sos').classList.remove('flash');
function add(m,alert=true){if(seen.has(m.id))return;seen.add(m.id);let p=lists[m.panel]||lists.PUBLIC;if(p.querySelector('.empty'))p.replaceChildren();let a=document.createElement('article');a.className='msg';let meta=document.createElement('div');meta.className='meta';let who=document.createElement('span');who.className='sender';who.textContent=m.sender;let t=document.createElement('time');t.textContent=new Date(m.timestamp||m.received_at).toLocaleTimeString();meta.append(who,t);let b=document.createElement('div');b.className='body';b.textContent=m.text;a.append(meta,b);p.append(a);p.scrollTop=p.scrollHeight;if(alert&&m.panel==='SOS')alarm();else if(alert&&m.panel==='PRIVATE CHAT')beep(880,.12)}
(async()=>{try{(await(await fetch('/api/messages?limit=100')).json()).forEach(m=>add(m,false))}catch(e){}let es=new EventSource('/api/events');es.onmessage=e=>add(JSON.parse(e.data),true)})();
</script></body></html>"""
SETTINGS=r"""<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Channels</title><style>body{background:#080d18;color:#edf4ff;font:14px system-ui;padding:22px;max-width:1000px;margin:auto}.row{display:grid;grid-template-columns:1.2fr 1fr 1fr 1fr auto auto;gap:8px;margin:10px 0;padding:12px;background:#111b2d;border-radius:12px}input,select{min-width:0;width:100%;padding:10px;background:#0b1524;color:white;border:1px solid #354963;border-radius:8px}button,a{padding:10px;background:#39d5e8;color:#04131a;border:0;border-radius:8px;font-weight:700;text-decoration:none;cursor:pointer}.note{color:#91a4bd}@media(max-width:750px){.row{grid-template-columns:1fr 1fr}}</style></head><body><h1>Channel management</h1><p class="note">Keys are never displayed. Leave key blank to retain the current key. File permissions: 600.</p><div id="rows"></div><button id="add">+ Add</button> <button id="save">Save</button> <a href="/">Dashboard</a><p id="status" class="note"></p><script>
let data=[];function el(tag,opts){let e=document.createElement(tag);if(tag==='select')opts.forEach(x=>{let o=document.createElement('option');o.value=x;o.textContent=x;e.append(o)});return e}
function render(){let root=document.querySelector('#rows');root.replaceChildren();data.forEach((c,i)=>{let r=el('div');r.className='row';let n=el('input');n.value=c.name;n.placeholder='Channel name';let k=el('input');k.type='password';k.placeholder=c.has_key?'Key set · blank keeps':'Optional channel key';let p=el('select',['PUBLIC','SOS','PRIVATE CHAT']);p.value=c.display_panel;let a=el('select',['none','ping','sos-alarm']);a.value=c.alert_type;let state=el('span');state.textContent=c.has_key?'🔒':'—';let d=el('button');d.textContent='Delete';d.onclick=()=>{data.splice(i,1);render()};r.values=()=>({name:n.value,key:k.value,display_panel:p.value,alert_type:a.value});r.append(n,k,p,a,state,d);root.append(r)})}
async function load(){data=await(await fetch('/api/channels')).json();render()}document.querySelector('#add').onclick=()=>{data.push({name:'',display_panel:'PUBLIC',alert_type:'none'});render()};document.querySelector('#save').onclick=async()=>{let channels=[...document.querySelectorAll('.row')].map(r=>r.values());let r=await fetch('/api/channels',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({channels})});document.querySelector('#status').textContent=r.ok?'Saved':'Error: '+await r.text();if(r.ok)load()};load();
</script></body></html>"""
