#!/usr/bin/env python3
"""Local-only registration, FIFO promotion and QR check-in experiment.
Creates 100 test accounts and a one-place event. Use a disposable database.
"""
import argparse
import concurrent.futures
import json
import math
import os
import statistics
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--url', default='http://127.0.0.1:8081')
parser.add_argument('--output', default='docs/contention-result.json')
parser.add_argument('--database-label', default='unspecified')
args = parser.parse_args()
if urllib.parse.urlparse(args.url).hostname not in ('127.0.0.1', 'localhost', '::1'):
    parser.error('Only a disposable local test server is permitted.')

organization_id=None

def request(path, body=None, token=None, method=None):
    headers = {'Content-Type': 'application/json'}
    if organization_id:
        headers['X-Organization-ID'] = organization_id
    if token:
        headers['Authorization'] = 'Bearer ' + token
    req = urllib.request.Request(args.url+'/api'+path,
        data=json.dumps(body).encode() if body is not None else None,
        headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=30) as response:
            return response.status, json.load(response)
    except urllib.error.HTTPError as error:
        return error.code, json.load(error)

def race(items, operation):
    gate = threading.Barrier(len(items))
    def worker(item):
        gate.wait(timeout=20)
        return operation(item)
    with concurrent.futures.ThreadPoolExecutor(max_workers=len(items)) as pool:
        return list(pool.map(worker, items))

def verify_email(token):
    status, sent=request('/verification/send', {}, token)
    assert status==200, sent
    status, inbox=request('/verification/test-inbox', token=token)
    assert status==200 and 'code' in inbox, inbox
    status, verified=request('/verification/confirm', {'code':inbox['code']}, token)
    assert status==200 and verified['verified'], verified

status, config=request('/auth/config')
assert status==200 and config['mode']=='development', 'This experiment uses the local test inbox only.'
status, admin=request('/organizer/register', {'name':'Test Organizer', 'email':uuid.uuid4().hex+'@load.example.test', 'password':'test-only-password-123'})
assert status==200, admin
verify_email(admin['token'])
status, organization=request('/organizer/organizations', {'name':'Load Test College', 'emailDomain':'load.example.test'}, admin['token'])
assert status==200, organization
organization_id=organization['id']
status, event = request('/admin/events', {
    'title':'Last place experiment', 'category':'Workshop', 'venue':'Local test only',
    'description':'100 students compete for one place; cancellation promotes the first waiting student.',
    'startsAt':int(time.time()*1000)+3600000, 'capacity':1, 'seatsPerRow':10,
    'speaker':'Test speaker', 'hostClub':'Test club', 'bookingMode':'GENERAL', 'audience':'COLLEGE'
}, admin['token'])
assert status == 200, event
students=[]
for _ in range(100):
    identity=uuid.uuid4().hex
    status, session=request('/auth/register', {'name':'Test Student', 'email':identity+'@load.example.test',
        'studentNumber':identity[:24], 'password':'test-only-password-123'})
    assert status == 200, session
    verify_email(session['token'])
    students.append({'token':session['token'], 'key':str(uuid.uuid4())})

def signup(student):
    started=time.perf_counter()
    status, result=request('/events/'+event['id']+'/registrations', {'requestKey':student['key']}, student['token'])
    return status, result, (time.perf_counter()-started)*1000, student

results=race(students, signup)
assert all(r[0]==200 for r in results), [(r[0],r[1]) for r in results if r[0]!=200]
confirmed=[r for r in results if r[1]['status']=='CONFIRMED']
waiting=sorted([r for r in results if r[1]['status']=='WAITLISTED'], key=lambda r:r[1]['waitlistPosition'])
assert len(confirmed)==1 and len(waiting)==99
assert [r[1]['waitlistPosition'] for r in waiting]==list(range(1,100))
winner=confirmed[0]
retries=race(list(range(20)), lambda _:signup(winner[3]))
assert all(r[0]==200 and r[1]['id']==winner[1]['id'] for r in retries)
status, cancelled=request('/registrations/'+winner[1]['id'],token=winner[3]['token'],method='DELETE')
assert status==200 and cancelled['status']=='CANCELLED'
status, promoted_list=request('/registrations',token=waiting[0][3]['token'])
promoted=next(e for e in promoted_list if e['eventId']==event['id'])
assert status==200 and promoted['status']=='CONFIRMED' and promoted['promotedAt'] is not None
assert promoted['id']==waiting[0][1]['id'] and promoted['qrPayload']
status, second_list=request('/registrations',token=waiting[1][3]['token'])
assert status==200 and second_list[0]['waitlistPosition']==1
scans=race(list(range(20)),lambda _:request('/admin/events/'+event['id']+'/check-in', {'qrPayload':promoted['qrPayload']},admin['token']))
assert all(r[0]==200 for r in scans),scans
first_scans=sum(not r[1]['alreadyCheckedIn'] for r in scans)
assert first_scans==1
status, invalid=request('/admin/events/'+event['id']+'/check-in', {'qrPayload':winner[1]['qrPayload']},admin['token'])
assert status==404 and invalid['code']=='INVALID_PASS'
latencies=sorted(r[2] for r in results)
report={
    'experiment':'Last-place registration, FIFO waitlist promotion, idempotent QR check-in',
    'run_at_utc':time.strftime('%Y-%m-%dT%H:%M:%SZ',time.gmtime()),
    'database':args.database_label,
    'verification':'Local test inbox; synthetic accounts only',
    'students':100,'event_capacity':1,'initial_confirmed':1,'initial_waitlisted':99,
    'unexpected_errors':0,'registration_retries':20,'unique_registration_after_retries':1,
    'cancellations':1,'earliest_waiter_promoted':True,'next_waiter_position':1,
    'qr_scan_requests':20,'new_checkins':first_scans,'duplicate_scans':20-first_scans,
    'cancelled_pass_rejected':True,
    'registration_latency_ms':{'p50':round(statistics.median(latencies),2),
        'p95':round(latencies[math.ceil(.95*len(latencies))-1],2),'max':round(max(latencies),2)},
    'limitations':'One localhost run, one app process and PostgreSQL database. Registration timings exclude account creation. Not production throughput or real student adoption. QR tokens submitted via API; camera hardware is not exercised.'
}
Path(args.output).parent.mkdir(parents=True,exist_ok=True)
Path(args.output).write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps(report,indent=2))
