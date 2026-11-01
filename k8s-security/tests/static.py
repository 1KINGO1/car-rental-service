"""Run: python static.py rendered.yaml (requires PyYAML). No cluster required."""
import sys, json
from pathlib import Path
import yaml
docs=[d for d in yaml.safe_load_all(Path(sys.argv[1]).read_text(encoding='utf-8-sig')) if d]
policies=[d for d in docs if d['kind']=='NetworkPolicy']
deployments=[d for d in docs if d['kind']=='Deployment']
checks=0
def check(condition, message):
    global checks
    assert condition, message
    checks+=1
def matches(selector, labels):
    return all(labels.get(k)==v for k,v in selector.get('matchLabels',{}).items())
def allowed(source, target, port, namespace='car-rental-security'):
    selected=[p for p in policies if matches(p['spec']['podSelector'],{'app':target})]
    if not selected: return True
    for p in selected:
        for rule in p['spec'].get('ingress',[]):
            if not any(x['port']==port and x.get('protocol','TCP')=='TCP' for x in rule['ports']): continue
            for peer in rule['from']:
                ns=peer.get('namespaceSelector')
                if ns is None and namespace!='car-rental-security': continue
                if ns is not None and not matches(ns,{'kubernetes.io/metadata.name':namespace}): continue
                if matches(peer.get('podSelector',{}),{'app':source}): return True
    return False
check(len(deployments)==9,'nine workloads')
check(len(policies)==10,'ten policies')
deny=next(p for p in policies if p['metadata']['name']=='default-deny-ingress')
check(deny['spec']=={'podSelector':{},'policyTypes':['Ingress'],'ingress':[]},'default deny')
for d in deployments:
    spec=d['spec']['template']['spec']; name=d['metadata']['name']
    check(spec['automountServiceAccountToken'] is False,name+' token')
    sc=spec['securityContext']
    check(sc['runAsNonRoot'] and sc['runAsUser']>0 and sc['runAsGroup']>0,name+' non-root')
    check(sc['seccompProfile']['type']=='RuntimeDefault',name+' seccomp')
    for c in spec['containers']:
        sc=c['securityContext']
        check(sc['allowPrivilegeEscalation'] is False and sc['readOnlyRootFilesystem'] is True,name+' container hardening')
        check(sc['capabilities']['drop']==['ALL'],name+' capabilities')
    if name.endswith('-db'):
        mounts={m['mountPath'] for m in spec['containers'][0]['volumeMounts']}
        check({'/tmp','/var/run/postgresql','/var/lib/postgresql/data'}<=mounts,name+' writable mounts')
for service in ['customer','fleet','booking','payment']:
    for source in ['unauthorized','gateway','customer-service','fleet-service','booking-service','payment-service','ingress-controller']:
        check(allowed(source,service+'-db',5432)==(source==service+'-service'),'database isolation')
        check(allowed(source,service+'-service',8080)==(source=='gateway'),'service isolation')
    check(not allowed('gateway',service+'-service',8080,'another-namespace'),'cross namespace gateway')
    check(not allowed(service+'-service',service+'-db',5432,'another-namespace'),'cross namespace owner')
    check(not allowed(service+'-service',service+'-db',5433),'wrong database port')
ingress_namespace=sys.argv[2] if len(sys.argv)>2 else 'ingress-nginx'
check(allowed('ingress-controller','gateway',8080,ingress_namespace),'ingress allowed')
check(not allowed('ingress-controller','gateway',8080,'other'),'wrong ingress namespace')
check(not allowed('other','gateway',8080,ingress_namespace),'wrong ingress app')
check(allowed('booking-service','gateway',8080),'booking internal calls')
check(not allowed('unauthorized','gateway',8080),'gateway isolation')
check(not allowed('unauthorized','new-pod',80),'future pod default deny')
ingress=next(d for d in docs if d['kind']=='Ingress')
check(ingress['spec']['rules'][0]['http']['paths'][0]['backend']['service']['name']=='gateway','ingress target')
print(json.dumps({'result':'PASS','checks':checks,'deployments':len(deployments),'networkPolicies':len(policies),'scope':'static manifests and policy model; not a live CNI test'},indent=2))
