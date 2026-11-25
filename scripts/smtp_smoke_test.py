#!/usr/bin/env python3
"""Test real SMTP transport with a loopback capture server and a temporary H2 database.
No message leaves this computer. Requires scripts/package.sh to have built the JAR.
"""
import email,json,os,queue,re,socket,socketserver,subprocess,tempfile,threading,time,urllib.request,urllib.error,uuid
from pathlib import Path
messages=queue.Queue()
class SMTP(socketserver.StreamRequestHandler):
    def send(self,text): self.wfile.write((text+'\r\n').encode());self.wfile.flush()
    def handle(self):
        self.send('220 localhost test SMTP');body=[];data=False
        while True:
            raw=self.rfile.readline()
            if not raw:break
            line=raw.decode().rstrip('\r\n')
            if data:
                if line=='.':messages.put('\n'.join(body));body=[];data=False;self.send('250 queued')
                else:body.append(line)
            elif line.upper().startswith(('EHLO','HELO')):self.send('250 localhost')
            elif line.upper().startswith(('MAIL FROM:','RCPT TO:','RSET','NOOP')):self.send('250 OK')
            elif line.upper()=='DATA':data=True;self.send('354 End with dot')
            elif line.upper()=='QUIT':self.send('221 bye');break
            else:self.send('502 unsupported')
class Server(socketserver.ThreadingTCPServer):daemon_threads=True
server=Server(('127.0.0.1',0),SMTP);threading.Thread(target=server.serve_forever,daemon=True).start()
with socket.socket() as sock:sock.bind(('127.0.0.1',0));port=sock.getsockname()[1]
root=Path(__file__).resolve().parents[1];jar=root/'backend/target/seatlock-1.0.0.jar';organization=None
if not jar.exists():raise SystemExit('Run bash scripts/package.sh first.')
def request(path,body=None,token=None):
    headers={'Content-Type':'application/json'}
    if token:headers['Authorization']='Bearer '+token
    if organization:headers['X-Organization-ID']=organization
    req=urllib.request.Request(f'http://127.0.0.1:{port}/api'+path,data=json.dumps(body).encode() if body is not None else None,headers=headers)
    try:
        with urllib.request.urlopen(req,timeout=15) as response:return response.status,json.load(response)
    except urllib.error.HTTPError as error:return error.code,json.load(error)
def verify(token,recipient):
    status,result=request('/verification/send',{},token);assert status==200,result
    msg=email.message_from_string(messages.get(timeout=10));assert msg['To']==recipient and msg['From']=='seatlock@local.example.test'
    text=msg.get_payload(decode=True).decode(msg.get_content_charset() or 'utf-8');code=re.search(r'code is (\d{6})',text).group(1)
    status,result=request('/verification/confirm',{'code':code},token);assert status==200,result
    assert request('/verification/test-inbox',token=token)[0]==404
with tempfile.TemporaryDirectory(prefix='seatlock-smtp-') as directory:
    env=os.environ.copy();env.update(PORT=str(port),BIND_ADDRESS='127.0.0.1',DB_URL='jdbc:h2:file:'+directory+'/database;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE',DB_USER='sa',DB_PASSWORD='',MAIL_MODE='smtp',MAIL_FROM='seatlock@local.example.test',SMTP_HOST='127.0.0.1',SMTP_PORT=str(server.server_address[1]),SMTP_AUTH='false',SMTP_STARTTLS='false',SMTP_USERNAME='',SMTP_PASSWORD='')
    with open(Path(directory)/'app.log','w+') as log:
        process=subprocess.Popen(['java','-jar',str(jar)],cwd=directory,env=env,stdout=log,stderr=subprocess.STDOUT)
        try:
            for _ in range(100):
                try:
                    if request('/health')[0]==200:break
                except (OSError,urllib.error.URLError):pass
                if process.poll() is not None:log.seek(0);raise RuntimeError(log.read()[-4000:])
                time.sleep(.1)
            else:raise RuntimeError('App did not start')
            ownerEmail=uuid.uuid4().hex+'@alpha.example.test'
            status,owner=request('/organizer/register',{'name':'SMTP Organizer','email':ownerEmail,'password':'local-smtp-fixture-password'});assert status==200,owner;verify(owner['token'],ownerEmail)
            status,workspace=request('/organizer/organizations',{'name':'SMTP Alpha College','emailDomain':'alpha.example.test'},owner['token']);assert status==200,workspace;organization=workspace['id'];events={}
            for audience in ['COLLEGE','OPEN']:
                status,event=request('/admin/events',{'title':audience+' test','category':'Guest Talk','venue':'Test Hall','description':'SMTP verification fixture','startsAt':int(time.time()*1000)+3600000,'capacity':2,'seatsPerRow':10,'speaker':'Test','hostClub':'Test Club','bookingMode':'GENERAL','audience':audience},owner['token']);assert status==200,event;events[audience]=event['id']
            for domain in ['alpha.example.test','outside.example.test']:
                address=uuid.uuid4().hex+'@'+domain
                status,student=request('/auth/register',{'name':'SMTP Student','email':address,'password':'local-smtp-fixture-password'});assert status==200,student
                assert request('/events/'+events['OPEN']+'/registrations',{'requestKey':str(uuid.uuid4())},student['token'])[0]==403
                verify(student['token'],address)
                status,entry=request('/events/'+events['COLLEGE']+'/registrations',{'requestKey':str(uuid.uuid4())},student['token']);assert status==(200 if domain=='alpha.example.test' else 403),entry
                assert request('/events/'+events['OPEN']+'/registrations',{'requestKey':str(uuid.uuid4())},student['token'])[0]==200
            report={'run_at_utc':time.strftime('%Y-%m-%dT%H:%M:%SZ',time.gmtime()),'transport':'Actual JavaMail SMTP connection to loopback capture server','messages_received':3,'organizer_verified':True,'students_verified':2,'college_domain_rule_passed':True,'open_event_rule_passed':True,'unverified_registration_blocked':True,'test_inbox_disabled_in_smtp_mode':True,'limitations':'No external provider, TLS negotiation, spam filtering or real inbox delivery tested. TLS/auth disabled only for the loopback capture server.'}
            (root/'docs/smtp-test-result.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report,indent=2))
        finally:
            process.terminate()
            try:process.wait(timeout=10)
            except subprocess.TimeoutExpired:process.kill();process.wait()
            server.shutdown();server.server_close()
