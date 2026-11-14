NOTIFY sip:mundokamailio1@172.30.29.40:6171 SIP/2.0
Via: SIP/2.0/UDP 172.30.29.11:5060;branch=z9hG4bK3ac3.b2eb21a1000000000000000000000000.0
To: <sip:mundokamailio1@172.30.29.11>;tag=f7ac6bc22a
From: <sip:rls@172.30.29.11>;tag=47796bc524ceb084dc36a7a49e0520e0-a899a6c5
CSeq: 1 NOTIFY
Call-ID: ba31609bf36f4cb6
Content-Length: 827
User-Agent: MundoPBX 2.0 (granja9)
Max-Forwards: 70
Event: presence
Contact: <sip:rls@172.30.29.11>
Subscription-State: active;expires=60
Require: eventlist
Content-Type: multipart/related;type="application/rlmi+xml";start="<1793068475.sip:rls@172.30.29.11.1249967075>";boundary="avdgdZJnAeHV2qifVLslVrlm"

--avdgdZJnAeHV2qifVLslVrlm
Content-Transfer-Encoding: binary
Content-ID: <1793068475.sip:rls@172.30.29.11.1249967075>
Content-Type: application/rlmi+xml;charset="UTF-8"

<?xml version="1.0"?>
<list uri="sip:rls@172.30.29.11" xmlns="urn:ietf:params:xml:ns:rlmi" version="1" fullState="true">
  <resource uri="sip:aiagent_lab@172.30.29.11"/>
  <resource uri="sip:comercial_demo@172.30.29.11"/>
  <resource uri="sip:mundokamailio2@172.30.29.11"/>
  <resource uri="sip:mundokamailio6@172.30.29.11"/>
  <resource uri="sip:mundokamailio7@172.30.29.11"/>
  <resource uri="sip:recepcion_demo@172.30.29.11"/>
  <resource uri="sip:rem_ana@172.30.29.11"/>
  <resource uri="sip:rem_ohid@172.30.29.11"/>
  <resource uri="sip:rem_xan@172.30.29.11"/>
  <resource uri="sip:rem_yall@172.30.29.11"/>
</list>

--avdgdZJnAeHV2qifVLslVrlm--
