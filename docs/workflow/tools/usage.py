import json,glob,os,sys
from datetime import datetime,timezone
# Usage: usage.py <session transcript dir>, e.g. ~/.claude/projects/<project>/<session id>
if len(sys.argv)<2: sys.exit("usage: usage.py <session transcript dir>")
P=os.path.expanduser(sys.argv[1].rstrip('/'))
def ts(s): return datetime.fromisoformat(s.replace('Z','+00:00')).timestamp()
PR={'input':4.0,'cw5m':5.0,'cw1h':8.0,'cread':0.20,'output':20.0}
K=list(PR)+['calls']
def scan(f):
    seen={}; times=[]
    for l in open(f):
        try: j=json.loads(l)
        except: continue
        if j.get('timestamp'): times.append(ts(j['timestamp']))
        if j.get('type')!='assistant': continue
        m=j['message']
        if m.get('model')=='<synthetic>': continue
        seen[m.get('id') or j.get('uuid')]=m.get('usage') or {}
    t=dict.fromkeys(K,0)
    for u in seen.values():
        cc=u.get('cache_creation') or {}
        t['input']+=u.get('input_tokens',0); t['cw5m']+=cc.get('ephemeral_5m_input_tokens',0) or 0
        t['cw1h']+=cc.get('ephemeral_1h_input_tokens',0) or 0; t['cread']+=u.get('cache_read_input_tokens',0); t['output']+=u.get('output_tokens',0); t['calls']+=1
    return t,times
names={'wf_25c26c80-0aa':'Understand (map)','wf_e63f2e79-b5e':'Design','wf_419f7490-1d6':'Build wave 1','wf_f434017e-5dd':'Build FE-04..07b','wf_9e53ef42-972':'Build wave 2','wf_c1e9a60c-2fa':'Build wave 3','wf_a67a114e-451':'Build wave 4','wf_70ebdcea-acb':'Test+fix (stopped)','wf_5b4f4d93-dc9':'Test+fix pass','wf_a15a7e4f-020':'E2E test+fix','wf_66cf7d7c-b52':'E2E fix+retest (stopped)','wf_7aff324a-d18':'E2E fix+retest','wf_478d4f9f-858':'E2E final fix','wf_01101c99-094':'Final test (step 4)','wf_a91f8d48-138':'Final test (step 4)'}
groups={}; allev=[]; agents={}
t,tm=scan(P+".jsonl"); groups['Main session']=t; allev+=tm
for f in glob.glob(P+"/subagents/workflows/*/agent-*.jsonl"):
    w=names.get(f.split('/')[-2],f.split('/')[-2]); t,tm=scan(f); allev+=tm; agents[w]=agents.get(w,0)+1
    g=groups.setdefault(w,dict.fromkeys(K,0))
    for k in K: g[k]+=t[k]
cost=lambda t: sum(t[k]*PR[k]/1e6 for k in PR)
G=dict.fromkeys(K,0)
print(f"{'group':20}{'agents':>7}{'calls':>7}{'cache_write':>13}{'cache_read':>13}{'output':>11}{'total_tok':>13}{'cost$':>9}")
for g,s in groups.items():
    for k in K: G[k]+=s[k]
    print(f"{g:20}{agents.get(g,''):>7}{s['calls']:>7}{s['cw5m']+s['cw1h']:>13,}{s['cread']:>13,}{s['output']:>11,}{sum(s[k] for k in PR):>13,}{cost(s):>9.2f}")
print(f"{'TOTAL':20}{sum(agents.values()):>7}{G['calls']:>7}{G['cw5m']+G['cw1h']:>13,}{G['cread']:>13,}{G['output']:>11,}{sum(G[k] for k in PR):>13,}{cost(G):>9.2f}")
allev.sort()
act=sum(allev[i+1]-allev[i] for i in range(len(allev)-1) if allev[i+1]-allev[i]<=300)
print('active time (gaps>5min excluded): %dh %02dm'%(act//3600,(act%3600)//60), '| wall span %.1fh'%((allev[-1]-allev[0])/3600), '| now', datetime.now(timezone.utc).strftime('%H:%M UTC'))
