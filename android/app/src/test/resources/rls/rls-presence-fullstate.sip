NOTIFY sip:mundokamailio1@172.30.22.40:6171 SIP/2.0
Via: SIP/2.0/UDP 172.30.22.11:5060;branch=z9hG4bK9fa7.3689d6e2000000000000000000000000.0
To: <sip:mundokamailio1@172.30.22.11>;tag=7c1f9914db
From: <sip:rls@172.30.22.11>;tag=cde849600d132998877c82004fcea57f-07fe4256
CSeq: 1 NOTIFY
Call-ID: 01721d218d26c50a
Content-Length: 827
User-Agent: MundoPBX 2.0 (granja2)
Max-Forwards: 70
Event: presence
Contact: <sip:rls@172.30.22.11>
Subscription-State: active;expires=600
Require: eventlist
Content-Type: multipart/related;type="application/rlmi+xml";start="<1793064808.sip:rls@172.30.22.11.1630526114>";boundary="xGwQDsMuqagNMGqZyQGTdrbo"

--xGwQDsMuqagNMGqZyQGTdrbo
Content-Transfer-Encoding: binary
Content-ID: <1793064808.sip:rls@172.30.22.11.1630526114>
Content-Type: application/rlmi+xml;charset="UTF-8"

<?xml version="1.0"?>
<list uri="sip:rls@172.30.22.11" xmlns="urn:ietf:params:xml:ns:rlmi" version="1" fullState="true">
  <resource uri="sip:aiagent_lab@172.30.22.11"/>
  <resource uri="sip:comercial_demo@172.30.22.11"/>
  <resource uri="sip:mundokamailio2@172.30.22.11"/>
  <resource uri="sip:mundokamailio6@172.30.22.11"/>
  <resource uri="sip:mundokamailio7@172.30.22.11"/>
  <resource uri="sip:recepcion_demo@172.30.22.11"/>
  <resource uri="sip:rem_ana@172.30.22.11"/>
  <resource uri="sip:rem_ohid@172.30.22.11"/>
  <resource uri="sip:rem_xan@172.30.22.11"/>
  <resource uri="sip:rem_yall@172.30.22.11"/>
</list>

--xGwQDsMuqagNMGqZyQGTdrbo--
