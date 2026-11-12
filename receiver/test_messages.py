#!/usr/bin/env python3
import argparse, json, secrets, urllib.request, urllib.error
from datetime import datetime, timezone

p=argparse.ArgumentParser(description="Send demo MeshCore messages to the local receiver")
p.add_argument("--url",default="http://127.0.0.1:8080/api/messages")
p.add_argument("--token",required=True)
a=p.parse_args()
samples=[
 ("#public","PUBLIC","No sound: public channel test"),
 ("#sos","SOS","Test SOS alert — this is a demonstration"),
 ("private chat","PRIVATE CHAT","Private channel ping test"),
]
for i,(channel,panel,message) in enumerate(samples):
 data={"id":"test-"+secrets.token_hex(8),"timestamp":datetime.now(timezone.utc).isoformat(),
       "channel_name":channel,"channel_index":i,"sender":"MeshSDR TEST","text":message,"rssi":None,"snr":8.5}
 req=urllib.request.Request(a.url,data=json.dumps(data).encode(),headers={"Content-Type":"application/json","X-MeshSDR-Token":a.token},method="POST")
 try:
  with urllib.request.urlopen(req,timeout=5) as r: print(channel,r.status,r.read().decode())
 except urllib.error.HTTPError as e: print(channel,"ERROR",e.code,e.read().decode())
